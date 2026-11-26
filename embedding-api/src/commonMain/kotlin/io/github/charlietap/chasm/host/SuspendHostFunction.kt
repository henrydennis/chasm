package io.github.charlietap.chasm.host

import io.github.charlietap.chasm.ExperimentalChasmApi

@ExperimentalChasmApi
fun interface SuspendHostFunction {

    context(
        _: HostStack,
        _: HostReferenceStack,
        _: HostModuleInstance,
        _: HostResources,
    )
    suspend fun invoke(
        parameters: HostParameters,
        results: HostResults,
    )
}
