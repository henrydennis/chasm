package io.github.charlietap.chasm.executor.invoker.function

import io.github.charlietap.chasm.InternalChasmApi
import io.github.charlietap.chasm.executor.invoker.instruction.control.ThrowRefValueExecutor
import io.github.charlietap.chasm.host.HostFunctionException
import io.github.charlietap.chasm.host.UnsafeHostApi
import io.github.charlietap.chasm.runtime.error.InvocationError
import io.github.charlietap.chasm.runtime.exception.HostRaisedWasmException
import io.github.charlietap.chasm.runtime.exception.InvocationException
import io.github.charlietap.chasm.runtime.execution.ExecutionContext
import io.github.charlietap.chasm.runtime.host.RawHostFunction
import io.github.charlietap.chasm.runtime.instance.FunctionInstance
import io.github.charlietap.chasm.runtime.instance.ModuleInstance
import io.github.charlietap.chasm.runtime.program.SUSPEND_IP
import io.github.charlietap.chasm.runtime.stack.ValueStack

@InternalChasmApi
@OptIn(UnsafeHostApi::class)
fun HostFunctionCall(
    vstack: ValueStack,
    context: ExecutionContext,
    caller: ModuleInstance,
    function: FunctionInstance.HostFunction,
    parameterSlotBase: Int,
    resultSlotBase: Int,
) {
    val fp = vstack.fp
    function.function.invokeHost(
        stack = vstack.unsafeElements(),
        parameterBase = fp + parameterSlotBase,
        resultBase = fp + resultSlotBase,
        caller = caller,
        context = context,
    )
}

internal inline fun RawHostFunction.invokeHost(
    stack: LongArray,
    parameterBase: Int,
    resultBase: Int,
    caller: ModuleInstance,
    context: ExecutionContext,
) {
    try {
        context.withHostCallbackScope {
            context(stack, caller, context) {
                invoke(parameterBase, resultBase)
            }
        }
    } catch (exception: HostFunctionException) {
        throw InvocationException(InvocationError.HostFunctionError(exception.reason))
    }
}

// Catch here so the dispatch loop does not need to preserve the current IP.
@InternalChasmApi
inline fun withHostExceptionHandling(
    vstack: ValueStack,
    context: ExecutionContext,
    nextIp: Int,
    call: () -> Int,
): Int = try {
    call()
} catch (_: HostRaisedWasmException) {
    ThrowRefValueExecutor(vstack, context, context.heap.takePendingExceptionReference(), nextIp - 1)
}

@InternalChasmApi
inline fun finishHostCall(
    context: ExecutionContext,
    nextIp: Int,
): Int {
    if (context.pendingSuspension != null) {
        context.interpreterResumeIp = nextIp
        context.interpreterReturnActivationHeaderSlot = -1
        return SUSPEND_IP
    }
    return nextIp
}

@InternalChasmApi
inline fun finishReturnHostCall(
    vstack: ValueStack,
    context: ExecutionContext,
    resultCount: Int,
    activationHeaderSlot: Int,
    nextIp: Int,
): Int {
    if (context.pendingSuspension != null) {
        context.interpreterResumeIp = nextIp
        context.interpreterReturnResultCount = resultCount
        context.interpreterReturnActivationHeaderSlot = activationHeaderSlot
        return SUSPEND_IP
    }
    return returnToCaller(vstack, context.store, resultCount, activationHeaderSlot)
}
