package io.github.charlietap.chasm.embedding.internal

import io.github.charlietap.chasm.ExperimentalChasmApi
import io.github.charlietap.chasm.host.HostFunction
import io.github.charlietap.chasm.host.HostFunctionException
import io.github.charlietap.chasm.host.HostParameters
import io.github.charlietap.chasm.host.HostResults
import io.github.charlietap.chasm.host.HostStack
import io.github.charlietap.chasm.host.SuspendHostFunction
import io.github.charlietap.chasm.host.UnsafeHostApi
import io.github.charlietap.chasm.runtime.execution.ExecutionContext
import io.github.charlietap.chasm.runtime.execution.PendingSuspension
import io.github.charlietap.chasm.runtime.host.RawHostFunction
import io.github.charlietap.chasm.runtime.host.RawHostModuleInstance
import io.github.charlietap.chasm.runtime.host.RawHostResources
import io.github.charlietap.chasm.type.FunctionType

internal interface HostCallAdapters {

    fun adapt(
        type: FunctionType,
        function: HostFunction,
    ): RawHostFunction

    @OptIn(ExperimentalChasmApi::class)
    fun adaptSuspending(
        type: FunctionType,
        function: SuspendHostFunction,
    ): RawHostFunction
}

internal class InterpreterHostCallAdapters(
    private val codec: ReferenceCodec,
    private val lanes: ReferenceLanes,
    private val resources: InterpreterHostResources,
) : HostCallAdapters {

    override fun adapt(
        type: FunctionType,
        function: HostFunction,
    ): RawHostFunction {

        val parameterSlots = codec.slots(type.params.types)
        val resultSlots = codec.slots(type.results.types)

        return if (parameterSlots.isEmpty && resultSlots.isEmpty) {
            NumericHostCall(function, resources)
        } else {
            ReferenceHostCall(function, parameterSlots, resultSlots, codec, lanes, resources)
        }
    }

    @OptIn(ExperimentalChasmApi::class)
    override fun adaptSuspending(
        type: FunctionType,
        function: SuspendHostFunction,
    ): RawHostFunction {

        val parameterSlots = codec.slots(type.params.types)
        val resultSlots = codec.slots(type.results.types)

        return if (parameterSlots.isEmpty && resultSlots.isEmpty) {
            NumericSuspendHostCall(type, function, resources)
        } else {
            ReferenceSuspendHostCall(type, function, parameterSlots, resultSlots, codec, resources)
        }
    }
}

internal class NumericHostCall(
    private val function: HostFunction,
    private val resources: InterpreterHostResources,
) : RawHostFunction {

    context(stack: HostStack, module: RawHostModuleInstance, raw: RawHostResources)
    override fun invoke(
        parameters: HostParameters,
        results: HostResults,
    ) {

        val context = raw as ExecutionContext

        context(stack, NO_REFERENCES, resources.module(module, context), resources) {
            function.invoke(parameters, results)
        }
    }

    private companion object {
        val NO_REFERENCES = arrayOfNulls<Any>(0)
    }
}

internal class ReferenceHostCall(
    private val function: HostFunction,
    private val parameterSlots: ReferenceSlots,
    private val resultSlots: ReferenceSlots,
    private val codec: ReferenceCodec,
    private val lanes: ReferenceLanes,
    private val resources: InterpreterHostResources,
) : RawHostFunction {

    context(stack: HostStack, module: RawHostModuleInstance, raw: RawHostResources)
    override fun invoke(
        parameters: HostParameters,
        results: HostResults,
    ) {

        val context = raw as ExecutionContext
        val parametersEnd = parameters + parameterSlots.valueCount
        val resultsEnd = results + resultSlots.valueCount
        val lane = lanes.acquire(maxOf(parametersEnd, resultsEnd))

        try {
            decodeParameters(stack, lane, parameters)

            context(stack, lane, resources.module(module, context), resources) {
                function.invoke(parameters, results)
            }

            encodeResults(stack, lane, results)
        } finally {
            lane.fill(null, parameters, parametersEnd)
            lane.fill(null, results, resultsEnd)
            lanes.release()
        }
    }

    private fun decodeParameters(
        stack: HostStack,
        lane: Array<Any?>,
        parameters: HostParameters,
    ) {
        for (index in parameterSlots.valueIndices) {
            val slot = parameters + index
            lane[slot] = codec.decode(stack[slot])
        }
    }

    private fun encodeResults(
        stack: HostStack,
        lane: Array<Any?>,
        results: HostResults,
    ) {

        val valueIndices = resultSlots.valueIndices
        val types = resultSlots.types

        for (position in valueIndices.indices) {
            val index = valueIndices[position]
            val slot = results + index
            stack[slot] = encodeResult(types[position], lane[slot], index)
        }
    }

    private fun encodeResult(
        type: ResolvedReferenceType,
        value: Any?,
        index: Int,
    ): Long {
        return try {
            codec.encode(type, value)
        } catch (exception: IllegalArgumentException) {
            throw HostFunctionException("result $index: ${exception.message}")
        }
    }
}

