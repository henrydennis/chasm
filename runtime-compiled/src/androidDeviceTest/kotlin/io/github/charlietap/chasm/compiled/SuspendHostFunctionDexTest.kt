@file:OptIn(ExperimentalChasmApi::class, InternalChasmApi::class)

package io.github.charlietap.chasm.compiled

import com.github.michaelbull.result.getOrElse
import io.github.charlietap.chasm.ExperimentalChasmApi
import io.github.charlietap.chasm.InternalChasmApi
import io.github.charlietap.chasm.ast.module.typeResolver
import io.github.charlietap.chasm.compiler.backend.dex.DexOptions
import io.github.charlietap.chasm.compiler.backend.dex.DexResources
import io.github.charlietap.chasm.compiler.backend.dex.ParallelModuleLowerer
import io.github.charlietap.chasm.compiler.backend.dex.abi.ART_BYTE_ARRAY_BASE_OFFSET
import io.github.charlietap.chasm.compiler.frontend.ModuleCompiler
import io.github.charlietap.chasm.config.ModuleConfig
import io.github.charlietap.chasm.config.RuntimeConfig
import io.github.charlietap.chasm.decoder.WasmModuleDecoder
import io.github.charlietap.chasm.embedding.error.ExecutionError
import io.github.charlietap.chasm.embedding.exports
import io.github.charlietap.chasm.embedding.function
import io.github.charlietap.chasm.embedding.functionReference
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
import io.github.charlietap.chasm.embedding.shapes.Instance
import io.github.charlietap.chasm.embedding.shapes.Module
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
import io.github.charlietap.chasm.module.ModuleDeclarationsFactory
import io.github.charlietap.chasm.parallel.coroutines.CallerParticipatingParallelTaskExecutor
import io.github.charlietap.chasm.type.AbstractHeapType
import io.github.charlietap.chasm.type.FunctionType
import io.github.charlietap.chasm.type.NumberType
import io.github.charlietap.chasm.type.ReferenceType
import io.github.charlietap.chasm.type.ResultType
import io.github.charlietap.chasm.type.TagType
import io.github.charlietap.chasm.type.ValueType
import io.github.charlietap.chasm.validator.WasmModuleValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SuspendHostFunctionDexTest {

    @Test
    fun singleSuspendPreservesMixedTypesAndExternref() = runBlocking<Unit> {
        verifyMixedTypes(splitInstructions = null)
        verifyMixedTypes(splitInstructions = 3)
    }

    @Test
    fun deepMultiFrameCallStackSuspendsAndResumes() = runBlocking<Unit> {
        verifyMultiFrame(splitInstructions = null)
        verifyMultiFrame(splitInstructions = 3)
    }

    @Test
    fun loopWithMultipleSuspensionsUpdatesMemoryAndLocals() = runBlocking<Unit> {
        verifyLoop(splitInstructions = null)
        verifyLoop(splitInstructions = 3)
    }

    @Test
    fun indirectCallSurvivesTableMutationAndTailCallsResume() = runBlocking<Unit> {
        // call_indirect with table mutation while suspended
        val indirectModule = compileSuspendDex(buildIndirectTableMutationWasm())
        val indirectStore = store(indirectModule)
        var instRef: Instance? = null
        val asyncAdd = suspendFunction(indirectStore, i32ToI32()) { params, results ->
            val x = params.readI32(0)
            yield()
            val tbl = assertNotNull(exports(checkNotNull(instRef)).table("tbl"))
            val poison = assertNotNull(exports(checkNotNull(instRef)).function("poison"))
            writeTable(tbl, 0, functionReference(poison))
            results.writeI32(0, x + 50)
        }
        val indirectInst = instance(indirectStore, indirectModule, listOf(Import("env", "async_add", asyncAdd))).expect("instantiates")
        instRef = indirectInst
        assertEquals(listOf(I32(1064)), invokeSuspending(indirectInst, "caller", listOf(I32(7))).expect("caller"))

        // return_call tail call into suspending function
        val tailModule = compileSuspendDex(buildTailCallWasm())
        val tailStore = store(tailModule)
        val doubleAsync = suspendFunction(tailStore, i32ToI32()) { params, results ->
            val x = params.readI32(0)
            yield()
            results.writeI32(0, x * 2)
        }
        val tailInst = instance(tailStore, tailModule, listOf(Import("env", "double_async", doubleAsync))).expect("instantiates")
        assertEquals(listOf(I32(27)), invokeSuspending(tailInst, "trampoline", listOf(I32(10))).expect("trampoline"))
        assertEquals(listOf(I32(127)), invokeSuspending(tailInst, "outer", listOf(I32(10))).expect("outer"))
    }

    @Test
    fun tryTableCatchesExceptionRaisedAfterSuspension() = runBlocking<Unit> {
        verifyTryTable(splitInstructions = null)
        verifyTryTable(splitInstructions = 3)
    }

    @Test
    fun cancellationCleansUpAndKeepsStoreReusable() = runBlocking<Unit> {
        verifyCancellation(splitInstructions = null)
        verifyCancellation(splitInstructions = 3)
    }

    @Test
    fun reentrantInvokeSuspendingWorks() = runBlocking<Unit> {
        val mod = compileSuspendDex(buildReentrantWasm())
        val s = store(mod)
        var instRef: Instance? = null
        val innerHost = suspendFunction(s, i32ToI32()) { params, results ->
            val x = params.readI32(0)
            yield()
            results.writeI32(0, x + 100)
        }
        val outerHost = suspendFunction(s, i32ToI32()) { params, results ->
            val x = params.readI32(0)
            yield()
            val nested = invokeSuspending(checkNotNull(instRef), "inner_entry", listOf(I32(x + 1))).expect("nested")
            results.writeI32(0, (nested.single() as I32).value * 2)
        }
        val inst = instance(
            s,
            mod,
            listOf(Import("env", "outer_host", outerHost), Import("env", "inner_host", innerHost)),
        ).expect("instantiates")
        instRef = inst
        assertEquals(listOf(I32(229)), invokeSuspending(inst, "outer_entry", listOf(I32(5))).expect("outer_entry"))
    }

    @Test
    fun linearMemoryGrowthDuringSuspendRefreshesAndUnboxedWorks() = runBlocking<Unit> {
        val mod = compileSuspendDex(buildMemoryGrowWasm())
        val s = store(mod)
        var instRef: Instance? = null
        val host = suspendFunction(s, i32ToI32()) { params, results ->
            val offset = params.readI32(0)
            yield()
            val mem = assertNotNull(exports(checkNotNull(instRef)).memory("mem"))
            assertEquals(1, growMemory(mem, 1))
            writeInt(mem, offset, 0x1234_5678)
            results.writeI32(0, offset)
        }
        val inst = instance(s, mod, listOf(Import("env", "grow_and_write", host))).expect("instantiates")
        instRef = inst

        val prepared = prepareFunction(inst, "read_after_grow").expect("prepares")
        val words = longArrayOf(70_000L)
        val refs = arrayOfNulls<Any>(1)
        prepared.invokeUnboxedSuspending(words, refs).expect("unboxed")
        assertEquals(0x1234_5678, words[0].toInt())
    }

    @Test
    fun guardrailsRejectSyncAndNonSuspendableCalls() = runBlocking<Unit> {
        val wasm = buildLeafAndImportWasm()
        val suspendModule = dexModule(wasm, RuntimeConfig(suspendable = true))
        val plainModule = dexModule(wasm, RuntimeConfig(suspendable = false))

        val suspendStore = store(suspendModule)
        val host = suspendFunction(suspendStore, i32ToI32()) { params, results ->
            yield()
            results.writeI32(0, params.readI32(0) + 1)
        }
        val inst = instance(suspendStore, suspendModule, listOf(Import("env", "h", host))).expect("instantiates")
        assertEquals(listOf(I32(42)), invoke(inst, "pure_add", listOf(I32(41))).expect("pure_add"))
        val syncResult = invoke(inst, "with_import", listOf(I32(1)))
        assertIs<ExecutionError.Trapped>(assertIs<ChasmResult.Error<*>>(syncResult).error)

        val plainStore = store(plainModule)
        val plainHost = suspendFunction(plainStore, i32ToI32()) { params, results ->
            yield()
            results.writeI32(0, params.readI32(0) + 1)
        }
        val plainInst = instance(plainStore, plainModule, listOf(Import("env", "h", plainHost))).expect("instantiates")
        val nonSuspendResult = invokeSuspending(plainInst, "with_import", listOf(I32(1)))
        assertIs<ExecutionError.Trapped>(assertIs<ChasmResult.Error<*>>(nonSuspendResult).error)

        val syncDummy = function(suspendStore, i32ToI32()) { params, results -> results.writeI32(0, params.readI32(0)) }
        val mixResult = instance(suspendStore, plainModule, listOf(Import("env", "h", syncDummy)))
        assertIs<ExecutionError.Unlinkable>(assertIs<ChasmResult.Error<*>>(mixResult).error)
    }

    private suspend fun verifyMixedTypes(splitInstructions: Int?) {
        val mod = compileSuspendDex(buildMixedTypesWasm(), splitInstructions)
        val s = store(mod)
        val sig = ResultType(
            listOf(
                ValueType.Number(NumberType.I32),
                ValueType.Number(NumberType.I64),
                ValueType.Number(NumberType.F32),
                ValueType.Number(NumberType.F64),
                ValueType.Reference(ReferenceType.RefNull(AbstractHeapType.Extern)),
            ),
        )
        val host = suspendFunction(s, FunctionType(sig, sig)) { params, results ->
            val i = params.readI32(0)
            val l = params.readI64(1)
            val f = params.readF32(2)
            val d = params.readF64(3)
            val ref = params.readReference(4) as String
            val transformed = withContext(Dispatchers.Default) {
                delay(5)
                "art:$ref:$i"
            }
            results.writeI32(0, i + 100)
            results.writeI64(1, l + 1_000L)
            results.writeF32(2, f * 2f)
            results.writeF64(3, d + 0.5)
            results.writeReference(4, transformed)
        }
        val inst = instance(s, mod, listOf(Import("env", "fetch", host))).expect("instantiates")
        val res = invokeSuspending(
            inst,
            "run",
            listOf(I32(7), I64(42L), F32(1.5f), F64(2.25), ReferenceValue.Extern("hello")),
        ).expect("invokes")

        assertEquals(I32(114), res[0])
        assertEquals(I64(1084L), res[1])
        assertEquals(F32(4.5f), res[2])
        assertEquals(F64(5.0), res[3])
        assertEquals(ReferenceValue.Extern("art:hello:7"), res[4])
    }

    private suspend fun verifyMultiFrame(splitInstructions: Int?) {
        val mod = compileSuspendDex(buildMultiFrameWasm(), splitInstructions)
        val s = store(mod)
        val gate = CompletableDeferred<Int>()
        val started = CompletableDeferred<Int>()
        val host = suspendFunction(s, i32ToI32()) { params, results ->
            val arg = params.readI32(0)
            started.complete(arg)
            val response = gate.await()
            results.writeI32(0, arg + response)
        }
        val inst = instance(s, mod, listOf(Import("env", "await_val", host))).expect("instantiates")
        val jobResult = CompletableDeferred<List<Any>>()
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            jobResult.complete(invokeSuspending(inst, "outer", listOf(I32(5))).expect("outer"))
        }
        assertEquals(8, started.await())
        assertTrue(job.isActive)
        gate.complete(100)
        assertEquals(listOf(I32(154)), jobResult.await())
        job.join()
    }

    private suspend fun verifyLoop(splitInstructions: Int?) {
        val mod = compileSuspendDex(buildLoopWasm(), splitInstructions)
        val s = store(mod)
        var calls = 0
        val host = suspendFunction(s, i32ToI32()) { params, results ->
            val i = params.readI32(0)
            calls++
            yield()
            results.writeI32(0, i * i)
        }
        val inst = instance(s, mod, listOf(Import("env", "square_async", host))).expect("instantiates")
        assertEquals(listOf(I32(55)), invokeSuspending(inst, "sum_squares", listOf(I32(6))).expect("loop"))
        assertEquals(6, calls)
        val mem = assertNotNull(exports(inst).memory("mem"))
        for (i in 0 until 6) {
            assertEquals(i * i, readInt(mem, i * 4))
        }
    }

    private suspend fun verifyTryTable(splitInstructions: Int?) {
        val mod = compileSuspendDex(buildTryTableWasm(), splitInstructions)
        val s = store(mod)
        val errTag = tag(
            s,
            TagType(TagType.Attribute.Exception, 0, FunctionType(ResultType(listOf(ValueType.Number(NumberType.I32))), ResultType(emptyList()))),
        )
        val host = suspendFunction(s, i32ToI32()) { params, _ ->
            val code = params.readI32(0)
            yield()
            raise(createException(errTag, longArrayOf((code * 10).toLong()), arrayOfNulls(1)))
        }
        val inst = instance(s, mod, listOf(Import("env", "err", errTag), Import("env", "fail_async", host))).expect("instantiates")
        assertEquals(listOf(I32(78)), invokeSuspending(inst, "caught", listOf(I32(7))).expect("caught"))
        val uncaught = invokeSuspending(inst, "uncaught", listOf(I32(9)))
        assertIs<ExecutionError.Thrown>(assertIs<ChasmResult.Error<*>>(uncaught).error)
    }

    private suspend fun verifyCancellation(splitInstructions: Int?) {
        val mod = compileSuspendDex(buildMultiFrameWasm(), splitInstructions)
        val s = store(mod)
        var shouldCancel = true
        val entered = CompletableDeferred<Unit>()
        val host = suspendFunction(s, i32ToI32()) { params, results ->
            val arg = params.readI32(0)
            if (shouldCancel) {
                entered.complete(Unit)
                awaitCancellation()
            } else {
                yield()
                results.writeI32(0, arg + 1)
            }
        }
        val inst = instance(s, mod, listOf(Import("env", "await_val", host))).expect("instantiates")
        val prepared = prepareFunction(inst, "outer").expect("prepares")

        var cancelled = false
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
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

        shouldCancel = false
        assertEquals(listOf(I32(55)), prepared.invokeSuspending(listOf(I32(5))).expect("reusable"))
    }

    private fun compileSuspendDex(
        bytes: ByteArray,
        splitInstructions: Int? = null,
    ): Module {
        if (splitInstructions == null) {
            return dexModule(bytes, RuntimeConfig(suspendable = true))
        }
        val className = dexClassName()
        val config = ModuleConfig()
        val decoded = WasmModuleDecoder(config, bytes).getOrElse { failure -> error("$failure") }
        val validated = WasmModuleValidator(config, decoded).getOrElse { failure -> error("$failure") }
        val code = ModuleCompiler(validated, validated.typeResolver(), recordProvenance = false, devirtualiseIndirectCalls = false)
            .getOrElse { failure -> error("$failure") }
        val resources = CollectedResources()
        val executor = CallerParticipatingParallelTaskExecutor(Dispatchers.Default, Semaphore(2))
        val options = DexOptions(
            entryClass = className,
            runtime = RuntimeConfig(suspendable = true),
            byteArrayBaseOffset = ART_BYTE_ARRAY_BASE_OFFSET,
            splitInstructions = splitInstructions,
        )
        val dex = runBlocking {
            ParallelModuleLowerer(validated, code, options, ModuleDeclarationsFactory(validated), emptyMap(), resources, executor)
        }
        return module(DexInput.memory(dex.dex, resources.resources), className).expect("chunked DEX loads")
    }

    private fun i32ToI32(): FunctionType {
        return FunctionType(
            ResultType(listOf(ValueType.Number(NumberType.I32))),
            ResultType(listOf(ValueType.Number(NumberType.I32))),
        )
    }

    private class CollectedResources : DexResources {
        val resources = HashMap<String, ByteArray>()

        override fun <T> resource(
            path: String,
            write: (OutputStream) -> T,
        ): T {
            val stream = ByteArrayOutputStream()
            val result = write(stream)
            resources[path] = stream.toByteArray()
            return result
        }
    }
}

