@file:OptIn(ExperimentalChasmApi::class)

package io.github.charlietap.chasm.embedding

import io.github.charlietap.chasm.ExperimentalChasmApi
import io.github.charlietap.chasm.config.RuntimeConfig
import io.github.charlietap.chasm.embedding.error.ExecutionError
import io.github.charlietap.chasm.embedding.memory.readInt
import io.github.charlietap.chasm.embedding.reference.createException
import io.github.charlietap.chasm.embedding.shapes.ChasmResult
import io.github.charlietap.chasm.embedding.shapes.Import
import io.github.charlietap.chasm.embedding.shapes.expect
import io.github.charlietap.chasm.embedding.value.NumberValue.F32
import io.github.charlietap.chasm.embedding.value.NumberValue.F64
import io.github.charlietap.chasm.embedding.value.NumberValue.I32
import io.github.charlietap.chasm.embedding.value.NumberValue.I64
import io.github.charlietap.chasm.embedding.value.ReferenceValue
import io.github.charlietap.chasm.host.raise
import io.github.charlietap.chasm.host.readF32
import io.github.charlietap.chasm.host.readF64
import io.github.charlietap.chasm.host.readI32
import io.github.charlietap.chasm.host.readI64
import io.github.charlietap.chasm.host.readReference
import io.github.charlietap.chasm.host.writeF32
import io.github.charlietap.chasm.host.writeF64
import io.github.charlietap.chasm.host.writeI32
import io.github.charlietap.chasm.host.writeI64
import io.github.charlietap.chasm.host.writeReference
import io.github.charlietap.chasm.type.AbstractHeapType
import io.github.charlietap.chasm.type.FunctionType
import io.github.charlietap.chasm.type.NumberType
import io.github.charlietap.chasm.type.ReferenceType
import io.github.charlietap.chasm.type.ResultType
import io.github.charlietap.chasm.type.TagType
import io.github.charlietap.chasm.type.ValueType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SuspendHostFunctionTest {

    @Test
    fun `single suspend host call preserves locals across mixed numeric externref and multi-value results`() = runTest {
        val wasm = buildMixedTypesSuspendModule()
        val store = store(RuntimeConfig(suspendable = true))
        val module = module(wasm).expect("decodes")

        val hostType = FunctionType(
            ResultType(
                listOf(
                    ValueType.Number(NumberType.I32),
                    ValueType.Number(NumberType.I64),
                    ValueType.Number(NumberType.F32),
                    ValueType.Number(NumberType.F64),
                    ValueType.Reference(ReferenceType.RefNull(AbstractHeapType.Extern)),
                ),
            ),
            ResultType(
                listOf(
                    ValueType.Number(NumberType.I32),
                    ValueType.Number(NumberType.I64),
                    ValueType.Number(NumberType.F32),
                    ValueType.Number(NumberType.F64),
                    ValueType.Reference(ReferenceType.RefNull(AbstractHeapType.Extern)),
                ),
            ),
        )

        val hostFn = suspendFunction(store, hostType) { params, results ->
            val i32 = params.readI32(0)
            val i64 = params.readI64(1)
            val f32 = params.readF32(2)
            val f64 = params.readF64(3)
            val ref = params.readReference(4) as String
            yield()
            results.writeI32(0, i32 + 10)
            results.writeI64(1, i64 + 100L)
            results.writeF32(2, f32 + 1.5f)
            results.writeF64(3, f64 + 2.25)
            results.writeReference(4, "$ref-resumed")
        }

        val inst = instance(store, module, listOf(Import("env", "async_op", hostFn))).expect("instantiates")

        val result = invokeSuspending(
            inst,
            "run",
            listOf(I32(7), I64(1000L), F32(2.0f), F64(3.5), ReferenceValue.Extern("hello")),
        ).expect("invokes")

        assertEquals(5, result.size)
        assertEquals(I32(24), result[0]) // (7 + 10) + 7
        assertEquals(I64(2100L), result[1]) // (1000 + 100) + 1000
        assertEquals(F32(5.5f), result[2]) // (2.0 + 1.5) + 2.0
        assertEquals(F64(9.25), result[3]) // (3.5 + 2.25) + 3.5
        assertEquals(ReferenceValue.Extern("hello-resumed"), result[4])
    }

    @Test
    fun `multi-frame call chain suspends and resumes with all caller locals preserved`() = runTest {
        val wasm = buildThreeFrameSuspendModule()
        val store = store(RuntimeConfig(suspendable = true))
        val module = module(wasm).expect("decodes")

        val hostType = FunctionType(
            ResultType(listOf(ValueType.Number(NumberType.I32))),
            ResultType(listOf(ValueType.Number(NumberType.I32))),
        )
        val hostFn = suspendFunction(store, hostType) { params, results ->
            val x = params.readI32(0)
            yield()
            results.writeI32(0, x * 2)
        }

        val inst = instance(store, module, listOf(Import("env", "fetch", hostFn))).expect("instantiates")

        // outer(5) -> local1=50, middle(6) -> local1=60, inner(7) -> local1=70, fetch(8)=16
        // inner=86, middle=146, outer=196
        val result = invokeSuspending(inst, "outer", listOf(I32(5))).expect("invokes")
        assertEquals(listOf(I32(196)), result)
    }

    @Test
    fun `loop with multiple suspensions preserves loop counter accumulator and linear memory`() = runTest {
        val wasm = buildLoopSuspendModule()
        val store = store(RuntimeConfig(suspendable = true))
        val module = module(wasm).expect("decodes")

        val hostType = FunctionType(
            ResultType(listOf(ValueType.Number(NumberType.I32))),
            ResultType(listOf(ValueType.Number(NumberType.I32))),
        )
        val seen = mutableListOf<Int>()
        val hostFn = suspendFunction(store, hostType) { params, results ->
            val i = params.readI32(0)
            seen += i
            yield()
            results.writeI32(0, i * i)
        }

        val inst = instance(store, module, listOf(Import("env", "square_async", hostFn))).expect("instantiates")
        val result = invokeSuspending(inst, "sum_squares", listOf(I32(5))).expect("invokes")

        assertEquals(listOf(I32(30)), result) // 0 + 1 + 4 + 9 + 16
        assertEquals(listOf(0, 1, 2, 3, 4), seen)

        val mem = exports(inst).memory("mem")!!
        for (i in 0 until 5) {
            assertEquals(i * i, readInt(mem, i * 4))
        }
    }

    @Test
    fun `call_indirect and return_call suspend and resume cleanly`() = runTest {
        val wasm = buildIndirectAndTailCallSuspendModule()
        val store = store(RuntimeConfig(suspendable = true))
        val module = module(wasm).expect("decodes")

        val hostType = FunctionType(
            ResultType(listOf(ValueType.Number(NumberType.I32))),
            ResultType(listOf(ValueType.Number(NumberType.I32))),
        )
        val hostFn = suspendFunction(store, hostType) { params, results ->
            val x = params.readI32(0)
            yield()
            results.writeI32(0, x * 3)
        }

        val inst = instance(store, module, listOf(Import("env", "triple_async", hostFn))).expect("instantiates")

        // tail_to_host(7) -> return_call triple_async(7 + 2) = 27
        assertEquals(listOf(I32(27)), invokeSuspending(inst, "tail_to_host", listOf(I32(7))).expect("tail_to_host"))
        // outer_tail(7) -> 100 + tail_to_host(7) = 127
        assertEquals(listOf(I32(127)), invokeSuspending(inst, "outer_tail", listOf(I32(7))).expect("outer_tail"))
        // indirect_call(7) -> 1000 + call_indirect(table[0], 7) = 1000 + 21 = 1021
        assertEquals(listOf(I32(1021)), invokeSuspending(inst, "indirect_call", listOf(I32(7))).expect("indirect_call"))
        // indirect_tail(7) -> return_call_indirect(table[0], 7 + 1) = 24
        assertEquals(listOf(I32(24)), invokeSuspending(inst, "indirect_tail", listOf(I32(7))).expect("indirect_tail"))
    }

    @Test
    fun `try_table catches Wasm exception raised after suspension and propagates uncaught exception`() = runTest {
        val wasm = buildTryTableSuspendModule()
        val store = store(RuntimeConfig(suspendable = true))
        val module = module(wasm).expect("decodes")

        val tagType = TagType(
            TagType.Attribute.Exception,
            0,
            FunctionType(ResultType(listOf(ValueType.Number(NumberType.I32))), ResultType(emptyList())),
        )
        val errTag = tag(store, tagType)

        val hostType = FunctionType(
            ResultType(listOf(ValueType.Number(NumberType.I32))),
            ResultType(listOf(ValueType.Number(NumberType.I32))),
        )
        val hostFn = suspendFunction(store, hostType) { params, results ->
            val x = params.readI32(0)
            yield()
            if (x < 0) {
                val ex = createException(errTag, longArrayOf((-x * 10).toLong()), emptyArray())
                raise(ex)
            }
            results.writeI32(0, x + 5)
        }

        val inst = instance(
            store,
            module,
            listOf(
                Import("env", "err", errTag),
                Import("env", "fail_async", hostFn),
            ),
        ).expect("instantiates")

        assertEquals(listOf(I32(15)), invokeSuspending(inst, "caught", listOf(I32(10))).expect("normal"))
        // x = -4 -> local1 = -3, raises 40, caught -> 40 + (-3) = 37
        assertEquals(listOf(I32(37)), invokeSuspending(inst, "caught", listOf(I32(-4))).expect("caught"))

        val uncaught = invokeSuspending(inst, "uncaught", listOf(I32(-3)))
        assertIs<ChasmResult.Error<ExecutionError.Thrown>>(uncaught)
        // Store remains clean and reusable after uncaught exception
        assertEquals(listOf(I32(8)), invokeSuspending(inst, "uncaught", listOf(I32(3))).expect("reusable"))
    }

    @Test
    fun `re-entrant Wasm to suspend host to Wasm to suspend host works cleanly`() = runTest {
        val wasm = buildReentrantSuspendModule()
        val store = store(RuntimeConfig(suspendable = true))
        val module = module(wasm).expect("decodes")

        val hostType = FunctionType(
            ResultType(listOf(ValueType.Number(NumberType.I32))),
            ResultType(listOf(ValueType.Number(NumberType.I32))),
        )
        var instRef: io.github.charlietap.chasm.embedding.shapes.Instance? = null

        val innerHost = suspendFunction(store, hostType) { params, results ->
            val x = params.readI32(0)
            yield()
            results.writeI32(0, x * 10)
        }
        val outerHost = suspendFunction(store, hostType) { params, results ->
            val x = params.readI32(0)
            yield()
            val innerResult = invokeSuspending(checkNotNull(instRef), "inner_entry", listOf(I32(x + 1))).expect("inner")
            yield()
            results.writeI32(0, (innerResult[0] as I32).value + 100)
        }

        val inst = instance(
            store,
            module,
            listOf(
                Import("env", "outer_host", outerHost),
                Import("env", "inner_host", innerHost),
            ),
        ).expect("instantiates")
        instRef = inst

        // outer_entry(4): outer_host(4) -> inner_entry(5) -> inner_host(5)=50 + 5 = 55 -> +100 = 155 -> +4 = 159
        assertEquals(listOf(I32(159)), invokeSuspending(inst, "outer_entry", listOf(I32(4))).expect("reentrant"))
    }

    @Test
    fun `coroutine cancellation during suspension leaves store reusable`() = runTest {
        val wasm = buildSimpleSuspendModule()
        val store = store(RuntimeConfig(suspendable = true))
        val module = module(wasm).expect("decodes")

        val hostType = FunctionType(
            ResultType(listOf(ValueType.Number(NumberType.I32))),
            ResultType(listOf(ValueType.Number(NumberType.I32))),
        )
        val entered = CompletableDeferred<Unit>()
        var blockForever = true
        val hostFn = suspendFunction(store, hostType) { params, results ->
            val x = params.readI32(0)
            if (blockForever) {
                entered.complete(Unit)
                awaitCancellation()
            }
            yield()
            results.writeI32(0, x + 1)
        }

        val inst = instance(store, module, listOf(Import("env", "h", hostFn))).expect("instantiates")
        val job = launch {
            invokeSuspending(inst, "call_h", listOf(I32(10)))
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)

        blockForever = false
        assertEquals(listOf(I32(43)), invokeSuspending(inst, "call_h", listOf(I32(41))).expect("reusable"))
    }

    @Test
    fun `synchronous invoke and non-suspendable store reject suspending host functions`() = runTest {
        val wasm = buildSimpleSuspendModule()
        val hostType = FunctionType(
            ResultType(listOf(ValueType.Number(NumberType.I32))),
            ResultType(listOf(ValueType.Number(NumberType.I32))),
        )

        // 1. Synchronous invoke on suspendable store
        val suspendStore = store(RuntimeConfig(suspendable = true))
        val suspendModule = module(wasm).expect("decodes")
        val suspendFn = suspendFunction(suspendStore, hostType) { params, results ->
            results.writeI32(0, params.readI32(0) + 1)
        }
        val suspendInst = instance(suspendStore, suspendModule, listOf(Import("env", "h", suspendFn))).expect("instantiates")
        val syncResult = invoke(suspendInst, "call_h", listOf(I32(1)))
        assertIs<ChasmResult.Error<ExecutionError.Trapped>>(syncResult)

        // 2. invokeSuspending on default (suspendable = false) store
        val plainStore = store()
        val plainModule = module(wasm).expect("decodes")
        val plainFn = suspendFunction(plainStore, hostType) { params, results ->
            results.writeI32(0, params.readI32(0) + 1)
        }
        val plainInst = instance(plainStore, plainModule, listOf(Import("env", "h", plainFn))).expect("instantiates")
        val nonSuspendableResult = invokeSuspending(plainInst, "call_h", listOf(I32(1)))
        assertIs<ChasmResult.Error<ExecutionError.Trapped>>(nonSuspendableResult)
    }

    @Test
    fun `unboxed invokeSuspending and direct exported suspend host function work`() = runTest {
        val wasm = buildReexportedHostModule()
        val store = store(RuntimeConfig(suspendable = true))
        val module = module(wasm).expect("decodes")

        val hostType = FunctionType(
            ResultType(listOf(ValueType.Number(NumberType.I32))),
            ResultType(listOf(ValueType.Number(NumberType.I32))),
        )
        val hostFn = suspendFunction(store, hostType) { params, results ->
            val x = params.readI32(0)
            yield()
            results.writeI32(0, x + 7)
        }

        val inst = instance(store, module, listOf(Import("env", "h", hostFn))).expect("instantiates")
        val prepared = prepareFunction(inst, "h").expect("prepares")
        val words = longArrayOf(35L)
        prepared.invokeUnboxedSuspending(words, emptyArray()).expect("unboxed invokes")
        assertEquals(42L, words[0])
    }

    @Test
    fun `garbage collection while suspended preserves live Wasm stack references`() = runTest {
        val wasm = buildSimpleExternrefSuspendModule()
        val store = store(RuntimeConfig(suspendable = true))
        val module = module(wasm).expect("decodes")

        val externref = ValueType.Reference(ReferenceType.RefNull(AbstractHeapType.Extern))
        val hostType = FunctionType(ResultType(listOf(externref)), ResultType(listOf(externref)))
        val hostFn = suspendFunction(store, hostType) { params, results ->
            val input = params.readReference(0) as String
            yield()
            collect(store)
            yield()
            results.writeReference(0, "$input!")
        }

        val inst = instance(store, module, listOf(Import("env", "echo", hostFn))).expect("instantiates")
        val result = invokeSuspending(inst, "keep_and_call", listOf(ReferenceValue.Extern("alive"))).expect("invokes")
        assertEquals(listOf(ReferenceValue.Extern("alive"), ReferenceValue.Extern("alive!")), result)
    }
}

