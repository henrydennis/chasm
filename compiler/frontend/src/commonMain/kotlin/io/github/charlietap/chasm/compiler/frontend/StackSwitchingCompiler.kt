package io.github.charlietap.chasm.compiler.frontend

import io.github.charlietap.chasm.ast.instruction.ControlInstruction
import io.github.charlietap.chasm.ast.module.Index
import io.github.charlietap.chasm.ast.module.toInt
import io.github.charlietap.chasm.compiler.frontend.context.FunctionCompilationContext
import io.github.charlietap.chasm.compiler.frontend.context.tag
import io.github.charlietap.chasm.compiler.frontend.context.tagType
import io.github.charlietap.chasm.compiler.frontend.context.type
import io.github.charlietap.chasm.compiler.frontend.instruction.callFrameOffset
import io.github.charlietap.chasm.type.ConcreteHeapType
import io.github.charlietap.chasm.type.ReferenceType
import io.github.charlietap.chasm.type.ValueType
import io.github.charlietap.chasm.cir.ControlInstruction as CirControlInstruction

internal fun compileContNew(
    state: FunctionCompilationContext,
    instruction: ControlInstruction.ContNew,
) {
    val functionOperand = state.pop()
    val functionSlot = state.materialize(functionOperand)
    state.frame.reserve(functionSlot)
    val dest = destination(state, null)
    state.emit(
        CirControlInstruction.ContNew(
            type = state.compiler.type(instruction.typeIndex),
            functionSlot = functionSlot,
            destinationSlot = dest.slot,
        ),
    )
    val definedType = state.compiler.types.definedType(instruction.typeIndex)
    val contRefType = ValueType.Reference(ReferenceType.Ref(ConcreteHeapType.Defined(definedType)))
    completeDestination(state, contRefType, dest)
}

internal fun compileContBind(
    state: FunctionCompilationContext,
    instruction: ControlInstruction.ContBind,
) {
    val inputFunc = state.compiler.continuationFunctionTypeAt(instruction.inputTypeIndex.toInt())
    val outputFunc = state.compiler.continuationFunctionTypeAt(instruction.outputTypeIndex.toInt())
    val bindCount = inputFunc.params.types.size - outputFunc.params.types.size
    val contOperand = state.pop()
    val args = state.pop(bindCount)
    val contSlot = state.materialize(contOperand)
    state.frame.reserve(contSlot)
    val contiguous = state.contiguousFrameSourceOrNull(args)
    val materializedArgs = args.isNotEmpty() && contiguous == null
    val firstArgSlot = contiguous ?: if (args.isEmpty()) 0 else state.materializeContiguous(args)
    val dest = destination(state, null)
    state.emit(
        CirControlInstruction.ContBind(
            inputType = state.compiler.type(instruction.inputTypeIndex),
            outputType = state.compiler.type(instruction.outputTypeIndex),
            continuationSlot = contSlot,
            firstArgumentSlot = firstArgSlot,
            argumentCount = bindCount,
            destinationSlot = dest.slot,
        ),
    )
    if (materializedArgs) state.releaseContiguous(firstArgSlot, bindCount)
    val definedType = state.compiler.types.definedType(instruction.outputTypeIndex)
    val contRefType = ValueType.Reference(ReferenceType.Ref(ConcreteHeapType.Defined(definedType)))
    completeDestination(state, contRefType, dest)
}

internal fun compileSuspend(
    state: FunctionCompilationContext,
    instruction: ControlInstruction.Suspend,
) {
    val tagFunc = state.compiler.tagType(instruction.tagIndex).functionType
    val payloads = state.pop(tagFunc.params.types.size)
    state.materializeBelow(0)
    val contiguous = state.contiguousFrameSourceOrNull(payloads)
    val materializedPayloads = payloads.isNotEmpty() && contiguous == null
    val firstPayloadSlot = contiguous ?: if (payloads.isEmpty()) 0 else state.materializeContiguous(payloads)
    val resultTypes = tagFunc.results.types
    val firstResultSlot = state.callFrameOffset()
    state.frame.reserve(firstResultSlot + resultTypes.size)
    state.emit(
        CirControlInstruction.Suspend(
            tagIndex = state.compiler.tag(instruction.tagIndex),
            firstPayloadSlot = firstPayloadSlot,
            payloadCount = payloads.size,
            firstResultSlot = firstResultSlot,
            resultCount = resultTypes.size,
        ),
    )
    if (materializedPayloads) state.releaseContiguous(firstPayloadSlot, payloads.size)
    pushResultSlots(state, resultTypes, firstResultSlot)
}

