package io.github.charlietap.chasm.compiler.backend.dex.lower

import io.github.charlietap.chasm.compiler.backend.abi.CONTEXT_TYPE
import io.github.charlietap.chasm.compiler.backend.abi.FRAME
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
import io.github.charlietap.chasm.compiler.backend.abi.descriptorWords
import io.github.charlietap.chasm.compiler.backend.abi.takesArgumentArray
import io.github.charlietap.chasm.compiler.backend.abi.takesReferenceArray
import io.github.charlietap.chasm.compiler.backend.abi.valueDescriptor
import io.github.charlietap.chasm.compiler.backend.analysis.SlotKindFlow
import io.github.charlietap.chasm.type.FunctionType
import io.github.charlietap.dexter.ArrayKind
import io.github.charlietap.dexter.Cond
import io.github.charlietap.dexter.Label

private const val OBJECT_ARRAY = "[Ljava/lang/Object;"

internal class SuspendSite(
    val id: Int,
    val resumeLabel: Label,
    val liveNumbers: List<Pair<Int, Byte>>,
    val liveObjects: List<Int>,
    val emitResume: () -> Unit,
)

internal fun LowerContext.lowerResumeEntryCheck() {
    if (!isSuspendable) return
    val dispatch = emit.freshLabel()
    resumeDispatchLabel = dispatch
    emit.getField(Registers.I0, Registers.CONTEXT, CONTEXT_TYPE, RESUMING_FIELD, "Z")
    emit.branchZero(Cond.Ne, Registers.I0, dispatch)
}

internal fun LowerContext.suspendCheck(
    hasCallee: Boolean,
    afterInvoke: Label,
    emitResumeCall: () -> Unit,
) {
    val siteId = suspendSites.size
    val suspendLabel = emit.freshLabel()
    val resumeLabel = emit.freshLabel()
    val (liveNumbers, liveObjects) = if (registers.records) {
        emptyList<Pair<Int, Byte>>() to emptyList()
    } else {
        collectLiveHomes(position)
    }

    suspendSites += SuspendSite(siteId, resumeLabel, liveNumbers, liveObjects) {
        emit.place(resumeLabel)
        if (chunk == null) {
            restoreWholeSiteSlots(liveNumbers, liveObjects)
        } else {
            restoreChunkSiteSlots(liveNumbers, liveObjects)
        }
        openWindow()
        emitResumeCall()
        emit.gotoLabel(afterInvoke)
    }

    emit.getField(Registers.I0, Registers.CONTEXT, CONTEXT_TYPE, SUSPENDING_FIELD, "Z")
    emit.branchZero(Cond.Ne, Registers.I0, suspendLabel)

    outOfLine += {
        emit.place(suspendLabel)
        if (chunk == null) {
            emitWholeSaveFrameAndReturn(siteId, hasCallee, liveNumbers, liveObjects)
        } else {
            emitChunkSaveFrameAndReturn(siteId, hasCallee, liveNumbers, liveObjects)
        }
    }
}

internal fun LowerContext.loadDummyWindow(
    type: FunctionType,
): Int {
    val window = registers.window
    if (takesArgumentArray(type)) {
        emit.const(Registers.I0, 0)
        emit.moveObject(window + 3, Registers.I0)
        if (!takesReferenceArray(type, module.referenceDescriptor)) return 1
        emit.moveObject(window + 4, Registers.I0)
        return 2
    }
    var words = 0
    type.params.types.forEach { parameter ->
        val descriptor = valueDescriptor(parameter, module.referenceDescriptor)
        when (descriptor) {
            "I", "F" -> {
                emit.const(Registers.I0, 0)
                emit.move(window + 3 + words, Registers.I0)
            }
            OBJECT_TYPE -> {
                emit.const(Registers.I0, 0)
                emit.moveObject(window + 3 + words, Registers.I0)
            }
            else -> {
                emit.constWide(Registers.W0, 0L)
                emit.moveWide(window + 3 + words, Registers.W0)
            }
        }
        words += descriptorWords(descriptor)
    }
    return words
}