private const val I32_T = 0x7F
private const val I64_T = 0x7E
private const val F32_T = 0x7D
private const val F64_T = 0x7C
private const val FUNCREF_T = 0x70
private const val EXTERNREF_T = 0x6F

private class WasmBuilder {
    private val bytes = mutableListOf<Byte>()

    fun b(vararg v: Int) {
        for (x in v) bytes.add(x.toByte())
    }

    fun raw(arr: ByteArray) {
        for (x in arr) bytes.add(x)
    }

    fun uleb(v: Int) {
        var n = v
        do {
            var byte = n and 0x7F
            n = n ushr 7
            if (n != 0) byte = byte or 0x80
            bytes.add(byte.toByte())
        } while (n != 0)
    }

    fun sleb(v: Int) {
        var n = v
        var more = true
        while (more) {
            var byte = n and 0x7F
            n = n shr 7
            if ((n == 0 && (byte and 0x40) == 0) || (n == -1 && (byte and 0x40) != 0)) {
                more = false
            } else {
                byte = byte or 0x80
            }
            bytes.add(byte.toByte())
        }
    }

    fun str(s: String) {
        val utf8 = s.encodeToByteArray()
        uleb(utf8.size)
        raw(utf8)
    }

    fun section(id: Int, block: WasmBuilder.() -> Unit) {
        val inner = WasmBuilder().apply(block).toByteArray()
        b(id)
        uleb(inner.size)
        raw(inner)
    }