internal fun compileResume(
    state: FunctionCompilationContext,
    instruction: ControlInstruction.Resume,
) {
    val contFunc = state.compiler.continuationFunctionTypeAt(instruction.typeIndex.toInt())
    val contOperand = state.pop()
    val args = state.pop(contFunc.params.types.size)
    state.materializeBelow(0)
    val contSlot = state.materialize(contOperand)
    state.frame.reserve(contSlot)
    val contiguous = state.contiguousFrameSourceOrNull(args)
    val materializedArgs = args.isNotEmpty() && contiguous == null
    val firstArgSlot = contiguous ?: if (args.isEmpty()) 0 else state.materializeContiguous(args)
    val resultTypes = contFunc.results.types
    val firstResultSlot = state.callFrameOffset()
    state.frame.reserve(firstResultSlot + resultTypes.size)
    emitWithResumeHandlers(state, instruction.handlers) { handlers ->
        CirControlInstruction.Resume(
            type = state.compiler.type(instruction.typeIndex),
            continuationSlot = contSlot,
            firstArgumentSlot = firstArgSlot,
            argumentCount = args.size,
            firstResultSlot = firstResultSlot,
            resultCount = resultTypes.size,
            handlers = handlers,
        )
    }
    if (materializedArgs) state.releaseContiguous(firstArgSlot, args.size)
    pushResultSlots(state, resultTypes, firstResultSlot)
}

internal fun compileResumeThrow(
    state: FunctionCompilationContext,
    instruction: ControlInstruction.ResumeThrow,
) {
    val contFunc = state.compiler.continuationFunctionTypeAt(instruction.typeIndex.toInt())
    val tagFunc = state.compiler.tagType(instruction.tagIndex).functionType
    val contOperand = state.pop()
    val payloads = state.pop(tagFunc.params.types.size)
    state.materializeBelow(0)
    val contSlot = state.materialize(contOperand)
    state.frame.reserve(contSlot)
    val contiguous = state.contiguousFrameSourceOrNull(payloads)
    val materializedPayloads = payloads.isNotEmpty() && contiguous == null
    val firstPayloadSlot = contiguous ?: if (payloads.isEmpty()) 0 else state.materializeContiguous(payloads)
    val resultTypes = contFunc.results.types
    val firstResultSlot = state.callFrameOffset()
    state.frame.reserve(firstResultSlot + resultTypes.size)
    emitWithResumeHandlers(state, instruction.handlers) { handlers ->
        CirControlInstruction.ResumeThrow(
            type = state.compiler.type(instruction.typeIndex),
            exceptionTag = state.compiler.tag(instruction.tagIndex),
            continuationSlot = contSlot,
            firstPayloadSlot = firstPayloadSlot,
            payloadCount = payloads.size,
            firstResultSlot = firstResultSlot,
            resultCount = resultTypes.size,
            handlers = handlers,
        )
    }
    if (materializedPayloads) state.releaseContiguous(firstPayloadSlot, payloads.size)
    pushResultSlots(state, resultTypes, firstResultSlot)
}

internal fun compileResumeThrowRef(
    state: FunctionCompilationContext,
    instruction: ControlInstruction.ResumeThrowRef,
) {
    val contFunc = state.compiler.continuationFunctionTypeAt(instruction.typeIndex.toInt())
    val contOperand = state.pop()
    val exceptionOperand = state.pop()
    state.materializeBelow(0)
    val contSlot = state.materialize(contOperand)
    state.frame.reserve(contSlot)
    val exceptionSlot = state.materialize(exceptionOperand)
    state.frame.reserve(exceptionSlot)
    val resultTypes = contFunc.results.types
    val firstResultSlot = state.callFrameOffset()
    state.frame.reserve(firstResultSlot + resultTypes.size)
    emitWithResumeHandlers(state, instruction.handlers) { handlers ->
        CirControlInstruction.ResumeThrowRef(
            type = state.compiler.type(instruction.typeIndex),
            continuationSlot = contSlot,
            exceptionSlot = exceptionSlot,
            firstResultSlot = firstResultSlot,
            resultCount = resultTypes.size,
            handlers = handlers,
        )
    }
    pushResultSlots(state, resultTypes, firstResultSlot)
}

