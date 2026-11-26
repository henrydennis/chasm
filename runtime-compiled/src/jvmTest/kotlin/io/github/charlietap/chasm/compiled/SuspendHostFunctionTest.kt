@file:OptIn(ExperimentalChasmApi::class, InternalChasmApi::class)

package io.github.charlietap.chasm.compiled

import io.github.charlietap.chasm.ExperimentalChasmApi
import io.github.charlietap.chasm.InternalChasmApi
import io.github.charlietap.chasm.compiled.code.generate
import io.github.charlietap.chasm.compiler.backend.jvm.JvmOptions
import io.github.charlietap.chasm.config.RuntimeConfig
import io.github.charlietap.chasm.embedding.error.ExecutionError
import io.github.charlietap.chasm.embedding.exports
import io.github.charlietap.chasm.embedding.function
import io.github.charlietap.chasm.embedding.instance
import io.github.charlietap.chasm.embedding.invoke
import io.github.charlietap.chasm.embedding.invokeSuspending
import io.github.charlietap.chasm.embedding.invokeUnboxedSuspending
import io.github.charlietap.chasm.embedding.memory.growMemory
import io.github.charlietap.chasm.embedding.memory.readInt
import io.github.charlietap.chasm.embedding.memory.writeInt
import io.github.charlietap.chasm.embedding.prepareFunction
import io.github.charlietap.chasm.embedding.reference.createException
import io.github.charlietap.chasm.embedding.shapes.ChasmResult
import io.github.charlietap.chasm.embedding.shapes.Import
import io.github.charlietap.chasm.embedding.shapes.expect
import io.github.charlietap.chasm.embedding.suspendFunction
import io.github.charlietap.chasm.embedding.table.writeTable
import io.github.charlietap.chasm.embedding.tag
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SuspendHostFunctionTest {

    @Test
    fun `single suspend host call preserves locals across i32, i64, f32, f64, externref, and multi-value results`() = runBlocking<Unit> {
        val wasm = buildMixedTypesSuspendModule()
        val generated = generate(wasm, suspendOptions("test.suspend.MixedModule"))
        val module = generated.compiledModule()
        val store = store(module)

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

        val host = suspendFunction(store, hostType) { params, results ->
            val i = params.readI32(0)
            val l = params.readI64(1)
            val f = params.readF32(2)
            val d = params.readF64(3)
            val s = params.readReference(4) as String
            val transformed = withContext(Dispatchers.Default) {
                delay(5)
                "async:$s:$i"
            }
            results.writeI32(0, i + 100)
            results.writeI64(1, l + 1_000L)
            results.writeF32(2, f * 2f)
            results.writeF64(3, d + 0.5)
            results.writeReference(4, transformed)
        }

        val inst = instance(store, module, listOf(Import("env", "fetch", host))).expect("instantiates")
        val result = invokeSuspending(
            inst,
            "run",
            listOf(I32(7), I64(42L), F32(1.5f), F64(2.25), ReferenceValue.Extern("hello")),
        ).expect("invokes")

        // Wasm preserves original args in locals and adds them to the suspended results:
        // i32: (7 + 100) + 7 = 114, i64: (42 + 1000) + 42 = 1084, f32: (1.5 * 2) + 1.5 = 4.5, f64: (2.25 + 0.5) + 2.25 = 5.0
        assertEquals(I32(114), result[0])
        assertEquals(I64(1084L), result[1])
        assertEquals(F32(4.5f), result[2])
        assertEquals(F64(5.0), result[3])
        assertEquals(ReferenceValue.Extern("async:hello:7"), result[4])
    }

    @Test
    fun `deep multi-frame Wasm call stack suspends and resumes with every frame's locals intact`() = runBlocking<Unit> {
        val wasm = buildMultiFrameSuspendModule()
        val generated = generate(wasm, suspendOptions("test.suspend.DeepModule"))
        val module = generated.compiledModule()
        val store = store(module)

        val gate = CompletableDeferred<Int>()
        val started = CompletableDeferred<Int>()
        val host = suspendFunction(store, i32ToI32()) { params, results ->
            val arg = params.readI32(0)
            started.complete(arg)
            val response = gate.await()
            results.writeI32(0, arg + response)
        }

        val inst = instance(store, module, listOf(Import("env", "await_val", host))).expect("instantiates")
        val jobResult = CompletableDeferred<List<Any>>()
        val job = launch {
            val out = invokeSuspending(inst, "outer", listOf(I32(5))).expect("invokes")
            jobResult.complete(out)
        }

        // outer(5): localA = 5 + 10 = 15, calls middle(5 + 1 = 6)
        // middle(6): localB = 6 * 3 = 18, calls inner(6 + 2 = 8)
        // inner(8): localC = 8 + 5 = 13, calls await_val(8)
        assertEquals(8, started.await())
        assertTrue(job.isActive)
        gate.complete(100)

        // inner returns (8 + 100) + 13 = 121
        // middle returns 121 + 18 = 139
        // outer returns 139 + 15 = 154
        assertEquals(listOf(I32(154)), jobResult.await())
    }

    @Test
    fun `loop with multiple suspensions accumulates locals and linear memory across iterations`() = runBlocking<Unit> {
        val wasm = buildLoopSuspendModule()
        val generated = generate(wasm, suspendOptions("test.suspend.LoopModule"))
        val module = generated.compiledModule()
        val store = store(module)

        var calls = 0
        val host = suspendFunction(store, i32ToI32()) { params, results ->
            val i = params.readI32(0)
            calls++
            yield()
            results.writeI32(0, i * i)
        }

        val inst = instance(store, module, listOf(Import("env", "square_async", host))).expect("instantiates")
        val result = invokeSuspending(inst, "sum_squares", listOf(I32(6))).expect("invokes")

        // sum_{i=0..5} i^2 = 0 + 1 + 4 + 9 + 16 + 25 = 55
        assertEquals(6, calls)
        assertEquals(listOf(I32(55)), result)

        val mem = assertNotNull(exports(inst).memory("mem"))
        for (i in 0 until 6) {
            assertEquals(i * i, readInt(mem, i * 4))
        }
    }

    @Test
    fun `call_indirect suspends cleanly even when the table entry is overwritten before resuming`() = runBlocking<Unit> {
        val wasm = buildIndirectTableMutationSuspendModule()
        val generated = generate(wasm, suspendOptions("test.suspend.IndirectModule"))
        val module = generated.compiledModule()
        val store = store(module)

        var instRef: io.github.charlietap.chasm.embedding.shapes.Instance? = null
        val host = suspendFunction(store, i32ToI32()) { params, results ->
            val x = params.readI32(0)
            yield()
            // Overwrite table[0] with the poison function (index 1 in table) while suspended!
            val tbl = assertNotNull(exports(checkNotNull(instRef)).table("tbl"))
            val poison = assertNotNull(exports(checkNotNull(instRef)).function("poison"))
            writeTable(tbl, 0, io.github.charlietap.chasm.embedding.functionReference(poison))
            results.writeI32(0, x + 50)
        }

        val inst = instance(store, module, listOf(Import("env", "async_add", host))).expect("instantiates")
        instRef = inst

        // caller invokes table[0] (worker) with arg=7.
        // worker suspends in async_add(7), host mutates table[0] -> poison (which traps unreachable),
        // then worker resumes and returns (7 + 50) + 7 = 64, caller adds 1000 -> 1064.
        val result = invokeSuspending(inst, "caller", listOf(I32(7))).expect("invokes")
        assertEquals(listOf(I32(1064)), result)
    }

    @Test
    fun `return_call tail call into a suspending function suspends and resumes cleanly`() = runBlocking<Unit> {
        val wasm = buildTailCallSuspendModule()
        val generated = generate(wasm, suspendOptions("test.suspend.TailModule"))
        val module = generated.compiledModule()
        val store = store(module)

        val host = suspendFunction(store, i32ToI32()) { params, results ->
            val x = params.readI32(0)
            yield()
            results.writeI32(0, x * 2)
        }

        val inst = instance(store, module, listOf(Import("env", "double_async", host))).expect("instantiates")

        // Top-level return_call: trampoline(10) -> return_call leaf(10 + 3 = 13) -> double_async(13) + 1 = 27
        assertEquals(listOf(I32(27)), invokeSuspending(inst, "trampoline", listOf(I32(10))).expect("trampoline"))

        // Nested return_call inside caller: outer(10) calls trampoline(10) -> return_call leaf(13) -> 27, outer adds local 100 -> 127
        assertEquals(listOf(I32(127)), invokeSuspending(inst, "outer", listOf(I32(10))).expect("outer"))
    }

    @Test
    fun `Wasm try_table catches a WasmException thrown by a suspend host function after suspending`() = runBlocking<Unit> {
        val wasm = buildTryTableSuspendModule()
        val generated = generate(wasm, suspendOptions("test.suspend.TryTableModule"))
        val module = generated.compiledModule()
        val store = store(module)

        val errTag = tag(store, TagType(TagType.Attribute.Exception, 0, FunctionType(ResultType(listOf(ValueType.Number(NumberType.I32))), ResultType(emptyList()))))
        val host = suspendFunction(store, i32ToI32()) { params, _ ->
            val code = params.readI32(0)
            yield()
            raise(createException(errTag, longArrayOf((code * 10).toLong()), arrayOfNulls(1)))
        }

        val inst = instance(store, module, listOf(Import("env", "err", errTag), Import("env", "fail_async", host))).expect("instantiates")

        // Caught inside try_table: preserves local (x + 1 = 8) + caught payload (7 * 10 = 70) = 78
        assertEquals(listOf(I32(78)), invokeSuspending(inst, "caught", listOf(I32(7))).expect("caught"))

        // Uncaught escapes as ExecutionError.Thrown
        val uncaught = invokeSuspending(inst, "uncaught", listOf(I32(9)))
        assertIs<ExecutionError.Thrown>(assertIs<ChasmResult.Error<*>>(uncaught).error)
    }

    @Test
    fun `coroutine cancellation while suspended cleans up state and keeps the store reusable`() = runBlocking<Unit> {
        val wasm = buildMultiFrameSuspendModule()
        val generated = generate(wasm, suspendOptions("test.suspend.CancelModule"))
        val module = generated.compiledModule()
        val store = store(module)

        var shouldCancel = true
        val entered = CompletableDeferred<Unit>()
        val host = suspendFunction(store, i32ToI32()) { params, results ->
            val arg = params.readI32(0)
            if (shouldCancel) {
                entered.complete(Unit)
                awaitCancellation()
            } else {
                yield()
                results.writeI32(0, arg + 1)
            }
        }

        val inst = instance(store, module, listOf(Import("env", "await_val", host))).expect("instantiates")
        val prepared = prepareFunction(inst, "outer").expect("prepares")

        var cancelled = false
        val job = launch {
            try {
                prepared.invokeSuspending(listOf(I32(5)))
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }

        entered.await()
        job.cancelAndJoin()
        assertTrue(cancelled)

        // Reuse the exact same PreparedFunction and Store after cancellation:
        shouldCancel = false
        // outer(5) -> middle(6) -> inner(8) -> host(8)=9 -> inner: 9+13=22 -> middle: 22+18=40 -> outer: 40+15=55
        assertEquals(listOf(I32(55)), prepared.invokeSuspending(listOf(I32(5))).expect("reusable"))
    }

    @Test
    fun `re-entrant invokeSuspending from inside a SuspendHostFunction works cleanly`() = runBlocking<Unit> {
        val wasm = buildReentrantSuspendModule()
        val generated = generate(wasm, suspendOptions("test.suspend.ReentrantModule"))
        val module = generated.compiledModule()
        val store = store(module)

        var instRef: io.github.charlietap.chasm.embedding.shapes.Instance? = null
        val innerHost = suspendFunction(store, i32ToI32()) { params, results ->
            val x = params.readI32(0)
            yield()
            results.writeI32(0, x + 100)
        }
        val outerHost = suspendFunction(store, i32ToI32()) { params, results ->
            val x = params.readI32(0)
            yield()
            val nested = invokeSuspending(checkNotNull(instRef), "inner_entry", listOf(I32(x + 1))).expect("nested")
            results.writeI32(0, (nested.single() as I32).value * 2)
        }

        val inst = instance(
            store,
            module,
            listOf(Import("env", "outer_host", outerHost), Import("env", "inner_host", innerHost)),
        ).expect("instantiates")
        instRef = inst

        // outer_entry(5) calls outer_host(5) -> invokes inner_entry(6) -> inner_host(6)=106 -> inner_entry returns 106+6=112
        // -> outer_host returns 112*2=224 -> outer_entry adds local 5 -> 229
        assertEquals(listOf(I32(229)), invokeSuspending(inst, "outer_entry", listOf(I32(5))).expect("outer_entry"))
    }

    @Test
    fun `inline memory refreshes cleanly if host grows linear memory while suspended and unboxed lanes work`() = runBlocking<Unit> {
        val wasm = buildMemoryGrowDuringSuspendModule()
        val generated = generate(
            wasm,
            JvmOptions(
                entryClass = "test.suspend.MemoryGrowModule",
                runtime = RuntimeConfig(suspendable = true),
                memory = io.github.charlietap.chasm.compiler.backend.jvm.MemoryLowering.BYTE_BUFFER,
            ),
        )
        val module = generated.compiledModule()
        val store = store(module)

        var instRef: io.github.charlietap.chasm.embedding.shapes.Instance? = null
        val host = suspendFunction(store, i32ToI32()) { params, results ->
            val offset = params.readI32(0)
            yield()
            val mem = assertNotNull(exports(checkNotNull(instRef)).memory("mem"))
            // Grow linear memory from 1 page (65536) to 2 pages (131072) and write into the NEW page!
            assertEquals(1, growMemory(mem, 1))
            writeInt(mem, offset, 0x1234_5678)
            results.writeI32(0, offset)
        }

        val inst = instance(store, module, listOf(Import("env", "grow_and_write", host))).expect("instantiates")
        instRef = inst

        val prepared = prepareFunction(inst, "read_after_grow").expect("prepares")
        val words = longArrayOf(70_000L) // offset in page 2 (only valid AFTER memory.grow!)
        val refs = arrayOfNulls<Any>(1)
        prepared.invokeUnboxedSuspending(words, refs).expect("invokes unboxed")
        assertEquals(0x1234_5678, words[0].toInt())
    }

    @Test
    fun `guardrails reject sync calls, non-suspendable modules, mismatched stores, and omit overhead on pure leaf functions`() = runBlocking<Unit> {
        val wasm = buildLeafAndImportModule()

        // 1. Default compile (suspendable = false): zero suspension runtime calls anywhere
        val plainGen = generate(wasm, JvmOptions(entryClass = "test.suspend.PlainModule"))
        assertFalse(anyClassCalls(plainGen.classes, "pushSuspendedFrame"))
        assertFalse(anyClassCalls(plainGen.classes, "popSuspendedFrame"))

        // 2. Suspendable compile: pure leaf function 'pure_add' has zero suspension calls; 'with_import' has them
        val suspendGen = generate(wasm, suspendOptions("test.suspend.GuardModule"))
        assertTrue(anyClassCalls(suspendGen.classes, "pushSuspendedFrame"))
        assertTrue(anyClassCalls(suspendGen.classes, "popSuspendedFrame"))
        assertFalse(methodCalls(suspendGen.classes, "f1", "pushSuspendedFrame"), "pure leaf function should not emit suspension code")

        // 3. Calling a suspendFunction from synchronous invoke() fails cleanly
        val suspendModule = suspendGen.compiledModule()
        val suspendStore = store(suspendModule)
        val host = suspendFunction(suspendStore, i32ToI32()) { params, results ->
            yield()
            results.writeI32(0, params.readI32(0) + 1)
        }
        val inst = instance(suspendStore, suspendModule, listOf(Import("env", "h", host))).expect("instantiates")
        val syncResult = invoke(inst, "with_import", listOf(I32(1)))
        assertIs<ExecutionError.Trapped>(assertIs<ChasmResult.Error<*>>(syncResult).error)

        // 4. Calling a suspendFunction on a module compiled with suspendable = false fails cleanly
        val plainModule = plainGen.compiledModule()
        val plainStore = store(plainModule)
        val plainHost = suspendFunction(plainStore, i32ToI32()) { params, results ->
            yield()
            results.writeI32(0, params.readI32(0) + 1)
        }
        val plainInst = instance(plainStore, plainModule, listOf(Import("env", "h", plainHost))).expect("instantiates")
        val nonSuspendResult = invokeSuspending(plainInst, "with_import", listOf(I32(1)))
        assertIs<ExecutionError.Trapped>(assertIs<ChasmResult.Error<*>>(nonSuspendResult).error)

        // 5. Mixing suspendable = true and suspendable = false in one store is rejected as Unlinkable
        val syncDummy = function(suspendStore, i32ToI32()) { params, results -> results.writeI32(0, params.readI32(0)) }
        val mixResult = instance(suspendStore, plainModule, listOf(Import("env", "h", syncDummy)))
        assertIs<ExecutionError.Unlinkable>(assertIs<ChasmResult.Error<*>>(mixResult).error)
    }

    private fun suspendOptions(entryClass: String): JvmOptions {
        return JvmOptions(
            entryClass = entryClass,
            runtime = RuntimeConfig(suspendable = true),
        )
    }

    private fun i32ToI32(): FunctionType {
        return FunctionType(ResultType(listOf(ValueType.Number(NumberType.I32))), ResultType(listOf(ValueType.Number(NumberType.I32))))
    }

    private fun anyClassCalls(
        classes: Map<String, ByteArray>,
        methodName: String,
    ): Boolean {
        return classes.values.any { bytes ->
            val node = ClassNode()
            ClassReader(bytes).accept(node, 0)
            node.methods.any { method ->
                method.instructions.any { insn -> insn is MethodInsnNode && insn.name == methodName }
            }
        }
    }

    private fun methodCalls(
        classes: Map<String, ByteArray>,
        wasmMethodPrefix: String,
        targetName: String,
    ): Boolean {
        return classes.values.any { bytes ->
            val node = ClassNode()
            ClassReader(bytes).accept(node, 0)
            node.methods.any { method ->
                method.name == wasmMethodPrefix &&
                    method.instructions.any { insn -> insn is MethodInsnNode && insn.name == targetName }
            }
        }
    }
}

// --- Self-contained Wasm binary builders for each suspension scenario ---

private const val I32_T = 0x7f
private const val I64_T = 0x7e
private const val F32_T = 0x7d
private const val F64_T = 0x7c
private const val EXTERNREF_T = 0x6f
private const val FUNCREF_T = 0x70

private class WasmBuilder {
    private val out = ArrayList<Byte>()

    init {
        bytes(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
    }

    fun section(id: Int, block: ByteBuf.() -> Unit) {
        val content = ByteBuf().apply(block).toBytes()
        bytes(id)
        leb(content.size)
        out.addAll(content.toList())
    }

    fun toBytes(): ByteArray = out.toByteArray()

    private fun bytes(vararg values: Int) = values.forEach { out.add(it.toByte()) }

    private fun leb(value: Int) {
        var rest = value
        do {
            val low = rest and 0x7f
            rest = rest ushr 7
            out.add((if (rest != 0) low or 0x80 else low).toByte())
        } while (rest != 0)
    }
}

private class ByteBuf {
    private val out = ArrayList<Byte>()

    fun b(vararg values: Int) = values.forEach { out.add(it.toByte()) }

    fun uleb(value: Int) {
        var rest = value
        do {
            val low = rest and 0x7f
            rest = rest ushr 7
            out.add((if (rest != 0) low or 0x80 else low).toByte())
        } while (rest != 0)
    }

    fun sleb(value: Int) {
        var current = value
        var more = true
        while (more) {
            var byte = current and 0x7f
            current = current shr 7
            if ((current == 0 && (byte and 0x40) == 0) || (current == -1 && (byte and 0x40) != 0)) {
                more = false
            } else {
                byte = byte or 0x80
            }
            out.add(byte.toByte())
        }
    }

    fun str(s: String) {
        val utf8 = s.encodeToByteArray()
        uleb(utf8.size)
        utf8.forEach { out.add(it) }
    }

    fun funcType(params: List<Int>, results: List<Int>) {
        b(0x60)
        uleb(params.size)
        params.forEach { b(it) }
        uleb(results.size)
        results.forEach { b(it) }
    }

    fun body(locals: List<Pair<Int, Int>> = emptyList(), block: ByteBuf.() -> Unit) {
        val bodyBuf = ByteBuf().apply {
            uleb(locals.size)
            locals.forEach { (count, type) ->
                uleb(count)
                b(type)
            }
            block()
            b(0x0b)
        }.toBytes()
        uleb(bodyBuf.size)
        bodyBuf.forEach { out.add(it) }
    }

    fun toBytes(): ByteArray = out.toByteArray()
}

private fun wasmModule(block: WasmBuilder.() -> Unit): ByteArray = WasmBuilder().apply(block).toBytes()

/**
 * (import "env" "fetch" (func $fetch (param i32 i64 f32 f64 externref) (result i32 i64 f32 f64 externref)))
 * (func (export "run") (param i32 i64 f32 f64 externref) (result i32 i64 f32 f64 externref)
 *   call $fetch -> store into locals -> add original params to numeric results, return all 5)
 */
internal fun buildMixedTypesSuspendModule(): ByteArray = wasmModule {
    val sig = listOf(I32_T, I64_T, F32_T, F64_T, EXTERNREF_T)
    section(1) {
        uleb(1)
        funcType(sig, sig)
    }
    section(2) {
        uleb(1)
        str("env")
        str("fetch")
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
        // locals 5=i32, 6=i64, 7=f32, 8=f64, 9=externref
        body(listOf(1 to I32_T, 1 to I64_T, 1 to F32_T, 1 to F64_T, 1 to EXTERNREF_T)) {
            b(0x20, 0, 0x20, 1, 0x20, 2, 0x20, 3, 0x20, 4) // get params 0..4
            b(0x10, 0) // call $fetch -> stack: i32, i64, f32, f64, externref
            b(0x21, 9, 0x21, 8, 0x21, 7, 0x21, 6, 0x21, 5) // pop into locals 9..5
            b(0x20, 5, 0x20, 0, 0x6a) // i32.add
            b(0x20, 6, 0x20, 1, 0x7c) // i64.add
            b(0x20, 7, 0x20, 2, 0x92) // f32.add
            b(0x20, 8, 0x20, 3, 0xa0) // f64.add
            b(0x20, 9) // externref
        }
    }
}

/**
 * (import "env" "await_val" (func $await_val (param i32) (result i32)))
 * inner(z): localC = z + 5; return await_val(z) + localC
 * middle(y): localB = y * 3; return inner(y + 2) + localB
 * outer(x): localA = x + 10; return middle(x + 1) + localA
 */
internal fun buildMultiFrameSuspendModule(): ByteArray = wasmModule {
    section(1) {
        uleb(1)
        funcType(listOf(I32_T), listOf(I32_T))
    }
    section(2) {
        uleb(1)
        str("env")
        str("await_val")
        b(0x00)
        uleb(0)
    }
    section(3) {
        uleb(3)
        uleb(0)
        uleb(0)
        uleb(0) // f1=inner, f2=middle, f3=outer
    }
    section(7) {
        uleb(1)
        str("outer")
        b(0x00)
        uleb(3)
    }
    section(10) {
        uleb(3)
        // f1 = inner(z)
        body(listOf(1 to I32_T)) {
            b(0x20, 0, 0x41)
            sleb(5)
            b(0x6a, 0x21, 1) // local1 = z + 5
            b(0x20, 0, 0x10, 0) // await_val(z)
            b(0x20, 1, 0x6a) // + local1
        }
        // f2 = middle(y)
        body(listOf(1 to I32_T)) {
            b(0x20, 0, 0x41)
            sleb(3)
            b(0x6c, 0x21, 1) // local1 = y * 3
            b(0x20, 0, 0x41)
            sleb(2)
            b(0x6a, 0x10, 1) // inner(y + 2)
            b(0x20, 1, 0x6a) // + local1
        }
        // f3 = outer(x)
        body(listOf(1 to I32_T)) {
            b(0x20, 0, 0x41)
            sleb(10)
            b(0x6a, 0x21, 1) // local1 = x + 10
            b(0x20, 0, 0x41)
            sleb(1)
            b(0x6a, 0x10, 2) // middle(x + 1)
            b(0x20, 1, 0x6a) // + local1
        }
    }
}

/**
 * Memory + loop calling square_async(i) N times, storing each result into mem[i*4], accumulating into sum.
 */
internal fun buildLoopSuspendModule(): ByteArray = wasmModule {
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
        uleb(1) // memory min=1 page
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
        // param 0 = n, local 1 = i, local 2 = sum, local 3 = sq
        body(listOf(3 to I32_T)) {
            b(0x02, 0x40) // block
            b(0x03, 0x40) // loop
            b(0x20, 1, 0x20, 0, 0x4e, 0x0d, 1) // if i >= n br 1 (break)
            b(0x20, 1, 0x10, 0, 0x22, 3) // sq = tee(square_async(i))
            b(0x20, 2, 0x6a, 0x21, 2) // sum += sq
            b(0x20, 1, 0x41)
            sleb(4)
            b(0x6c) // addr = i * 4
            b(0x20, 3, 0x36, 0x02, 0x00) // i32.store align=2 offset=0
            b(0x20, 1, 0x41)
            sleb(1)
            b(0x6a, 0x21, 1) // i += 1
            b(0x0c, 0) // br 0 (continue)
            b(0x0b) // end loop
            b(0x0b) // end block
            b(0x20, 2) // return sum
        }
    }
}

/**
 * Table[0] = worker (calls async_add), poison = unreachable; caller calls call_indirect table[0].
 */
internal fun buildIndirectTableMutationSuspendModule(): ByteArray = wasmModule {
    section(1) {
        uleb(1)
        funcType(listOf(I32_T), listOf(I32_T))
    }
    section(2) {
        uleb(1)
        str("env")
        str("async_add")
        b(0x00)
        uleb(0)
    }
    section(3) {
        uleb(3)
        uleb(0)
        uleb(0)
        uleb(0) // f1=worker, f2=poison, f3=caller
    }
    section(4) {
        uleb(1)
        b(FUNCREF_T, 0x00)
        uleb(2) // table min=2
    }
    section(7) {
        uleb(3)
        str("caller")
        b(0x00)
        uleb(3)
        str("poison")
        b(0x00)
        uleb(2)
        str("tbl")
        b(0x01)
        uleb(0)
    }
    section(9) {
        uleb(1)
        b(0x00)
        b(0x41, 0x00, 0x0b)
        uleb(2)
        uleb(1)
        uleb(2) // table[0]=f1(worker), table[1]=f2(poison)
    }
    section(10) {
        uleb(3)
        // f1 = worker(x): return async_add(x) + x
        body {
            b(0x20, 0, 0x10, 0, 0x20, 0, 0x6a)
        }
        // f2 = poison(x): unreachable
        body {
            b(0x00)
        }
        // f3 = caller(x): local1 = 1000; return call_indirect(x, 0) + local1
        body(listOf(1 to I32_T)) {
            b(0x41)
            sleb(1000)
            b(0x21, 1)
            b(0x20, 0, 0x41, 0x00, 0x11, 0x00, 0x00) // call_indirect type=0 table=0
            b(0x20, 1, 0x6a)
        }
    }
}

/**
 * Tail-call module:
 * f0 = import env.double_async
 * f1 = leaf(x): return double_async(x) + 1
 * f2 = trampoline(x): return_call leaf(x + 3)
 * f3 = outer(x): local1 = 100; return trampoline(x) + local1
 */
internal fun buildTailCallSuspendModule(): ByteArray = wasmModule {
    section(1) {
        uleb(1)
        funcType(listOf(I32_T), listOf(I32_T))
    }
    section(2) {
        uleb(1)
        str("env")
        str("double_async")
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
        uleb(2)
        str("trampoline")
        b(0x00)
        uleb(2)
        str("outer")
        b(0x00)
        uleb(3)
    }
    section(10) {
        uleb(3)
        // f1 = leaf(x)
        body {
            b(0x20, 0, 0x10, 0, 0x41)
            sleb(1)
            b(0x6a)
        }
        // f2 = trampoline(x): return_call f1(x + 3)
        body {
            b(0x20, 0, 0x41)
            sleb(3)
            b(0x6a, 0x12, 1)
        }
        // f3 = outer(x)
        body(listOf(1 to I32_T)) {
            b(0x41)
            sleb(100)
            b(0x21, 1)
            b(0x20, 0, 0x10, 2)
            b(0x20, 1, 0x6a)
        }
    }
}

/**
 * Exception handling across suspension:
 * tag 0: import env.err (param i32)
 * f0: import env.fail_async (param i32) (result i32)
 * f1 = caught(x): local1 = x + 1; block $h (result i32) try_table (result i32) (catch 0 $h) call fail_async(x) end return end + local1
 * f2 = uncaught(x): return fail_async(x)
 */
internal fun buildTryTableSuspendModule(): ByteArray = wasmModule {
    section(1) {
        uleb(2)
        funcType(listOf(I32_T), emptyList()) // type 0: tag
        funcType(listOf(I32_T), listOf(I32_T)) // type 1: func
    }
    section(2) {
        uleb(2)
        str("env")
        str("err")
        b(0x04, 0x00)
        uleb(0) // tag import (index 0)
        str("env")
        str("fail_async")
        b(0x00)
        uleb(1) // func import (index 0)
    }
    section(3) {
        uleb(2)
        uleb(1)
        uleb(1) // f1=caught, f2=uncaught
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
        // f1 = caught(x)
        body(listOf(1 to I32_T)) {
            b(0x20, 0, 0x41)
            sleb(1)
            b(0x6a, 0x21, 1) // local1 = x + 1
            b(0x02, I32_T) // block $h (result i32)
            b(0x1f, I32_T, 0x01, 0x00, 0x00, 0x00) // try_table (result i32) (catch tag=0 label=0)
            b(0x20, 0, 0x10, 0) // call fail_async(x)
            b(0x0b) // end try_table
            b(0x0f) // return (if didn't throw)
            b(0x0b) // end block $h -> stack has caught i32 payload
            b(0x20, 1, 0x6a) // payload + local1
        }
        // f2 = uncaught(x)
        body {
            b(0x20, 0, 0x10, 0)
        }
    }
}

/**
 * Re-entrant module:
 * f0 = import env.outer_host, f1 = import env.inner_host
 * f2 = inner_entry(x): return inner_host(x) + x
 * f3 = outer_entry(x): return outer_host(x) + x
 */
internal fun buildReentrantSuspendModule(): ByteArray = wasmModule {
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
        body { b(0x20, 0, 0x10, 1, 0x20, 0, 0x6a) }
        body { b(0x20, 0, 0x10, 0, 0x20, 0, 0x6a) }
    }
}

