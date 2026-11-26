package io.github.charlietap.chasm.compiler.backend.jvm.lower

import io.github.charlietap.chasm.compiler.backend.abi.CONTEXT_TYPE
import io.github.charlietap.chasm.compiler.backend.abi.INT_BITS_TO_FLOAT
import io.github.charlietap.chasm.compiler.backend.abi.LONG_BITS_TO_DOUBLE
import io.github.charlietap.chasm.compiler.backend.abi.OBJECT_TYPE
import io.github.charlietap.chasm.compiler.backend.abi.POP_SUSPENDED_FRAME
import io.github.charlietap.chasm.compiler.backend.abi.PUSH_SUSPENDED_FRAME
import io.github.charlietap.chasm.compiler.backend.abi.RESUMING_FIELD
import io.github.charlietap.chasm.compiler.backend.abi.SUSPENDED_FRAME_CALLEE_FIELD
import io.github.charlietap.chasm.compiler.backend.abi.SUSPENDED_FRAME_OBJECTS_FIELD
import io.github.charlietap.chasm.compiler.backend.abi.SUSPENDED_FRAME_SITE_FIELD
import io.github.charlietap.chasm.compiler.backend.abi.SUSPENDED_FRAME_TYPE
import io.github.charlietap.chasm.compiler.backend.abi.SUSPENDED_FRAME_WORDS_FIELD
import io.github.charlietap.chasm.compiler.backend.abi.SUSPENDING_FIELD
import io.github.charlietap.chasm.compiler.backend.abi.UNREACHABLE
import io.github.charlietap.chasm.compiler.backend.abi.takesArgumentArray
import io.github.charlietap.chasm.compiler.backend.abi.takesReferenceArray
import io.github.charlietap.chasm.compiler.backend.abi.valueDescriptor
import io.github.charlietap.chasm.compiler.backend.analysis.SlotKinds
import io.github.charlietap.chasm.compiler.backend.analysis.form.Operand
import io.github.charlietap.chasm.type.FunctionType
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

internal class SuspendSite(
    val id: Int,
    val resumeLabel: Label,
    val emitResume: () -> Unit,
)

internal fun LowerContext.lowerResumeEntryCheck() {
    if (!isSuspendable) return
    val dispatch = emit.label()
    resumeDispatchLabel = dispatch
    pushContext()
    emit.getField(CONTEXT_TYPE, RESUMING_FIELD, "Z")
    emit.jump(Opcodes.IFNE, dispatch)
}

internal fun LowerContext.suspendCheck(
    hasCallee: Boolean,
    afterInvoke: Label,
    emitResumeCall: () -> Unit,
) {
    val siteId = suspendSites.size
    val suspendLabel = emit.label()
    val resumeLabel = emit.label()
    suspendSites += SuspendSite(siteId, resumeLabel) {
        emit.place(resumeLabel)
        emitResumeCall()
        emit.jump(Opcodes.GOTO, afterInvoke)
    }

    pushContext()
    emit.getField(CONTEXT_TYPE, SUSPENDING_FIELD, "Z")
    emit.jump(Opcodes.IFNE, suspendLabel)

    outOfLine += {
        emit.place(suspendLabel)
        emitSaveFrameAndReturn(siteId, hasCallee)
    }
}

internal fun LowerContext.pushDummyCallArguments(
    type: FunctionType,
) {
    if (takesArgumentArray(type)) {
        emit.insn(Opcodes.ACONST_NULL)
        if (takesReferenceArray(type, OBJECT_TYPE)) {
            emit.insn(Opcodes.ACONST_NULL)
        }
        return
    }
    type.params.types.forEach { parameter ->
        when (valueDescriptor(parameter, OBJECT_TYPE)) {
            "I" -> emit.int(0)
            "F" -> emit.float(0)
            "D" -> emit.double(0L)
            OBJECT_TYPE -> emit.insn(Opcodes.ACONST_NULL)
            else -> emit.long(0L)
        }
    }
}