private fun LowerContext.collectLiveHomes(
    callPosition: Int,
): Pair<List<Pair<Int, Byte>>, List<Int>> {
    val liveSlots = checkNotNull(suspendLiveness).liveAfterCall(callPosition)
    val numbers = ArrayList<Pair<Int, Byte>>()
    val objects = ArrayList<Int>()
    for (slot in liveSlots) {
        if (!registers.holds(slot)) continue
        val state = kinds.state(slot)
        when {
            SlotKindFlow.definite(state) && registers.holds(slot, state) -> {
                if (state == SlotKindFlow.OBJECT) {
                    objects += slot
                } else {
                    numbers += slot to state
                }
            }
            state == SlotKindFlow.ANY_NARROW || state == SlotKindFlow.ANY_WIDE -> {
                val kind = anyHome(registers.heldMask(slot), SlotKindFlow.LONG)
                if (kind != SlotKindFlow.NONE) {
                    numbers += slot to kind
                }
            }
            else -> {
                forEachHome(slot) { kind ->
                    if (kind == SlotKindFlow.OBJECT) {
                        objects += slot
                    } else {
                        numbers += slot to kind
                    }
                }
            }
        }
    }
    return numbers to objects
}

private fun LowerContext.emitWholeSaveFrameAndReturn(
    siteId: Int,
    hasCallee: Boolean,
    liveNumbers: List<Pair<Int, Byte>>,
    liveObjects: List<Int>,
) {
    emit.const(Registers.I0, liveNumbers.size)
    emit.newArray(Registers.SECOND_OBJECT, Registers.I0, "[J")
    liveNumbers.forEachIndexed { index, (slot, kind) ->
        loadEncoded(Registers.W0, slot, kind)
        emit.const(Registers.I1, index)
        emit.arrayPut(ArrayKind.Wide, Registers.W0, Registers.SECOND_OBJECT, Registers.I1)
    }

    if (liveObjects.isNotEmpty()) {
        emit.const(Registers.I0, liveObjects.size)
        emit.newArray(Registers.THIRD_OBJECT, Registers.I0, OBJECT_ARRAY)
        liveObjects.forEachIndexed { index, slot ->
            emit.moveObject(Registers.OBJECT, registers.home(slot, SlotKindFlow.OBJECT))
            emit.const(Registers.I1, index)
            emit.arrayPut(ArrayKind.Object, Registers.OBJECT, Registers.THIRD_OBJECT, Registers.I1)
        }
    } else {
        emit.const(Registers.THIRD_OBJECT, 0)
    }

    if (hasCallee) {
        emit.moveObject(Registers.OBJECT, registers.suspendCallee)
    } else {
        emit.const(Registers.OBJECT, 0)
    }
    emit.const(Registers.I0, siteId)
    emit.invoke(PUSH_SUSPENDED_FRAME, Registers.CONTEXT, Registers.I0, Registers.SECOND_OBJECT, Registers.THIRD_OBJECT, Registers.OBJECT)
    returnAnything()
}

private fun LowerContext.emitChunkSaveFrameAndReturn(
    siteId: Int,
    hasCallee: Boolean,
    liveNumbers: List<Pair<Int, Byte>>,
    liveObjects: List<Int>,
) {
    if (!registers.records) {
        if (liveNumbers.isNotEmpty()) {
            emit.invoke(FRAME, Registers.CONTEXT)
            emit.moveResultObject(Registers.OBJECT)
            for ((slot, kind) in liveNumbers) {
                loadEncoded(Registers.W0, slot, kind)
                frameIndex(Registers.I2, slot)
                emit.arrayPut(ArrayKind.Wide, Registers.W0, Registers.OBJECT, Registers.I2)
            }
        }
        for (slot in liveObjects) {
            emit.moveObject(Registers.THIRD_OBJECT, registers.home(slot, SlotKindFlow.OBJECT))
            spillReference(slot, Registers.THIRD_OBJECT)
        }
    }

    emit.const(Registers.I0, 0)
    emit.newArray(Registers.SECOND_OBJECT, Registers.I0, "[J")
    emit.const(Registers.THIRD_OBJECT, 0)
    if (hasCallee) {
        emit.moveObject(Registers.OBJECT, registers.suspendCallee)
    } else {
        emit.const(Registers.OBJECT, 0)
    }
    emit.const(Registers.I0, siteId)
    emit.invoke(PUSH_SUSPENDED_FRAME, Registers.CONTEXT, Registers.I0, Registers.SECOND_OBJECT, Registers.THIRD_OBJECT, Registers.OBJECT)
    emit.const(Registers.I0, Chunk.SUSPEND)
    emit.returnValue(Registers.I0)
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
    if (facts.window.callBufferWords > 0) {
        emit.const(Registers.I0, 0)
        emit.moveObject(registers.callBuffer, Registers.I0)
    }

    emit.invoke(POP_SUSPENDED_FRAME, Registers.CONTEXT)
    emit.moveResultObject(Registers.OBJECT)

    emit.getField(Registers.I0, Registers.OBJECT, SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_SITE_FIELD, "I")
    emit.move(registers.window, Registers.I0)

    emit.getField(Registers.SECOND_OBJECT, Registers.OBJECT, SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_CALLEE_FIELD, OBJECT_TYPE)
    emit.moveObject(registers.suspendCallee, Registers.SECOND_OBJECT)

    if (suspendSites.any { site -> site.liveObjects.isNotEmpty() }) {
        emit.getField(Registers.THIRD_OBJECT, Registers.OBJECT, SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_OBJECTS_FIELD, OBJECT_ARRAY)
        emit.moveObject(registers.window + 1, Registers.THIRD_OBJECT)
    }

    if (suspendSites.any { site -> site.liveNumbers.isNotEmpty() }) {
        emit.getField(Registers.SECOND_OBJECT, Registers.OBJECT, SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_WORDS_FIELD, "[J")
        emit.moveObject(registers.window + 2, Registers.SECOND_OBJECT)
    }

    refreshMemory()
    emit.move(Registers.I3, registers.window)
    emit.packedSwitch(Registers.I3, 0, suspendSites.map { site -> site.resumeLabel })
    trap(UNREACHABLE)

    suspendSites.forEach { site -> site.emitResume() }
}