/**
 * Guardrail module:
 * f0 = import env.h (i32 -> i32)
 * f1 = pure_add(x) = x + 1 (pure leaf, !maySuspend)
 * f2 = with_import(x) = h(x) + 1 (maySuspend)
 */
internal fun buildLeafAndImportModule(): ByteArray = wasmModule {
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
        uleb(2)
        uleb(0)
        uleb(0)
    }
    section(7) {
        uleb(2)
        str("pure_add")
        b(0x00)
        uleb(1)
        str("with_import")
        b(0x00)
        uleb(2)
    }
    section(10) {
        uleb(2)
        body {
            b(0x20, 0, 0x41)
            sleb(1)
            b(0x6a)
        }
        body {
            b(0x20, 0, 0x10, 0, 0x41)
            sleb(1)
            b(0x6a)
        }
    }
}

/**
 * Memory grow while suspended:
 * f0 = import env.grow_and_write (i32 -> i32)
 * mem = 1 page (min=1)
 * f1 = read_after_grow(offset): i32.store(0, 42); offset2 = grow_and_write(offset); return i32.load(offset2)
 */
internal fun buildMemoryGrowDuringSuspendModule(): ByteArray = wasmModule {
    section(1) {
        uleb(1)
        funcType(listOf(I32_T), listOf(I32_T))
    }
    section(2) {
        uleb(1)
        str("env")
        str("grow_and_write")
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
        str("read_after_grow")
        b(0x00)
        uleb(1)
        str("mem")
        b(0x02)
        uleb(0)
    }
    section(10) {
        uleb(1)
        body {
            b(0x41, 0x00, 0x41, 42, 0x36, 0x02, 0x00) // i32.store addr=0 val=42 (forces inlineMemory)
            b(0x20, 0, 0x10, 0) // call grow_and_write(offset)
            b(0x28, 0x02, 0x00) // i32.load from newly grown page 2!
        }
    }
}
