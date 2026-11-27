package io.github.charlietap.chasm.compiler.backend.chasm.link

import io.github.charlietap.chasm.cir.ControlInstruction
import io.github.charlietap.chasm.compiler.backend.chasm.ext.toRuntime
import io.github.charlietap.chasm.executor.invoker.dispatch.control.CallIndirectIDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.CallIndirectSDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.CallRefSDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.ContBindDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.ContNewDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.FunctionReturnDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.HostCallDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.ResumeDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.ResumeThrowDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.ResumeThrowRefDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.ReturnCallIndirectIDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.ReturnCallIndirectSDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.ReturnCallRefSDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.ReturnHostCallDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.SuspendDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.SwitchDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.ThrowDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.ThrowRefSDispatcher
import io.github.charlietap.chasm.executor.invoker.dispatch.control.UnreachableDispatcher
import io.github.charlietap.chasm.runtime.dispatch.DispatchableInstruction
import io.github.charlietap.chasm.runtime.instance.FunctionInstance
import io.github.charlietap.chasm.runtime.instance.PromptHandler
import io.github.charlietap.chasm.type.CompositeType
import io.github.charlietap.chasm.type.ConcreteHeapType
import io.github.charlietap.chasm.type.FunctionType
import io.github.charlietap.chasm.type.ext.asFunctionType

internal fun linkControlInstruction(
    instruction: ControlInstruction,
    context: LinkContext,
): DispatchableInstruction = when (instruction) {
    is ControlInstruction.Call -> linkCall(instruction, context)
    is ControlInstruction.FunctionReturn -> FunctionReturnDispatcher(
        instruction.results.toRuntime(),
        instruction.activationHeaderSlot,
    )
    is ControlInstruction.Unreachable -> UnreachableDispatcher()
    is ControlInstruction.CallIndirectS -> CallIndirectSDispatcher(
        instruction.elementIndexSlot,
        instruction.operands.toRuntime(),
        context.runtimeTypes[instruction.type.index],
        context.tables[instruction.table.index],
        context.instance,
        instruction.callFrameOffset,
        context.nextIp,
        instruction.resultDestinationSlot,
    )
    is ControlInstruction.CallIndirectI -> CallIndirectIDispatcher(
        instruction.elementIndex,
        instruction.operands.toRuntime(),
        context.runtimeTypes[instruction.type.index],
        context.tables[instruction.table.index],
        context.instance,
        instruction.callFrameOffset,
        context.nextIp,
        instruction.resultDestinationSlot,
    )
    is ControlInstruction.CallRefS -> CallRefSDispatcher(
        instruction.functionSlot,
        instruction.operands.toRuntime(),
        context.instance,
        instruction.callFrameOffset,
        context.nextIp,
        instruction.resultDestinationSlot,
    )
    is ControlInstruction.ReturnCall -> linkReturnCall(instruction, context)
    is ControlInstruction.ReturnCallIndirectS -> ReturnCallIndirectSDispatcher(
        instruction.elementIndexSlot,
        instruction.operands.toRuntime(),
        context.runtimeTypes[instruction.type.index],
        context.tables[instruction.table.index],
        context.instance,
        instruction.callFrameOffset,
        instruction.callerActivationHeaderSlot,
    )
    is ControlInstruction.ReturnCallIndirectI -> ReturnCallIndirectIDispatcher(
        instruction.elementIndex,
        instruction.operands.toRuntime(),
        context.runtimeTypes[instruction.type.index],
        context.tables[instruction.table.index],
        context.instance,
        instruction.callFrameOffset,
        instruction.callerActivationHeaderSlot,
    )
    is ControlInstruction.ReturnCallRefS -> ReturnCallRefSDispatcher(
        instruction.functionSlot,
        instruction.operands.toRuntime(),
        context.instance,
        instruction.callFrameOffset,
        instruction.callerActivationHeaderSlot,
    )
    is ControlInstruction.Throw -> ThrowDispatcher(
        context.instance.tagAddresses[instruction.exceptionTag.index],
        instruction.firstPayloadSlot,
    )
    is ControlInstruction.ThrowRefS -> ThrowRefSDispatcher(instruction.exceptionSlot)
    is ControlInstruction.ContNew -> ContNewDispatcher(
        functionSlot = instruction.functionSlot,
        destinationSlot = instruction.destinationSlot,
        functionType = continuationFunctionType(context, instruction.type.index),
        callerInstance = context.instance,
    )
    is ControlInstruction.ContBind -> ContBindDispatcher(
        continuationSlot = instruction.continuationSlot,
        firstArgumentSlot = instruction.firstArgumentSlot,
        argumentCount = instruction.argumentCount,
        destinationSlot = instruction.destinationSlot,
        outputFunctionType = continuationFunctionType(context, instruction.outputType.index),
    )
    is ControlInstruction.Suspend -> SuspendDispatcher(
        tagAddress = context.instance.tagAddresses[instruction.tagIndex.index],
        firstPayloadSlot = instruction.firstPayloadSlot,
        payloadCount = instruction.payloadCount,
        firstResultSlot = instruction.firstResultSlot,
        resultCount = instruction.resultCount,
    )
    is ControlInstruction.Resume -> ResumeDispatcher(
        continuationSlot = instruction.continuationSlot,
        firstArgumentSlot = instruction.firstArgumentSlot,
        argumentCount = instruction.argumentCount,
        firstResultSlot = instruction.firstResultSlot,
        resultCount = instruction.resultCount,
        handlers = linkResumeHandlers(instruction.handlers, context),
    )
    is ControlInstruction.ResumeThrow -> ResumeThrowDispatcher(
        continuationSlot = instruction.continuationSlot,
        tagAddress = context.instance.tagAddresses[instruction.exceptionTag.index],
        firstPayloadSlot = instruction.firstPayloadSlot,
        firstResultSlot = instruction.firstResultSlot,
        resultCount = instruction.resultCount,
        handlers = linkResumeHandlers(instruction.handlers, context),
    )
    is ControlInstruction.ResumeThrowRef -> ResumeThrowRefDispatcher(
        continuationSlot = instruction.continuationSlot,
        exceptionSlot = instruction.exceptionSlot,
        firstResultSlot = instruction.firstResultSlot,
        resultCount = instruction.resultCount,
        handlers = linkResumeHandlers(instruction.handlers, context),
    )
    is ControlInstruction.Switch -> SwitchDispatcher(
        tagAddress = context.instance.tagAddresses[instruction.tagIndex.index],
        continuationSlot = instruction.continuationSlot,
        firstArgumentSlot = instruction.firstArgumentSlot,
        argumentCount = instruction.argumentCount,
        firstResultSlot = instruction.firstResultSlot,
        resultCount = instruction.resultCount,
    )
}