internal fun compileSwitch(
    state: FunctionCompilationContext,
    instruction: ControlInstruction.Switch,
) {
    val targetFunc = state.compiler.continuationFunctionTypeAt(instruction.typeIndex.toInt())
    val lastParam = targetFunc.params.types.last() as ValueType.Reference
    val currentFunc = state.compiler.continuationFunctionType(lastParam.referenceType.heapType)
    val contOperand = state.pop()
    val args = state.pop(targetFunc.params.types.size - 1)
    state.materializeBelow(0)
    val contSlot = state.materialize(contOperand)
    state.frame.reserve(contSlot)
    val contiguous = state.contiguousFrameSourceOrNull(args)
    val materializedArgs = args.isNotEmpty() && contiguous == null
    val firstArgSlot = contiguous ?: if (args.isEmpty()) 0 else state.materializeContiguous(args)
    val resultTypes = currentFunc.params.types
    val firstResultSlot = state.callFrameOffset()
    state.frame.reserve(firstResultSlot + resultTypes.size)
    state.emit(
        CirControlInstruction.Switch(
            type = state.compiler.type(instruction.typeIndex),
            tagIndex = state.compiler.tag(instruction.tagIndex),
            continuationSlot = contSlot,
            firstArgumentSlot = firstArgSlot,
            argumentCount = args.size,
            firstResultSlot = firstResultSlot,
            resultCount = resultTypes.size,
        ),
    )
    if (materializedArgs) state.releaseContiguous(firstArgSlot, args.size)
    pushResultSlots(state, resultTypes, firstResultSlot)
}

private fun emitWithResumeHandlers(
    state: FunctionCompilationContext,
    clauses: List<ControlInstruction.OnClause>,
    build: (List<CirControlInstruction.ResumeHandler>) -> CirControlInstruction,
) {
    val labelIndices = ArrayList<Int>()
    val prepared = clauses.map { clause ->
        when (clause) {
            is ControlInstruction.OnClause.OnLabel -> {
                val target = state.target(clause.labelIndex.toInt())
                target.reachedByBranch = true
                val prefixEnd = state.operands.highestReservedSlot(target.baseHeight) + 1
                val payloadEnd = (target.branchSlots.maxOrNull() ?: -1) + 1
                val stackSlotCount = maxOf(state.layout.temporarySlotBase, prefixEnd, payloadEnd)
                val targetSlotInArray = labelIndices.size
                labelIndices.add(target.branchTarget.index)
                PreparedClause.Label(
                    tag = state.compiler.tag(clause.tagIndex),
                    targetArrayIndex = targetSlotInArray,
                    targetSlots = target.branchSlots,
                    stackSlotCount = stackSlotCount,
                )
            }
            is ControlInstruction.OnClause.OnSwitch -> PreparedClause.Switch(state.compiler.tag(clause.tagIndex))
        }
    }
    if (labelIndices.isEmpty()) {
        val handlers = prepared.map { clause ->
            CirControlInstruction.ResumeHandler.OnSwitch((clause as PreparedClause.Switch).tag)
        }
        state.emit(build(handlers))
    } else {
        state.append(labelIndices.toIntArray()) { resolvedOffsets ->
            val handlers = prepared.map { clause ->
                when (clause) {
                    is PreparedClause.Label -> CirControlInstruction.ResumeHandler.OnLabel(
                        tagIndex = clause.tag,
                        targetOffset = resolvedOffsets[clause.targetArrayIndex],
                        targetSlots = clause.targetSlots,
                        stackSlotCount = clause.stackSlotCount,
                    )
                    is PreparedClause.Switch -> CirControlInstruction.ResumeHandler.OnSwitch(clause.tag)
                }
            }
            build(handlers)
        }
    }
}

private sealed interface PreparedClause {
    data class Label(
        val tag: io.github.charlietap.chasm.cir.TagIndex,
        val targetArrayIndex: Int,
        val targetSlots: IntArray,
        val stackSlotCount: Int,
    ) : PreparedClause

    data class Switch(
        val tag: io.github.charlietap.chasm.cir.TagIndex,
    ) : PreparedClause
}

private fun pushResultSlots(
    state: FunctionCompilationContext,
    types: List<ValueType>,
    firstSlot: Int,
) {
    for (index in types.indices) {
        state.pushFrame(types[index], firstSlot + index)
    }
}
