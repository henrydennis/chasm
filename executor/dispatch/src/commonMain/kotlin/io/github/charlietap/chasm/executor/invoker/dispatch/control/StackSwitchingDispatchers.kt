@file:OptIn(InternalChasmApi::class)

package io.github.charlietap.chasm.executor.invoker.dispatch.control

import io.github.charlietap.chasm.InternalChasmApi
import io.github.charlietap.chasm.executor.invoker.function.HostFunctionCall
import io.github.charlietap.chasm.executor.invoker.function.activateContinuationWasmFrame
import io.github.charlietap.chasm.executor.invoker.instruction.control.ThrowRefValueExecutor
import io.github.charlietap.chasm.runtime.address.Address
import io.github.charlietap.chasm.runtime.dispatch.DispatchableInstruction
import io.github.charlietap.chasm.runtime.encoder.RV_SHIFT_BITS
import io.github.charlietap.chasm.runtime.encoder.RV_TYPE_CONTINUATION
import io.github.charlietap.chasm.runtime.encoder.RV_TYPE_MASK
import io.github.charlietap.chasm.runtime.error.InvocationError
import io.github.charlietap.chasm.runtime.exception.HostRaisedWasmException
import io.github.charlietap.chasm.runtime.exception.InvocationException
import io.github.charlietap.chasm.runtime.execution.ExecutionContext
import io.github.charlietap.chasm.runtime.ext.function
import io.github.charlietap.chasm.runtime.ext.isNullableReference
import io.github.charlietap.chasm.runtime.ext.toFunctionAddress
import io.github.charlietap.chasm.runtime.instance.ContinuationInstance
import io.github.charlietap.chasm.runtime.instance.FunctionInstance
import io.github.charlietap.chasm.runtime.instance.ModuleInstance
import io.github.charlietap.chasm.runtime.instance.PromptHandler
import io.github.charlietap.chasm.runtime.instance.PromptRecord
import io.github.charlietap.chasm.runtime.stack.ValueStack
import io.github.charlietap.chasm.runtime.stack.activationHeader
import io.github.charlietap.chasm.runtime.store.Store
import io.github.charlietap.chasm.type.FunctionType
import io.github.charlietap.chasm.type.ResultType

private val PROMPT_RETURN_INSTRUCTION = DispatchableInstruction { vstack, context, _ ->
    val prompt = context.promptStack.removeAt(context.promptStack.lastIndex)
    val destination = prompt.resultDestinationSlot
    if (destination != null && prompt.resultCount > 0) {
        vstack.copyRange(
            sourceOffset = prompt.stackBase,
            destinationOffset = prompt.callerFp + destination,
            count = prompt.resultCount,
        )
    }
    vstack.activateFrameAtDepth(prompt.callerFp, prompt.callerSp)
    prompt.returnIp
}

private val EMPTY_FUNCTION_TYPE = FunctionType(ResultType(emptyList()), ResultType(emptyList()))

fun ContNewDispatcher(
    functionSlot: Int,
    destinationSlot: Int,
    functionType: FunctionType,
    callerInstance: ModuleInstance,
): DispatchableInstruction = DispatchableInstruction { vstack, context, nextIp ->
    val rawFunc = vstack.getFrameSlot(functionSlot)
    if (rawFunc.isNullableReference()) {
        throw InvocationException(InvocationError.UnexpectedReferenceValue)
    }
    val fn = context.store.function(rawFunc.toFunctionAddress())
    val continuation = ContinuationInstance(
        functionType = functionType,
        initialFunction = fn,
        initialCallerInstance = callerInstance,
    )
    vstack.setFrameSlot(destinationSlot, encodeContinuation(context.store, continuation))
    nextIp
}

fun ContBindDispatcher(
    continuationSlot: Int,
    firstArgumentSlot: Int,
    argumentCount: Int,
    destinationSlot: Int,
    outputFunctionType: FunctionType,
): DispatchableInstruction = DispatchableInstruction { vstack, context, nextIp ->
    val old = takeUnconsumedContinuation(context.store, vstack.getFrameSlot(continuationSlot))
    val previousBound = old.boundArguments
    val newBound = LongArray(previousBound.size + argumentCount)
    previousBound.copyInto(newBound)
    for (index in 0 until argumentCount) {
        newBound[previousBound.size + index] = vstack.getFrameSlot(firstArgumentSlot + index)
    }
    val rebound = ContinuationInstance(
        functionType = outputFunctionType,
        boundArguments = newBound,
        initialFunction = old.initialFunction,
        initialCallerInstance = old.initialCallerInstance,
        stack = old.stack,
        savedFpOffset = old.savedFpOffset,
        resumeIp = old.resumeIp,
        resumeResultDestinationSlot = old.resumeResultDestinationSlot,
        resumeResultCount = old.resumeResultCount,
        savedPrompts = old.savedPrompts,
    )
    old.consume()
    vstack.setFrameSlot(destinationSlot, encodeContinuation(context.store, rebound))
    nextIp
}

