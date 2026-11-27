package io.github.charlietap.chasm.compiler.backend.jvm.lower

import io.github.charlietap.chasm.compiler.backend.abi.CONTEXT_TYPE
import io.github.charlietap.chasm.compiler.backend.abi.DOUBLE_TO_RAW_LONG_BITS
import io.github.charlietap.chasm.compiler.backend.abi.FLOAT_TO_RAW_INT_BITS
import io.github.charlietap.chasm.compiler.backend.abi.FRAME
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
    val liveNumbers: IntArray,
    val liveObjects: IntArray,
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
    val liveSlots = if (locals.isRecording) {
        IntArray(0)
    } else {
        checkNotNull(facts.suspendLiveness).liveAfterCall(position)
    }
    val liveNumbers = liveSlots.filter { slot -> locals.holdsNumber(slot) }.toIntArray()
    val liveObjects = liveSlots.filter { slot -> locals.holdsObject(slot) }.toIntArray()

    suspendSites += SuspendSite(siteId, resumeLabel, liveNumbers, liveObjects) {
        emit.place(resumeLabel)
        if (chunk == null) {
            restoreWholeSiteSlots(liveNumbers, liveObjects)
        } else {
            restoreChunkSiteSlots(liveNumbers, liveObjects)
        }
        emitResumeCall()
        emit.jump(Opcodes.GOTO, afterInvoke)
    }

    pushContext()
    emit.getField(CONTEXT_TYPE, SUSPENDING_FIELD, "Z")
    emit.jump(Opcodes.IFNE, suspendLabel)

    outOfLine += {
        emit.place(suspendLabel)
        if (chunk == null) {
            emitWholeSaveFrameAndReturn(siteId, hasCallee, liveNumbers, liveObjects)
        } else {
            emitChunkSaveFrameAndReturn(siteId, hasCallee, liveNumbers, liveObjects)
        }
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

private fun LowerContext.emitWholeSaveFrameAndReturn(
    siteId: Int,
    hasCallee: Boolean,
    liveNumbers: IntArray,
    liveObjects: IntArray,
) {
    emit.int(liveNumbers.size)
    emit.newLongArray()
    liveNumbers.forEachIndexed { index, slot ->
        emit.insn(Opcodes.DUP)
        emit.int(index)
        pushLong(Operand.Slot(slot))
        emit.insn(Opcodes.LASTORE)
    }
    emit.store(Opcodes.ASTORE, locals.scratchValues)

    if (liveObjects.isNotEmpty()) {
        emit.int(liveObjects.size)
        emit.newObjectArray()
        liveObjects.forEachIndexed { index, slot ->
            emit.insn(Opcodes.DUP)
            emit.int(index)
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

private fun LowerContext.emitChunkSaveFrameAndReturn(
    siteId: Int,
    hasCallee: Boolean,
    liveNumbers: IntArray,
    liveObjects: IntArray,
) {
    if (!locals.isRecording) {
        if (liveNumbers.isNotEmpty()) {
            pushContext()
            emit.invokeStatic(FRAME)
            emit.store(Opcodes.ASTORE, locals.scratchValues)
            for (slot in liveNumbers) {
                emit.load(Opcodes.ALOAD, locals.scratchValues)
                emit.load(Opcodes.ILOAD, locals.frameBase)
                emit.int(slot)
                emit.insn(Opcodes.IADD)
                val local = locals.slot(slot)
                when (locals.kind(slot)) {
                    SlotKinds.INT -> {
                        emit.load(Opcodes.ILOAD, local)
                        emit.insn(Opcodes.I2L)
                    }
                    SlotKinds.FLOAT -> {
                        emit.load(Opcodes.FLOAD, local)
                        emit.invokeStatic(FLOAT_TO_RAW_INT_BITS)
                        emit.insn(Opcodes.I2L)
                    }
                    SlotKinds.DOUBLE -> {
                        emit.load(Opcodes.DLOAD, local)
                        emit.invokeStatic(DOUBLE_TO_RAW_LONG_BITS)
                    }
                    else -> emit.load(Opcodes.LLOAD, local)
                }
                emit.insn(Opcodes.LASTORE)
            }
        }
        for (slot in liveObjects) {
            emit.load(Opcodes.ALOAD, locals.objectFrame)
            emit.int(slot)
            emit.load(Opcodes.ALOAD, locals.objectSlot(slot))
            emit.insn(Opcodes.AASTORE)
        }
    }

    pushContext()
    emit.int(siteId)
    emit.int(0)
    emit.newLongArray()
    emit.insn(Opcodes.ACONST_NULL)
    if (hasCallee) {
        emit.load(Opcodes.ALOAD, locals.suspendCallee)
    } else {
        emit.insn(Opcodes.ACONST_NULL)
    }
    emit.invokeStatic(PUSH_SUSPENDED_FRAME)
    emit.int(Chunk.SUSPEND)
    emit.insn(Opcodes.IRETURN)
}

internal fun LowerContext.lowerSuspensionDispatch() {
    val dispatch = resumeDispatchLabel ?: return
    emit.place(dispatch)
    if (suspendSites.isEmpty()) {
        trap(UNREACHABLE)
        return
    }

    if (chunk == null) {
        lowerWholeSuspensionDispatch()
    } else {
        lowerChunkSuspensionDispatch()
    }
}

private fun LowerContext.lowerWholeSuspensionDispatch() {
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

    refreshMemory()

    val unknown = emit.label()
    emit.load(Opcodes.ILOAD, locals.exitPosition)
    emit.tableSwitch(0, unknown, Array(suspendSites.size) { index -> suspendSites[index].resumeLabel })
    emit.place(unknown)
    trap(UNREACHABLE)

    suspendSites.forEach { site -> site.emitResume() }
}

private fun LowerContext.lowerChunkSuspensionDispatch() {
    pushContext()
    emit.invokeStatic(POP_SUSPENDED_FRAME)
    emit.insn(Opcodes.DUP)
    emit.getField(SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_SITE_FIELD, "I")
    emit.store(Opcodes.ISTORE, locals.exitPosition)

    emit.getField(SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_CALLEE_FIELD, OBJECT_TYPE)
    emit.store(Opcodes.ASTORE, locals.suspendCallee)

    if (!locals.isRecording) {
        if (suspendSites.any { site -> site.liveNumbers.isNotEmpty() }) {
            pushContext()
            emit.invokeStatic(FRAME)
            emit.store(Opcodes.ASTORE, locals.frameArray)
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

private fun LowerContext.restoreWholeSiteSlots(
    liveNumbers: IntArray,
    liveObjects: IntArray,
) {
    liveNumbers.forEachIndexed { index, slot ->
        emit.load(Opcodes.ALOAD, locals.scratchValues)
        emit.int(index)
        emit.insn(Opcodes.LALOAD)
        storeRestoredNumber(slot)
    }
    liveObjects.forEachIndexed { index, slot ->
        emit.load(Opcodes.ALOAD, locals.scratchReferences)
        emit.int(index)
        emit.insn(Opcodes.AALOAD)
        emit.store(Opcodes.ASTORE, locals.objectSlot(slot))
    }
}

private fun LowerContext.restoreChunkSiteSlots(
    liveNumbers: IntArray,
    liveObjects: IntArray,
) {
    if (locals.isRecording) return
    for (slot in liveNumbers) {
        emit.load(Opcodes.ALOAD, locals.frameArray)
        emit.load(Opcodes.ILOAD, locals.frameBase)
        emit.int(slot)
        emit.insn(Opcodes.IADD)
        emit.insn(Opcodes.LALOAD)
        storeRestoredNumber(slot)
    }
    for (slot in liveObjects) {
        emit.load(Opcodes.ALOAD, locals.objectFrame)
        emit.int(slot)
        emit.insn(Opcodes.AALOAD)
        emit.store(Opcodes.ASTORE, locals.objectSlot(slot))
    }
}

private fun LowerContext.storeRestoredNumber(slot: Int) {
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
