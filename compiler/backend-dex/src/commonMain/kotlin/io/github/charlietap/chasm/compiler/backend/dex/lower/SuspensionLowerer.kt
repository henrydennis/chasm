package io.github.charlietap.chasm.compiler.backend.dex.lower

import io.github.charlietap.chasm.compiler.backend.abi.CONTEXT_TYPE
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
    suspendSites += SuspendSite(siteId, resumeLabel) {
        emit.place(resumeLabel)
        emitResumeCall()
        emit.gotoLabel(afterInvoke)
    }

    emit.getField(Registers.I0, Registers.CONTEXT, CONTEXT_TYPE, SUSPENDING_FIELD, "Z")
    emit.branchZero(Cond.Ne, Registers.I0, suspendLabel)

    outOfLine += {
        emit.place(suspendLabel)
        emitSaveFrameAndReturn(siteId, hasCallee)
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

private fun LowerContext.collectHomes(): Pair<List<Pair<Int, Byte>>, List<Int>> {
    val numbers = ArrayList<Pair<Int, Byte>>()
    val objects = ArrayList<Int>()
    for (slot in 0 until frameSlots) {
        forEachHome(slot) { kind ->
            if (kind == SlotKindFlow.OBJECT) {
                objects += slot
            } else {
                numbers += slot to kind
            }
        }
    }
    return numbers to objects
}

private fun LowerContext.emitSaveFrameAndReturn(
    siteId: Int,
    hasCallee: Boolean,
) {
    val (numbers, objects) = collectHomes()

    emit.const(Registers.I0, numbers.size)
    emit.newArray(Registers.SECOND_OBJECT, Registers.I0, "[J")
    numbers.forEachIndexed { index, (slot, kind) ->
        loadEncoded(Registers.W0, slot, kind)
        emit.const(Registers.I1, index)
        emit.arrayPut(ArrayKind.Wide, Registers.W0, Registers.SECOND_OBJECT, Registers.I1)
    }

    if (objects.isNotEmpty()) {
        emit.const(Registers.I0, objects.size)
        emit.newArray(Registers.THIRD_OBJECT, Registers.I0, OBJECT_ARRAY)
        objects.forEachIndexed { index, slot ->
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

internal fun LowerContext.lowerSuspensionDispatch() {
    val dispatch = resumeDispatchLabel ?: return
    emit.place(dispatch)
    if (suspendSites.isEmpty()) {
        trap(UNREACHABLE)
        return
    }

    val (numbers, objects) = collectHomes()

    emit.invoke(POP_SUSPENDED_FRAME, Registers.CONTEXT)
    emit.moveResultObject(Registers.OBJECT)

    emit.getField(Registers.I0, Registers.OBJECT, SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_SITE_FIELD, "I")
    emit.move(registers.window, Registers.I0)

    emit.getField(Registers.SECOND_OBJECT, Registers.OBJECT, SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_CALLEE_FIELD, OBJECT_TYPE)
    emit.moveObject(registers.suspendCallee, Registers.SECOND_OBJECT)

    if (objects.isNotEmpty()) {
        emit.getField(Registers.THIRD_OBJECT, Registers.OBJECT, SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_OBJECTS_FIELD, OBJECT_ARRAY)
        emit.moveObject(registers.window + 1, Registers.THIRD_OBJECT)
    }

    if (numbers.isNotEmpty()) {
        emit.getField(Registers.SECOND_OBJECT, Registers.OBJECT, SUSPENDED_FRAME_TYPE, SUSPENDED_FRAME_WORDS_FIELD, "[J")
        emit.moveObject(registers.window + 2, Registers.SECOND_OBJECT)
    }

    numbers.forEachIndexed { index, (slot, kind) ->
        emit.moveObject(Registers.SECOND_OBJECT, registers.window + 2)
        emit.const(Registers.I1, index)
        emit.arrayGet(ArrayKind.Wide, Registers.W0, Registers.SECOND_OBJECT, Registers.I1)
        decodeInto(slot, kind, Registers.W0)
    }

    if (objects.isNotEmpty()) {
        emit.moveObject(Registers.THIRD_OBJECT, registers.window + 1)
        objects.forEachIndexed { index, slot ->
            emit.const(Registers.I1, index)
            emit.arrayGet(ArrayKind.Object, Registers.OBJECT, Registers.THIRD_OBJECT, Registers.I1)
            emit.moveObject(registers.home(slot, SlotKindFlow.OBJECT), Registers.OBJECT)
        }
    }

    refreshMemory()
    emit.move(Registers.I3, registers.window)
    openWindow()
    emit.packedSwitch(Registers.I3, 0, suspendSites.map { site -> site.resumeLabel })
    trap(UNREACHABLE)

    suspendSites.forEach { site -> site.emitResume() }
}