fun SuspendDispatcher(
    tagAddress: Address.Tag,
    firstPayloadSlot: Int,
    payloadCount: Int,
    firstResultSlot: Int,
    resultCount: Int,
): DispatchableInstruction = DispatchableInstruction { vstack, context, nextIp ->
    val promptStack = context.promptStack
    val match = findMatchingPrompt(promptStack, tagAddress.address, matchSwitch = false)
        ?: throw InvocationException(InvocationError.UnhandledSuspension)

    val promptIndex = match.promptIndex
    val prompt = promptStack[promptIndex]
    val handler = match.handler
    val targetSlots = checkNotNull(handler.targetSlots)

    val payload = LongArray(payloadCount) { index -> vstack.getFrameSlot(firstPayloadSlot + index) }
    val capturedStack = vstack.slice(prompt.stackBase, vstack.sp)
    val savedFpOffset = vstack.fp - prompt.stackBase
    val savedPrompts = captureIntermediatePrompts(promptStack, promptIndex + 1, prompt.stackBase)

    while (promptStack.size > promptIndex) {
        promptStack.removeAt(promptStack.lastIndex)
    }

    val captured = ContinuationInstance(
        functionType = EMPTY_FUNCTION_TYPE,
        stack = capturedStack,
        savedFpOffset = savedFpOffset,
        resumeIp = nextIp,
        resumeResultDestinationSlot = firstResultSlot,
        resumeResultCount = resultCount,
        savedPrompts = savedPrompts,
    )
    val contRef = encodeContinuation(context.store, captured)

    vstack.activateFrameAtDepth(prompt.callerFp, prompt.callerFp + handler.targetStackSlotCount)
    for (index in 0 until payloadCount) {
        vstack.setFrameSlot(targetSlots[index], payload[index])
    }
    vstack.setFrameSlot(targetSlots[payloadCount], contRef)
    handler.targetIp
}

fun ResumeDispatcher(
    continuationSlot: Int,
    firstArgumentSlot: Int,
    argumentCount: Int,
    firstResultSlot: Int,
    resultCount: Int,
    handlers: Array<PromptHandler>,
): DispatchableInstruction = DispatchableInstruction { vstack, context, nextIp ->
    val continuation = takeUnconsumedContinuation(context.store, vstack.getFrameSlot(continuationSlot))
    val suppliedArgs = LongArray(argumentCount) { index -> vstack.getFrameSlot(firstArgumentSlot + index) }
    val prompt = PromptRecord(
        callerFp = vstack.fp,
        callerSp = vstack.sp,
        stackBase = vstack.sp,
        returnIp = nextIp,
        resumeCallSiteIp = nextIp - 1,
        resultDestinationSlot = firstResultSlot,
        resultCount = resultCount,
        handlers = handlers,
    )
    context.promptStack.add(prompt)
    activateContinuation(vstack, context, continuation, suppliedArgs, prompt)
}

fun ResumeThrowDispatcher(
    continuationSlot: Int,
    tagAddress: Address.Tag,
    firstPayloadSlot: Int,
    firstResultSlot: Int,
    resultCount: Int,
    handlers: Array<PromptHandler>,
): DispatchableInstruction = DispatchableInstruction { vstack, context, nextIp ->
    val continuation = takeUnconsumedContinuation(context.store, vstack.getFrameSlot(continuationSlot))
    val exceptionRef = context.heap.allocateExceptionFromFrame(context, tagAddress, firstPayloadSlot)
    resumeThrowContinuation(
        vstack = vstack,
        context = context,
        continuation = continuation,
        exceptionRef = exceptionRef,
        nextIp = nextIp,
        firstResultSlot = firstResultSlot,
        resultCount = resultCount,
        handlers = handlers,
    )
}

fun ResumeThrowRefDispatcher(
    continuationSlot: Int,
    exceptionSlot: Int,
    firstResultSlot: Int,
    resultCount: Int,
    handlers: Array<PromptHandler>,
): DispatchableInstruction = DispatchableInstruction { vstack, context, nextIp ->
    val rawCont = vstack.getFrameSlot(continuationSlot)
    val exceptionRef = vstack.getFrameSlot(exceptionSlot)
    if (rawCont.isNullableReference()) {
        throw InvocationException(InvocationError.NullContinuationReference)
    }
    if (exceptionRef.isNullableReference()) {
        throw InvocationException(InvocationError.UnexpectedReferenceValue)
    }
    val continuation = takeUnconsumedContinuation(context.store, rawCont)
    resumeThrowContinuation(
        vstack = vstack,
        context = context,
        continuation = continuation,
        exceptionRef = exceptionRef,
        nextIp = nextIp,
        firstResultSlot = firstResultSlot,
        resultCount = resultCount,
        handlers = handlers,
    )
}

