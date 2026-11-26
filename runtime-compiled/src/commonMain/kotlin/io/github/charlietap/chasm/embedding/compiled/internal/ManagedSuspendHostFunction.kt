@file:OptIn(ExperimentalChasmApi::class)

package io.github.charlietap.chasm.embedding.compiled.internal

import io.github.charlietap.chasm.ExperimentalChasmApi
import io.github.charlietap.chasm.host.HostResources
import io.github.charlietap.chasm.host.SuspendHostFunction
import io.github.charlietap.chasm.runtime.compiled.gc.ObjectLaneSuspendHostFunction
import io.github.charlietap.chasm.runtime.host.RawHostModuleInstance
import io.github.charlietap.chasm.runtime.host.RawHostResources
import io.github.charlietap.chasm.runtime.instance.ModuleInstance
import io.github.charlietap.chasm.type.FunctionType

internal class ManagedSuspendHostFunction(
    type: FunctionType,
    private val function: SuspendHostFunction,
    private val instances: ManagedInstances,
    private val hostResources: HostResources,
) : ObjectLaneSuspendHostFunction(type) {

    override suspend fun callSuspending(
        stack: LongArray,
        references: Array<Any?>,
        parameters: Int,
        results: Int,
        caller: RawHostModuleInstance,
        resources: RawHostResources,
    ) {
        context(stack, references, instances.contextOf(caller as ModuleInstance), hostResources) {
            function.invoke(parameters, results)
        }
    }
}