internal class ReferenceLanes {

    private var lanes = arrayOfNulls<Array<Any?>>(INITIAL_DEPTH)
    private var depth = 0

    fun acquire(size: Int): Array<Any?> {

        if (depth == lanes.size) {
            lanes = lanes.copyOf(depth * 2)
        }

        val current = lanes[depth]
        val lane = if (current != null && current.size >= size) {
            current
        } else {
            arrayOfNulls<Any>(maxOf(size, MINIMUM_SIZE, (current?.size ?: 0) * 2)).also { created -> lanes[depth] = created }
        }
        depth++

        return lane
    }

    fun release() {
        depth--
    }

    private companion object {
        const val INITIAL_DEPTH = 4
        const val MINIMUM_SIZE = 16
    }
}

@OptIn(ExperimentalChasmApi::class, UnsafeHostApi::class)
internal class NumericSuspendHostCall(
    type: FunctionType,
    private val function: SuspendHostFunction,
    private val resources: InterpreterHostResources,
) : RawHostFunction {

    private val parameterCount = type.params.types.size
    private val resultCount = type.results.types.size
    private val slotCount = maxOf(parameterCount, resultCount, 1)

    context(stack: HostStack, module: RawHostModuleInstance, raw: RawHostResources)
    override fun invoke(
        parameters: HostParameters,
        results: HostResults,
    ) {
        val context = raw as ExecutionContext
        requireSuspendable(context)

        val hostWords = LongArray(slotCount)
        if (parameterCount > 0) {
            stack.copyInto(hostWords, 0, parameters, parameters + parameterCount)
        }

        context.pendingSuspension = object : PendingSuspension {
            override suspend fun resume() {
                context(hostWords, NO_REFERENCES, resources.module(module, context), resources) {
                    function.invoke(0, 0)
                }
                if (resultCount > 0) {
                    hostWords.copyInto(context.vstack.unsafeElements(), results, 0, resultCount)
                }
            }
        }
    }

    private companion object {
        val NO_REFERENCES = arrayOfNulls<Any>(0)
    }
}

@OptIn(ExperimentalChasmApi::class, UnsafeHostApi::class)
internal class ReferenceSuspendHostCall(
    type: FunctionType,
    private val function: SuspendHostFunction,
    private val parameterSlots: ReferenceSlots,
    private val resultSlots: ReferenceSlots,
    private val codec: ReferenceCodec,
    private val resources: InterpreterHostResources,
) : RawHostFunction {

    private val parameterCount = type.params.types.size
    private val resultCount = type.results.types.size
    private val slotCount = maxOf(parameterCount, resultCount, 1)

    context(stack: HostStack, module: RawHostModuleInstance, raw: RawHostResources)
    override fun invoke(
        parameters: HostParameters,
        results: HostResults,
    ) {
        val context = raw as ExecutionContext
        requireSuspendable(context)

        val hostWords = LongArray(slotCount)
        if (parameterCount > 0) {
            stack.copyInto(hostWords, 0, parameters, parameters + parameterCount)
        }

        context.pendingSuspension = object : PendingSuspension {
            override suspend fun resume() {
                val lane = arrayOfNulls<Any>(slotCount)
                for (index in parameterSlots.valueIndices) {
                    lane[index] = codec.decode(hostWords[index])
                }

                context(hostWords, lane, resources.module(module, context), resources) {
                    function.invoke(0, 0)
                }

                val valueIndices = resultSlots.valueIndices
                val types = resultSlots.types
                for (position in valueIndices.indices) {
                    val index = valueIndices[position]
                    hostWords[index] = encodeResult(types[position], lane[index], index)
                }
                if (resultCount > 0) {
                    hostWords.copyInto(context.vstack.unsafeElements(), results, 0, resultCount)
                }
            }
        }
    }

    private fun encodeResult(
        type: ResolvedReferenceType,
        value: Any?,
        index: Int,
    ): Long {
        return try {
            codec.encode(type, value)
        } catch (exception: IllegalArgumentException) {
            throw HostFunctionException("result $index: ${exception.message}")
        }
    }
}

private fun requireSuspendable(context: ExecutionContext) {
    if (!context.allowSuspend) {
        throw HostFunctionException("cannot call a suspending host function from a synchronous invocation")
    }
    if (!context.config.suspendable) {
        throw HostFunctionException("the store was not configured with RuntimeConfig(suspendable = true)")
    }
}