fun SwitchDispatcher(
    tagAddress: Address.Tag,
    continuationSlot: Int,
    firstArgumentSlot: Int,
    argumentCount: Int,
    firstResultSlot: Int,
    resultCount: Int,
): DispatchableInstruction = DispatchableInstruction { vstack, context, nextIp ->
    val targetContinuation = takeUnconsumedContinuation(context.store, vstack.getFrameSlot(continuationSlot))
    val promptStack = context.promptStack
    val match = findMatchingPrompt(promptStack, tagAddress.address, matchSwitch = true)
        ?: throw InvocationException(InvocationError.UnhandledSuspension)

    val promptIndex = match.promptIndex
    val prompt = promptStack[promptIndex]

    val args = LongArray(argumentCount + 1)
    for (index in 0 until argumentCount) {
        args[index] = vstack.getFrameSlot(firstArgumentSlot + index)
    }
    val capturedStack = vstack.slice(prompt.stackBase, vstack.sp)
    val savedFpOffset = vstack.fp - prompt.stackBase
    val savedPrompts = captureIntermediatePrompts(promptStack, promptIndex + 1, prompt.stackBase)

    // Keep the matched switch prompt installed on promptStack.
    while (promptStack.size > promptIndex + 1) {
        promptStack.removeAt(promptStack.lastIndex)
    }

    val currentContinuation = ContinuationInstance(
        functionType = EMPTY_FUNCTION_TYPE,
        stack = capturedStack,
        savedFpOffset = savedFpOffset,
        resumeIp = nextIp,
        resumeResultDestinationSlot = firstResultSlot,
        resumeResultCount = resultCount,
        savedPrompts = savedPrompts,
    )
    args[argumentCount] = encodeContinuation(context.store, currentContinuation)
    activateContinuation(vstack, context, targetContinuation, args, prompt)
}

private fun resumeThrowContinuation(
    vstack: ValueStack,
    context: ExecutionContext,
    continuation: ContinuationInstance,
    exceptionRef: Long,
    nextIp: Int,
    firstResultSlot: Int,
    resultCount: Int,
    handlers: Array<PromptHandler>,
): Int {
    val stack = continuation.stack
    val savedFpOffset = continuation.savedFpOffset
    val resumeIp = continuation.resumeIp
    val savedPrompts = continuation.savedPrompts
    continuation.consume()

    if (stack == null) {
        return ThrowRefValueExecutor(vstack, context, exceptionRef, nextIp - 1)
    }

    val prompt = PromptRecord(
        callerFp = vstack.fp,
        callerSp = vstack.sp,
        stackBase = vstack.sp,
        returnIp = nextIp,
        resumeCallSiteIp = nextIp - 1,
        resultDestinationSlot = firstResultSlot,
        resultCount = resultCount,
        handlers = handlers,
    )
    context.promptStack.add(prompt)

    val stackBase = prompt.stackBase
    vstack.restoreSlice(stackBase, stack)
    vstack.activateFrameAtDepth(stackBase + savedFpOffset, stackBase + stack.size)
    if (savedPrompts != null) {
        for (saved in savedPrompts) {
            context.promptStack.add(saved.shifted(stackBase))
        }
    }
    return ThrowRefValueExecutor(vstack, context, exceptionRef, resumeIp - 1)
}

