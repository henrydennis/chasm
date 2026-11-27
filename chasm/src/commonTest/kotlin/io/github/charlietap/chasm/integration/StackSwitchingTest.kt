package io.github.charlietap.chasm.integration

import com.goncalossilva.resources.Resource
import io.github.charlietap.chasm.ExperimentalChasmApi
import io.github.charlietap.chasm.config.ModuleConfig
import io.github.charlietap.chasm.config.Proposal
import io.github.charlietap.chasm.config.RuntimeConfig
import io.github.charlietap.chasm.embedding.collect
import io.github.charlietap.chasm.embedding.error.ExecutionError
import io.github.charlietap.chasm.embedding.function
import io.github.charlietap.chasm.embedding.instance
import io.github.charlietap.chasm.embedding.invoke
import io.github.charlietap.chasm.embedding.invokeSuspending
import io.github.charlietap.chasm.embedding.module
import io.github.charlietap.chasm.embedding.shapes.ChasmResult
import io.github.charlietap.chasm.embedding.shapes.Import
import io.github.charlietap.chasm.embedding.shapes.Instance
import io.github.charlietap.chasm.embedding.shapes.Store
import io.github.charlietap.chasm.embedding.shapes.expect
import io.github.charlietap.chasm.embedding.store
import io.github.charlietap.chasm.embedding.suspendFunction
import io.github.charlietap.chasm.embedding.value.NumberValue.I32
import io.github.charlietap.chasm.host.readI32
import io.github.charlietap.chasm.host.writeI32
import io.github.charlietap.chasm.type.FunctionType
import io.github.charlietap.chasm.type.NumberType
import io.github.charlietap.chasm.type.ResultType
import io.github.charlietap.chasm.type.ValueType
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StackSwitchingTest {

    private val stackSwitchingConfig = ModuleConfig(
        proposals = Proposal.entries.toSet(),
    )

    @Test
    fun `stack switching module is rejected when proposal is disabled`() = runTest {
        val bytes = Resource(MODULE_PATH).readBytes()
        assertIs<ChasmResult.Error<*>>(module(bytes))
    }

    @Test
    fun `casts to continuation types fail validation`() = runTest {
        val bytes = Resource(INVALID_CAST_PATH).readBytes()
        assertIs<ChasmResult.Error<*>>(module(bytes, stackSwitchingConfig))
    }

    @Test
    fun `basic cont new and resume executes function`() = runTest {
        val (_, instance) = instantiate()
        val result = invoke(instance, "basic_resume", listOf(I32(41))).expect("basic_resume should succeed")
        assertEquals(listOf(I32(42)), result)
    }

    @Test
    fun `chained cont bind partially applies arguments in order`() = runTest {
        val (_, instance) = instantiate()
        val result = invoke(instance, "chained_bind").expect("chained_bind should succeed")
        assertEquals(listOf(I32(123)), result)
    }

    @Test
    fun `suspend and resume pass values back and forth across multiple yields`() = runTest {
        val (_, instance) = instantiate()
        // start=5 -> yield 15 -> resume 30 -> yield 50 -> resume 100 -> return 130
        val result = invoke(instance, "suspend_roundtrip", listOf(I32(5))).expect("suspend_roundtrip should succeed")
        assertEquals(listOf(I32(130)), result)
    }

    @Test
    fun `nested prompts capture and restore intermediate prompts across outer suspension`() = runTest {
        val (_, instance) = instantiate()
        // start=3 -> outer yields 3, resumes 30 -> inner yields 30, resumes 35 -> 30 + 35 = 65
        val result = invoke(instance, "nested_prompts", listOf(I32(3))).expect("nested_prompts should succeed")
        assertEquals(listOf(I32(65)), result)
    }

    @Test
    fun `resume throw can be caught inside a suspended continuation`() = runTest {
        val (_, instance) = instantiate()
        val result = invoke(instance, "resume_throw_caught").expect("resume_throw_caught should succeed")
        assertEquals(listOf(I32(1042)), result)
    }

    @Test
    fun `uncaught resume throw propagates back to caller try_table`() = runTest {
        val (_, instance) = instantiate()
        val result = invoke(instance, "resume_throw_uncaught").expect("resume_throw_uncaught should succeed")
        assertEquals(listOf(I32(99)), result)
    }

    @Test
    fun `resume throw ref rethrows an existing exception into a suspended continuation`() = runTest {
        val (_, instance) = instantiate()
        val result = invoke(instance, "resume_throw_ref_caught").expect("resume_throw_ref_caught should succeed")
        assertEquals(listOf(I32(1077)), result)
    }

    @Test
    fun `switch transfers directly between peer continuations under on switch prompt`() = runTest {
        val (_, instance) = instantiate()
        // 5 -> peer_a sends 6 -> peer_b sends 12 -> peer_a sends 22 -> peer_b returns 122
        val result = invoke(instance, "switch_ping_pong", listOf(I32(5))).expect("switch_ping_pong should succeed")
        assertEquals(listOf(I32(122)), result)
    }

    @Test
    fun `gc collection preserves heap roots referenced by suspended continuations`() = runTest {
        val (_, instance) = instantiate()
        val result = invoke(instance, "gc_preserves_suspended_roots", listOf(I32(789)))
            .expect("gc_preserves_suspended_roots should succeed")
        assertEquals(listOf(I32(789)), result)
    }

    @Test
    fun `covariant continuation subtyping validates and executes`() = runTest {
        val (_, instance) = instantiate()
        val result = invoke(instance, "covariant_subtyping").expect("covariant_subtyping should succeed")
        assertEquals(listOf(I32(555)), result)
    }

    @Test
    fun `cont new can wrap and resume an imported host function`() = runTest {
        val (_, instance) = instantiate()
        val result = invoke(instance, "host_cont_resume", listOf(I32(17), I32(25)))
            .expect("host_cont_resume should succeed")
        assertEquals(listOf(I32(42)), result)
    }

    @Test
    fun `resuming a null continuation traps`() = runTest {
        val (_, instance) = instantiate()
        val result = invoke(instance, "trap_null_cont")
        val error = assertIs<ChasmResult.Error<ExecutionError>>(result).error
        assertIs<ExecutionError.Trapped>(error)
    }

    @Test
    fun `resuming an already consumed continuation traps`() = runTest {
        val (_, instance) = instantiate()
        val result = invoke(instance, "trap_already_consumed")
        val error = assertIs<ChasmResult.Error<ExecutionError>>(result).error
        assertIs<ExecutionError.Trapped>(error)
    }

    @Test
    fun `unhandled suspend traps`() = runTest {
        val (_, instance) = instantiate()
        val result = invoke(instance, "trap_unhandled_suspend")
        val error = assertIs<ChasmResult.Error<ExecutionError>>(result).error
        assertIs<ExecutionError.Trapped>(error)
    }

    @Test
    fun `unhandled switch traps`() = runTest {
        val (_, instance) = instantiate()
        val result = invoke(instance, "trap_unhandled_switch")
        val error = assertIs<ChasmResult.Error<ExecutionError>>(result).error
        assertIs<ExecutionError.Trapped>(error)
    }

    @OptIn(ExperimentalChasmApi::class)
    @Test
    fun `cont new can wrap and resume a suspending host function`() = runTest {
        val store = store(RuntimeConfig(suspendable = true))
        val bytes = Resource(MODULE_PATH).readBytes()
        val decoded = module(bytes, stackSwitchingConfig).expect("stack_switching.wasm should decode and validate")
        val gcHost = function(
            store = store,
            type = FunctionType(ResultType(emptyList()), ResultType(emptyList())),
        ) { _, _ ->
            collect(store)
        }
        val addHost = suspendFunction(
            store = store,
            type = FunctionType(
                ResultType(listOf(ValueType.Number(NumberType.I32), ValueType.Number(NumberType.I32))),
                ResultType(listOf(ValueType.Number(NumberType.I32))),
            ),
        ) { args, results ->
            yield()
            results.writeI32(0, args.readI32(0) + args.readI32(1))
        }
        val instance = instance(
            store = store,
            module = decoded,
            imports = listOf(Import("env", "gc", gcHost), Import("env", "add_host", addHost)),
        ).expect("stack_switching.wasm should instantiate")

        val result = invokeSuspending(instance, "host_cont_resume", listOf(I32(17), I32(25)))
            .expect("host_cont_resume with suspending host should succeed")
        assertEquals(listOf(I32(42)), result)
    }

    private suspend fun instantiate(): Pair<Store, Instance> {
        val store = store()
        val bytes = Resource(MODULE_PATH).readBytes()
        val decoded = module(bytes, stackSwitchingConfig).expect("stack_switching.wasm should decode and validate")
        val gcHost = function(
            store = store,
            type = FunctionType(ResultType(emptyList()), ResultType(emptyList())),
        ) { _, _ ->
            collect(store)
        }
        val addHost = function(
            store = store,
            type = FunctionType(
                ResultType(listOf(ValueType.Number(NumberType.I32), ValueType.Number(NumberType.I32))),
                ResultType(listOf(ValueType.Number(NumberType.I32))),
            ),
        ) { args, results ->
            results.writeI32(0, args.readI32(0) + args.readI32(1))
        }
        val imports = listOf(
            Import("env", "gc", gcHost),
            Import("env", "add_host", addHost),
        )
        val instance = instance(store, decoded, imports).expect("stack_switching.wasm should instantiate")
        return store to instance
    }

    companion object {
        private const val MODULE_PATH = "integration/stack_switching.wasm"
        private const val INVALID_CAST_PATH = "integration/stack_switching_invalid_cast.wasm"
    }
}