    fun funcType(params: List<Int>, results: List<Int>) {
        b(0x60)
        uleb(params.size)
        for (p in params) b(p)
        uleb(results.size)
        for (r in results) b(r)
    }

    fun body(locals: List<Pair<Int, Int>> = emptyList(), block: WasmBuilder.() -> Unit) {
        val inner = WasmBuilder().apply {
            uleb(locals.size)
            for ((count, type) in locals) {
                uleb(count)
                b(type)
            }
            block()
            b(0x0B)
        }.toByteArray()
        uleb(inner.size)
        raw(inner)
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}

private fun wasmModule(block: WasmBuilder.() -> Unit): ByteArray = WasmBuilder().apply {
    b(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    block()
}.toByteArray()

private fun buildMixedTypesSuspendModule(): ByteArray = wasmModule {
    val sig = listOf(I32_T, I64_T, F32_T, F64_T, EXTERNREF_T)
    section(1) {
        uleb(1)
        funcType(sig, sig)
    }
    section(2) {
        uleb(1)
        str("env")
        str("async_op")
        b(0x00)
        uleb(0)
    }
    section(3) {
        uleb(1)
        uleb(0)
    }
    section(7) {
        uleb(1)
        str("run")
        b(0x00)
        uleb(1)
    }
    section(10) {
        uleb(1)
        body(listOf(1 to I32_T, 1 to I64_T, 1 to F32_T, 1 to F64_T, 1 to EXTERNREF_T)) {
            b(0x20, 0, 0x20, 1, 0x20, 2, 0x20, 3, 0x20, 4, 0x10, 0)
            b(0x21, 9, 0x21, 8, 0x21, 7, 0x21, 6, 0x21, 5)
            b(0x20, 5, 0x20, 0, 0x6A)
            b(0x20, 6, 0x20, 1, 0x7C)
            b(0x20, 7, 0x20, 2, 0x92)
            b(0x20, 8, 0x20, 3, 0xA0)
            b(0x20, 9)
        }
    }
}

private fun buildThreeFrameSuspendModule(): ByteArray = wasmModule {
    section(1) {
        uleb(1)
        funcType(listOf(I32_T), listOf(I32_T))
    }
    section(2) {
        uleb(1)
        str("env")
        str("fetch")
        b(0x00)
        uleb(0)
    }
    section(3) {
        uleb(3)
        uleb(0)
        uleb(0)
        uleb(0)
    }
    section(7) {
        uleb(1)
        str("outer")
        b(0x00)
        uleb(3)
    }
    section(10) {
        uleb(3)
        // f1 = inner(x): local1 = x * 10; return fetch(x + 1) + local1
        body(listOf(1 to I32_T)) {
            b(0x20, 0, 0x41)
            sleb(10)
            b(0x6C, 0x21, 1)
            b(0x20, 0, 0x41)
            sleb(1)
            b(0x6A, 0x10, 0, 0x20, 1, 0x6A)
        }
        // f2 = middle(x): local1 = x * 10; return inner(x + 1) + local1
        body(listOf(1 to I32_T)) {
            b(0x20, 0, 0x41)
            sleb(10)
            b(0x6C, 0x21, 1)
            b(0x20, 0, 0x41)
            sleb(1)
            b(0x6A, 0x10, 1, 0x20, 1, 0x6A)
        }
        // f3 = outer(x): local1 = x * 10; return middle(x + 1) + local1
        body(listOf(1 to I32_T)) {
            b(0x20, 0, 0x41)
            sleb(10)
            b(0x6C, 0x21, 1)
            b(0x20, 0, 0x41)
            sleb(1)
            b(0x6A, 0x10, 2, 0x20, 1, 0x6A)
        }
    }
}

private fun buildLoopSuspendModule(): ByteArray = wasmModule {
    section(1) {
        uleb(1)
        funcType(listOf(I32_T), listOf(I32_T))
    }
    section(2) {
        uleb(1)
        str("env")
        str("square_async")
        b(0x00)
        uleb(0)
    }
    section(3) {
        uleb(1)
        uleb(0)
    }
    section(5) {
        uleb(1)
        b(0x00)
        uleb(1)
    }
    section(7) {
        uleb(2)
        str("sum_squares")
        b(0x00)
        uleb(1)
        str("mem")
        b(0x02)
        uleb(0)
    }
    section(10) {
        uleb(1)
        body(listOf(3 to I32_T)) {
            b(0x02, 0x40)
            b(0x03, 0x40)
            b(0x20, 1, 0x20, 0, 0x4E, 0x0D, 1)
            b(0x20, 1, 0x10, 0, 0x22, 3)
            b(0x20, 2, 0x6A, 0x21, 2)
            b(0x20, 1, 0x41)
            sleb(4)
            b(0x6C)
            b(0x20, 3, 0x36, 0x02, 0x00)
            b(0x20, 1, 0x41)
            sleb(1)
            b(0x6A, 0x21, 1)
            b(0x0C, 0)
            b(0x0B)
            b(0x0B)
            b(0x20, 2)
        }
    }
}

private fun buildIndirectAndTailCallSuspendModule(): ByteArray = wasmModule {
    section(1) {
        uleb(1)
        funcType(listOf(I32_T), listOf(I32_T))
    }
    section(2) {
        uleb(1)
        str("env")
        str("triple_async")
        b(0x00)
        uleb(0)
    }
    section(3) {
        uleb(4)
        uleb(0)
        uleb(0)
        uleb(0)
        uleb(0)
    }
    section(4) {
        uleb(1)
        b(FUNCREF_T, 0x00)
        uleb(1)
    }
    section(7) {
        uleb(4)
        str("tail_to_host")
        b(0x00)
        uleb(1)
        str("outer_tail")
        b(0x00)
        uleb(2)
        str("indirect_call")
        b(0x00)
        uleb(3)
        str("indirect_tail")
        b(0x00)
        uleb(4)
    }
    section(9) {
        uleb(1)
        b(0x00, 0x41, 0x00, 0x0B)
        uleb(1)
        uleb(0) // table[0] = f0 (triple_async)
    }
    section(10) {
        uleb(4)
        // f1 = tail_to_host(x): return_call f0(x + 2)
        body {
            b(0x20, 0, 0x41)
            sleb(2)
            b(0x6A, 0x12, 0)
        }
        // f2 = outer_tail(x): local1 = 100; return tail_to_host(x) + local1
        body(listOf(1 to I32_T)) {
            b(0x41)
            sleb(100)
            b(0x21, 1)
            b(0x20, 0, 0x10, 1, 0x20, 1, 0x6A)
        }
        // f3 = indirect_call(x): local1 = 1000; return call_indirect(x, 0) + local1
        body(listOf(1 to I32_T)) {
            b(0x41)
            sleb(1000)
            b(0x21, 1)
            b(0x20, 0, 0x41, 0x00, 0x11, 0x00, 0x00, 0x20, 1, 0x6A)
        }
        // f4 = indirect_tail(x): return_call_indirect(x + 1, 0)
        body {
            b(0x20, 0, 0x41)
            sleb(1)
            b(0x6A, 0x41, 0x00, 0x13, 0x00, 0x00)
        }
    }
}

private fun buildTryTableSuspendModule(): ByteArray = wasmModule {
    section(1) {
        uleb(2)
        funcType(listOf(I32_T), emptyList())
        funcType(listOf(I32_T), listOf(I32_T))
    }
    section(2) {
        uleb(2)
        str("env")
        str("err")
        b(0x04, 0x00)
        uleb(0)
        str("env")
        str("fail_async")
        b(0x00)
        uleb(1)
    }
    section(3) {
        uleb(2)
        uleb(1)
        uleb(1)
    }
    section(7) {
        uleb(2)
        str("caught")
        b(0x00)
        uleb(1)
        str("uncaught")
        b(0x00)
        uleb(2)
    }
    section(10) {
        uleb(2)
        body(listOf(1 to I32_T)) {
            b(0x20, 0, 0x41)
            sleb(1)
            b(0x6A, 0x21, 1)
            b(0x02, I32_T)
            b(0x1F, I32_T, 0x01, 0x00, 0x00, 0x00)
            b(0x20, 0, 0x10, 0)
            b(0x0B)
            b(0x0F)
            b(0x0B)
            b(0x20, 1, 0x6A)
        }
        body {
            b(0x20, 0, 0x10, 0)
        }
    }
}

private fun buildReentrantSuspendModule(): ByteArray = wasmModule {
    section(1) {
        uleb(1)
        funcType(listOf(I32_T), listOf(I32_T))
    }
    section(2) {
        uleb(2)
        str("env")
        str("outer_host")
        b(0x00)
        uleb(0)
        str("env")
        str("inner_host")
        b(0x00)
        uleb(0)
    }
    section(3) {
        uleb(2)
        uleb(0)
        uleb(0)
    }
    section(7) {
        uleb(2)
        str("inner_entry")
        b(0x00)
        uleb(2)
        str("outer_entry")
        b(0x00)
        uleb(3)
    }
    section(10) {
        uleb(2)
        body { b(0x20, 0, 0x10, 1, 0x20, 0, 0x6A) }
        body { b(0x20, 0, 0x10, 0, 0x20, 0, 0x6A) }
    }
}

private fun buildSimpleSuspendModule(): ByteArray = wasmModule {
    section(1) {
        uleb(1)
        funcType(listOf(I32_T), listOf(I32_T))
    }
    section(2) {
        uleb(1)
        str("env")
        str("h")
        b(0x00)
        uleb(0)
    }
    section(3) {
        uleb(1)
        uleb(0)
    }
    section(7) {
        uleb(1)
        str("call_h")
        b(0x00)
        uleb(1)
    }
    section(10) {
        uleb(1)
        body {
            b(0x20, 0, 0x10, 0, 0x41)
            sleb(1)
            b(0x6A)
        }
    }
}

private fun buildReexportedHostModule(): ByteArray = wasmModule {
    section(1) {
        uleb(1)
        funcType(listOf(I32_T), listOf(I32_T))
    }
    section(2) {
        uleb(1)
        str("env")
        str("h")
        b(0x00)
        uleb(0)
    }
    section(7) {
        uleb(1)
        str("h")
        b(0x00)
        uleb(0)
    }
}

private fun buildSimpleExternrefSuspendModule(): ByteArray = wasmModule {
    section(1) {
        uleb(2)
        funcType(listOf(EXTERNREF_T), listOf(EXTERNREF_T))
        funcType(listOf(EXTERNREF_T), listOf(EXTERNREF_T, EXTERNREF_T))
    }
    section(2) {
        uleb(1)
        str("env")
        str("echo")
        b(0x00)
        uleb(0)
    }
    section(3) {
        uleb(1)
        uleb(1)
    }
    section(7) {
        uleb(1)
        str("keep_and_call")
        b(0x00)
        uleb(1)
    }
    section(10) {
        uleb(1)
        // return (param0, echo(param0))
        body {
            b(0x20, 0, 0x20, 0, 0x10, 0)
        }
    }
}