private fun linkResumeHandlers(
    handlers: List<ControlInstruction.ResumeHandler>,
    context: LinkContext,
): Array<PromptHandler> = Array(handlers.size) { index ->
    when (val handler = handlers[index]) {
        is ControlInstruction.ResumeHandler.OnLabel -> PromptHandler(
            tagAddress = context.instance.tagAddresses[handler.tagIndex.index].address,
            targetIp = context.ip(handler.targetOffset),
            targetSlots = handler.targetSlots,
            targetStackSlotCount = handler.stackSlotCount,
        )
        is ControlInstruction.ResumeHandler.OnSwitch -> PromptHandler(
            tagAddress = context.instance.tagAddresses[handler.tagIndex.index].address,
            targetIp = -1,
            targetSlots = null,
            targetStackSlotCount = 0,
        )
    }
}

private fun continuationFunctionType(
    context: LinkContext,
    typeIndex: Int,
): FunctionType {
    val cont = context.definedTypes[typeIndex].asSubType.compositeType as CompositeType.Cont
    return when (val funcHeap = cont.functionType) {
        is ConcreteHeapType.Defined -> funcHeap.definedType.asSubType.compositeType.asFunctionType()
        is ConcreteHeapType.TypeIndex -> context.definedTypes[funcHeap.index].asSubType.compositeType.asFunctionType()
        is ConcreteHeapType.RecursiveTypeIndex -> error("unclosed continuation function type")
    }
}

private fun linkCall(
    instruction: ControlInstruction.Call,
    context: LinkContext,
): DispatchableInstruction = when (val function = context.functions[instruction.function.index]) {
    is FunctionInstance.WasmFunction -> {
        checkWasmCallFrame(instruction.callFrameOffset)
        context.deferWasmCall(
            PendingWasmCall.Call(
                ip = context.nextIp,
                callee = function,
                operands = instruction.operands.toRuntime(),
                callFrameOffset = instruction.callFrameOffset,
                resultDestinationSlot = instruction.resultDestinationSlot,
            ),
        )
    }
    is FunctionInstance.HostFunction -> HostCallDispatcher(
        function,
        context.instance,
        instruction.operands.toRuntime(),
        instruction.callFrameOffset,
        instruction.resultDestinationSlot,
    )
}

private fun linkReturnCall(
    instruction: ControlInstruction.ReturnCall,
    context: LinkContext,
): DispatchableInstruction = when (val function = context.functions[instruction.function.index]) {
    is FunctionInstance.WasmFunction -> context.deferWasmCall(
        PendingWasmCall.ReturnCall(
            ip = context.nextIp,
            callee = function,
            operands = instruction.operands.wasm.toRuntime(),
            callerActivationHeaderSlot = instruction.callerActivationHeaderSlot,
        ),
    )
    is FunctionInstance.HostFunction -> {
        val parameterCount = function.functionType.params.types.size
        context.frameSlots = maxOf(context.frameSlots, instruction.callFrameOffset + parameterCount + 1)
        ReturnHostCallDispatcher(
            function,
            context.instance,
            instruction.operands.host.toRuntime(),
            instruction.callFrameOffset,
            instruction.callerActivationHeaderSlot,
        )
    }
}

private fun checkWasmCallFrame(
    callFrameOffset: Int,
) {
    check(callFrameOffset in 0 until CALLER_FRAME_DELTA_LIMIT) {
        "Wasm caller-frame displacement exceeds the activation-header representation"
    }
}

private const val CALLER_FRAME_DELTA_LIMIT = 1 shl 25
