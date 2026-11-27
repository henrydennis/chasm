package io.github.charlietap.chasm.runtime.instance

import io.github.charlietap.chasm.gc.GcRootMarker
import io.github.charlietap.chasm.type.FunctionType

class PromptHandler(
    val tagAddress: Int,
    val targetIp: Int,
    val targetSlots: IntArray?,
    val targetStackSlotCount: Int,
) {
    val isSwitch: Boolean
        get() = targetSlots == null
}

class PromptRecord(
    val callerFp: Int,
    val callerSp: Int,
    val stackBase: Int,
    val returnIp: Int,
    val resumeCallSiteIp: Int,
    val resultDestinationSlot: Int?,
    val resultCount: Int,
    val handlers: Array<PromptHandler>,
) {
    fun shifted(delta: Int): PromptRecord = PromptRecord(
        callerFp = callerFp + delta,
        callerSp = callerSp + delta,
        stackBase = stackBase + delta,
        returnIp = returnIp,
        resumeCallSiteIp = resumeCallSiteIp,
        resultDestinationSlot = resultDestinationSlot,
        resultCount = resultCount,
        handlers = handlers,
    )
}

class ContinuationInstance(
    val functionType: FunctionType,
    var consumed: Boolean = false,
    var boundArguments: LongArray = EMPTY_LONGS,
    var initialFunction: FunctionInstance? = null,
    var initialCallerInstance: ModuleInstance? = null,
    var stack: LongArray? = null,
    val savedFpOffset: Int = 0,
    val resumeIp: Int = -1,
    val resumeResultDestinationSlot: Int? = null,
    val resumeResultCount: Int = 0,
    var savedPrompts: List<PromptRecord>? = null,
) {
    fun consume() {
        consumed = true
        boundArguments = EMPTY_LONGS
        initialFunction = null
        initialCallerInstance = null
        stack = null
        savedPrompts = null
    }

    internal fun visitGcRoots(rootMarker: GcRootMarker) {
        val args = boundArguments
        var index = 0
        while (index < args.size) {
            rootMarker.markRoot(args[index])
            index++
        }
        val captured = stack ?: return
        index = 0
        while (index < captured.size) {
            rootMarker.markRoot(captured[index])
            index++
        }
    }

    companion object {
        val EMPTY_LONGS: LongArray = LongArray(0)
    }
}