private fun LowerContext.lowerChunkSuspensionDispatch() {
    emit.invoke(POP_SUSPENDED_FRAME, Registers.CONTEXT)
    emit.moveResultObject(Registers.OBJECT)

    emit.getField(Registers.I0, Registers.OBJECT, SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_SITE_FIELD, "I")
    emit.move(registers.window, Registers.I0)

    emit.getField(Registers.SECOND_OBJECT, Registers.OBJECT, SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_CALLEE_FIELD, OBJECT_TYPE)
    emit.moveObject(registers.suspendCallee, Registers.SECOND_OBJECT)

    if (chunk?.usesMemory == true) refreshMemory()
    emit.move(Registers.I3, registers.window)
    emit.packedSwitch(Registers.I3, 0, suspendSites.map { site -> site.resumeLabel })
    trap(UNREACHABLE)

    suspendSites.forEach { site -> site.emitResume() }
}

private fun LowerContext.restoreWholeSiteSlots(
    liveNumbers: List<Pair<Int, Byte>>,
    liveObjects: List<Int>,
) {
    liveNumbers.forEachIndexed { index, (slot, kind) ->
        emit.moveObject(Registers.SECOND_OBJECT, registers.window + 2)
        emit.const(Registers.I1, index)
        emit.arrayGet(ArrayKind.Wide, Registers.W0, Registers.SECOND_OBJECT, Registers.I1)
        decodeInto(slot, kind, Registers.W0)
    }
    if (liveObjects.isNotEmpty()) {
        emit.moveObject(Registers.THIRD_OBJECT, registers.window + 1)
        liveObjects.forEachIndexed { index, slot ->
            emit.const(Registers.I1, index)
            emit.arrayGet(ArrayKind.Object, Registers.OBJECT, Registers.THIRD_OBJECT, Registers.I1)
            emit.moveObject(registers.home(slot, SlotKindFlow.OBJECT), Registers.OBJECT)
        }
    }
}

private fun LowerContext.restoreChunkSiteSlots(
    liveNumbers: List<Pair<Int, Byte>>,
    liveObjects: List<Int>,
) {
    if (registers.records) return
    if (liveNumbers.isNotEmpty()) {
        emit.invoke(FRAME, Registers.CONTEXT)
        emit.moveResultObject(Registers.OBJECT)
        for ((slot, kind) in liveNumbers) {
            frameIndex(Registers.I2, slot)
            emit.arrayGet(ArrayKind.Wide, Registers.W0, Registers.OBJECT, Registers.I2)
            decodeInto(slot, kind, Registers.W0)
        }
    }
    for (slot in liveObjects) {
        emit.moveObject(Registers.SECOND_OBJECT, registers.objectFrame)
        emit.const(Registers.I1, slot)
        emit.arrayGet(ArrayKind.Object, Registers.THIRD_OBJECT, Registers.SECOND_OBJECT, Registers.I1)
        emit.moveObject(registers.home(slot, SlotKindFlow.OBJECT), Registers.THIRD_OBJECT)
    }
}