private fun activateContinuation(
    vstack: ValueStack,
    context: ExecutionContext,
    continuation: ContinuationInstance,
    suppliedArgs: LongArray,
    prompt: PromptRecord,
): Int {
    val boundArgs = continuation.boundArguments
    val initialFunction = continuation.initialFunction
    val initialCaller = continuation.initialCallerInstance
    val stack = continuation.stack
    val savedFpOffset = continuation.savedFpOffset
    val resumeIp = continuation.resumeIp
    val resumeDest = continuation.resumeResultDestinationSlot
    val resumeResultCount = continuation.resumeResultCount
    val savedPrompts = continuation.savedPrompts
    continuation.consume()

    val allArgs = if (boundArgs.isEmpty()) {
        suppliedArgs
    } else if (suppliedArgs.isEmpty()) {
        boundArgs
    } else {
        LongArray(boundArgs.size + suppliedArgs.size).also { combined ->
            boundArgs.copyInto(combined)
            suppliedArgs.copyInto(combined, destinationOffset = boundArgs.size)
        }
    }

    val stackBase = prompt.stackBase
    if (stack != null) {
        vstack.restoreSlice(stackBase, stack)
        val restoredFp = stackBase + savedFpOffset
        vstack.activateFrameAtDepth(restoredFp, stackBase + stack.size)
        if (savedPrompts != null) {
            for (saved in savedPrompts) {
                context.promptStack.add(saved.shifted(stackBase))
            }
        }
        if (resumeDest != null) {
            for (index in 0 until resumeResultCount) {
                vstack.setFrameSlot(resumeDest + index, allArgs[index])
            }
        }
        return resumeIp
    }

    return when (val fn = checkNotNull(initialFunction)) {
        is FunctionInstance.WasmFunction -> {
            val promptReturnIp = context.store.program.ensurePromptReturn(PROMPT_RETURN_INSTRUCTION)
            activateContinuationWasmFrame(
                vstack = vstack,
                strategy = fn.callStrategy,
                stackBase = stackBase,
                arguments = allArgs,
                activationHeader = activationHeader(promptReturnIp, 0),
            )
        }
        is FunctionInstance.HostFunction -> {
            val interfaceSlots = maxOf(allArgs.size, prompt.resultCount)
            vstack.ensureCapacity(stackBase + interfaceSlots)
            for (index in allArgs.indices) {
                vstack.setFrameSlot(stackBase, index, allArgs[index])
            }
            vstack.activateFrame(stackBase, interfaceSlots)
            try {
                HostFunctionCall(
                    vstack = vstack,
                    context = context,
                    caller = checkNotNull(initialCaller),
                    function = fn,
                    parameterSlotBase = 0,
                    resultSlotBase = 0,
                )
                context.promptStack.removeAt(context.promptStack.lastIndex)
                val dest = prompt.resultDestinationSlot
                if (dest != null && prompt.resultCount > 0) {
                    vstack.copyRange(stackBase, prompt.callerFp + dest, prompt.resultCount)
                }
                vstack.activateFrameAtDepth(prompt.callerFp, prompt.callerSp)
                prompt.returnIp
            } catch (_: HostRaisedWasmException) {
                context.promptStack.removeAt(context.promptStack.lastIndex)
                vstack.activateFrameAtDepth(prompt.callerFp, prompt.callerSp)
                ThrowRefValueExecutor(
                    vstack = vstack,
                    context = context,
                    ref = context.heap.takePendingExceptionReference(),
                    faultIp = prompt.resumeCallSiteIp,
                )
            }
        }
    }
}

private data class PromptMatch(
    val promptIndex: Int,
    val handler: PromptHandler,
)

private fun findMatchingPrompt(
    promptStack: ArrayList<PromptRecord>,
    tagAddress: Int,
    matchSwitch: Boolean,
): PromptMatch? {
    for (promptIndex in promptStack.lastIndex downTo 0) {
        val handlers = promptStack[promptIndex].handlers
        for (handler in handlers) {
            if (handler.isSwitch == matchSwitch && handler.tagAddress == tagAddress) {
                return PromptMatch(promptIndex, handler)
            }
        }
    }
    return null
}

private fun captureIntermediatePrompts(
    promptStack: ArrayList<PromptRecord>,
    fromIndex: Int,
    baseOffset: Int,
): List<PromptRecord>? {
    if (fromIndex > promptStack.lastIndex) return null
    val captured = ArrayList<PromptRecord>(promptStack.size - fromIndex)
    for (index in fromIndex..promptStack.lastIndex) {
        captured.add(promptStack[index].shifted(-baseOffset))
    }
    return captured
}

private fun encodeContinuation(
    store: Store,
    continuation: ContinuationInstance,
): Long {
    val index = store.continuations.size
    store.continuations.add(continuation)
    return (index.toLong() shl RV_SHIFT_BITS) or RV_TYPE_CONTINUATION
}

private fun takeUnconsumedContinuation(
    store: Store,
    raw: Long,
): ContinuationInstance {
    if (raw.isNullableReference()) {
        throw InvocationException(InvocationError.NullContinuationReference)
    }
    if ((raw and RV_TYPE_MASK) != RV_TYPE_CONTINUATION) {
        throw InvocationException(InvocationError.UnexpectedReferenceValue)
    }
    val index = (raw ushr RV_SHIFT_BITS).toInt()
    val continuation = store.continuations[index]
    if (continuation.consumed) {
        throw InvocationException(InvocationError.ContinuationAlreadyConsumed)
    }
    return continuation
}