private const val I32_T = 0x7f
private const val I64_T = 0x7e
private const val F32_T = 0x7d
private const val F64_T = 0x7c
private const val EXTERNREF_T = 0x6f
private const val FUNCREF_T = 0x70

private class DexWasmBuilder {
    private val out = ArrayList<Byte>()

    init {
        bytes(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
    }

    fun section(id: Int, block: DexByteBuf.() -> Unit) {
        val content = DexByteBuf().apply(block).toBytes()
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

private class DexByteBuf {
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

    fun body(locals: List<Pair<Int, Int>> = emptyList(), block: DexByteBuf.() -> Unit) {
        val bodyBuf = DexByteBuf().apply {
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

private fun wasmModule(block: DexWasmBuilder.() -> Unit): ByteArray = DexWasmBuilder().apply(block).toBytes()

private fun buildMixedTypesWasm(): ByteArray = wasmModule {
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
        body(listOf(1 to I32_T, 1 to I64_T, 1 to F32_T, 1 to F64_T, 1 to EXTERNREF_T)) {
            b(0x20, 0, 0x20, 1, 0x20, 2, 0x20, 3, 0x20, 4)
            b(0x10, 0)
            b(0x21, 9, 0x21, 8, 0x21, 7, 0x21, 6, 0x21, 5)
            b(0x20, 5, 0x20, 0, 0x6a)
            b(0x20, 6, 0x20, 1, 0x7c)
            b(0x20, 7, 0x20, 2, 0x92)
            b(0x20, 8, 0x20, 3, 0xa0)
            b(0x20, 9)
        }
    }
}

private fun buildMultiFrameWasm(): ByteArray = wasmModule {
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
        body(listOf(1 to I32_T)) {
            b(0x20, 0, 0x41)
            sleb(5)
            b(0x6a, 0x21, 1)
            b(0x20, 0, 0x10, 0)
            b(0x20, 1, 0x6a)
        }
        body(listOf(1 to I32_T)) {
            b(0x20, 0, 0x41)
            sleb(3)
            b(0x6c, 0x21, 1)
            b(0x20, 0, 0x41)
            sleb(2)
            b(0x6a, 0x10, 1)
            b(0x20, 1, 0x6a)
        }
        body(listOf(1 to I32_T)) {
            b(0x20, 0, 0x41)
            sleb(10)
            b(0x6a, 0x21, 1)
            b(0x20, 0, 0x41)
            sleb(1)
            b(0x6a, 0x10, 2)
            b(0x20, 1, 0x6a)
        }
    }
}

private fun buildLoopWasm(): ByteArray = wasmModule {
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
            b(0x20, 1, 0x20, 0, 0x4e, 0x0d, 1)
            b(0x20, 1, 0x10, 0, 0x22, 3)
            b(0x20, 2, 0x6a, 0x21, 2)
            b(0x20, 1, 0x41)
            sleb(4)
            b(0x6c)
            b(0x20, 3, 0x36, 0x02, 0x00)
            b(0x20, 1, 0x41)
            sleb(1)
            b(0x6a, 0x21, 1)
            b(0x0c, 0)
            b(0x0b)
            b(0x0b)
            b(0x20, 2)
        }
    }
}

private fun buildIndirectTableMutationWasm(): ByteArray = wasmModule {
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
        uleb(0)
    }
    section(4) {
        uleb(1)
        b(FUNCREF_T, 0x00)
        uleb(2)
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
        uleb(2)
    }
    section(10) {
        uleb(3)
        body { b(0x20, 0, 0x10, 0, 0x20, 0, 0x6a) }
        body { b(0x00) }
        body(listOf(1 to I32_T)) {
            b(0x41)
            sleb(1000)
            b(0x21, 1)
            b(0x20, 0, 0x41, 0x00, 0x11, 0x00, 0x00)
            b(0x20, 1, 0x6a)
        }
    }
}

private fun buildTailCallWasm(): ByteArray = wasmModule {
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
        body {
            b(0x20, 0, 0x10, 0, 0x41)
            sleb(1)
            b(0x6a)
        }
        body {
            b(0x20, 0, 0x41)
            sleb(3)
            b(0x6a, 0x12, 1)
        }
        body(listOf(1 to I32_T)) {
            b(0x41)
            sleb(100)
            b(0x21, 1)
            b(0x20, 0, 0x10, 2)
            b(0x20, 1, 0x6a)
        }
    }
}

private fun buildTryTableWasm(): ByteArray = wasmModule {
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
            b(0x6a, 0x21, 1)
            b(0x02, I32_T)
            b(0x1f, I32_T, 0x01, 0x00, 0x00, 0x00)
            b(0x20, 0, 0x10, 0)
            b(0x0b)
            b(0x0f)
            b(0x0b)
            b(0x20, 1, 0x6a)
        }
        body { b(0x20, 0, 0x10, 0) }
    }
}

private fun buildReentrantWasm(): ByteArray = wasmModule {
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

private fun buildMemoryGrowWasm(): ByteArray = wasmModule {
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
            b(0x41, 0x00, 0x41, 42, 0x36, 0x02, 0x00)
            b(0x20, 0, 0x10, 0)
            b(0x28, 0x02, 0x00)
        }
    }
}

private fun buildLeafAndImportWasm(): ByteArray = wasmModule {
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