private fun LowerContext.emitSaveFrameAndReturn(
    siteId: Int,
    hasCallee: Boolean,
) {
    emit.int(frame.frameSlots)
    emit.newLongArray()
    for (slot in 0 until frame.frameSlots) {
        if (!locals.holdsNumber(slot)) continue
        emit.insn(Opcodes.DUP)
        emit.int(slot)
        pushLong(Operand.Slot(slot))
        emit.insn(Opcodes.LASTORE)
    }
    emit.store(Opcodes.ASTORE, locals.scratchValues)

    if (locals.holdsObjects) {
        emit.int(frame.frameSlots)
        emit.newObjectArray()
        for (slot in 0 until frame.frameSlots) {
            if (!locals.holdsObject(slot)) continue
            emit.insn(Opcodes.DUP)
            emit.int(slot)
            emit.load(Opcodes.ALOAD, locals.objectSlot(slot))
            emit.insn(Opcodes.AASTORE)
        }
        emit.store(Opcodes.ASTORE, locals.scratchReferences)
    } else {
        emit.insn(Opcodes.ACONST_NULL)
        emit.store(Opcodes.ASTORE, locals.scratchReferences)
    }

    pushContext()
    emit.int(siteId)
    emit.load(Opcodes.ALOAD, locals.scratchValues)
    emit.load(Opcodes.ALOAD, locals.scratchReferences)
    if (hasCallee) {
        emit.load(Opcodes.ALOAD, locals.suspendCallee)
    } else {
        emit.insn(Opcodes.ACONST_NULL)
    }
    emit.invokeStatic(PUSH_SUSPENDED_FRAME)
    returnNothing()
}

internal fun LowerContext.lowerSuspensionDispatch() {
    val dispatch = resumeDispatchLabel ?: return
    emit.place(dispatch)
    if (suspendSites.isEmpty()) {
        trap(UNREACHABLE)
        return
    }

    pushContext()
    emit.invokeStatic(POP_SUSPENDED_FRAME)
    emit.insn(Opcodes.DUP)
    emit.getField(SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_SITE_FIELD, "I")
    emit.store(Opcodes.ISTORE, locals.exitPosition)

    emit.insn(Opcodes.DUP)
    emit.getField(SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_CALLEE_FIELD, OBJECT_TYPE)
    emit.store(Opcodes.ASTORE, locals.suspendCallee)

    emit.insn(Opcodes.DUP)
    emit.getField(SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_WORDS_FIELD, "[J")
    emit.store(Opcodes.ASTORE, locals.scratchValues)

    if (locals.holdsObjects) {
        emit.getField(SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_OBJECTS_FIELD, "[Ljava/lang/Object;")
        emit.store(Opcodes.ASTORE, locals.scratchReferences)
    } else {
        emit.insn(Opcodes.POP)
    }

    for (slot in 0 until frame.frameSlots) {
        if (locals.holdsNumber(slot)) {
            restoreNumberSlot(slot)
        }
        if (locals.holdsObject(slot)) {
            emit.load(Opcodes.ALOAD, locals.scratchReferences)
            emit.int(slot)
            emit.insn(Opcodes.AALOAD)
            emit.store(Opcodes.ASTORE, locals.objectSlot(slot))
        }
    }

    refreshMemory()

    val unknown = emit.label()
    emit.load(Opcodes.ILOAD, locals.exitPosition)
    emit.tableSwitch(0, unknown, Array(suspendSites.size) { index -> suspendSites[index].resumeLabel })
    emit.place(unknown)
    trap(UNREACHABLE)

    suspendSites.forEach { site -> site.emitResume() }
}

private fun LowerContext.restoreNumberSlot(slot: Int) {
    emit.load(Opcodes.ALOAD, locals.scratchValues)
    emit.int(slot)
    emit.insn(Opcodes.LALOAD)
    val local = locals.slot(slot)
    when (locals.kind(slot)) {
        SlotKinds.INT -> {
            emit.insn(Opcodes.L2I)
            emit.store(Opcodes.ISTORE, local)
        }
        SlotKinds.FLOAT -> {
            emit.insn(Opcodes.L2I)
            emit.invokeStatic(INT_BITS_TO_FLOAT)
            emit.store(Opcodes.FSTORE, local)
        }
        SlotKinds.DOUBLE -> {
            emit.invokeStatic(LONG_BITS_TO_DOUBLE)
            emit.store(Opcodes.DSTORE, local)
        }
        else -> emit.store(Opcodes.LSTORE, local)
    }
    refreshShadows(slot)
}
