package io.github.charlietap.chasm.runtime.execution

import kotlin.jvm.JvmField

class SuspendedFrame(
    @JvmField val site: Int,
    @JvmField val words: LongArray,
    @JvmField val objects: Array<Any?>?,
    @JvmField val callee: Any?,
)

interface PendingSuspension {

    suspend fun resume()
}
