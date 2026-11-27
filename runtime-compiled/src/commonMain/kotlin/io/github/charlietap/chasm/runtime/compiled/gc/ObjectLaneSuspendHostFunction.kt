package io.github.charlietap.chasm.runtime.compiled.gc

import io.github.charlietap.chasm.InternalChasmApi
import io.github.charlietap.chasm.runtime.host.RawHostModuleInstance
import io.github.charlietap.chasm.runtime.host.RawHostResources
import io.github.charlietap.chasm.type.FunctionType

@InternalChasmApi
abstract class ObjectLaneSuspendHostFunction(
    type: FunctionType,
) : ObjectLaneHostFunction(type) {

    internal val slotCount: Int = maxOf(type.params.types.size, type.results.types.size, 1)

    abstract suspend fun callSuspending(
        stack: LongArray,
        references: Array<Any?>,
        parameters: Int,
        results: Int,
        caller: RawHostModuleInstance,
        resources: RawHostResources,
    )

    final override fun call(
        stack: LongArray,
        references: Array<Any?>,
        parameters: Int,
        results: Int,
        caller: RawHostModuleInstance,
        resources: RawHostResources,
    ) {
        throw UnsupportedOperationException("suspending host functions must be called via invokeSuspending")
    }
}
