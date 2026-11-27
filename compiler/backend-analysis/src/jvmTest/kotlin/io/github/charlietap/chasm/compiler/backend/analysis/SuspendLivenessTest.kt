package io.github.charlietap.chasm.compiler.backend.analysis

import com.github.michaelbull.result.getOrThrow
import io.github.charlietap.chasm.cir.ControlInstruction
import io.github.charlietap.chasm.compiler.backend.analysis.form.InstructionForms
import io.github.charlietap.chasm.compiler.frontend.ModuleCompiler
import io.github.charlietap.chasm.config.ModuleConfig
import io.github.charlietap.chasm.decoder.WasmModuleDecoder
import io.github.charlietap.chasm.validator.WasmModuleValidator
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuspendLivenessTest {

    @Test
    fun `dead locals before call are excluded and live locals after call are included`() {
        val wasm = buildWasm {
            section(1) {
                uleb(1)
                funcType(listOf(I32), listOf(I32))
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
            section(10) {
                uleb(1)
                // param 0 (live), local 1 (dead before call), local 2 (live after call)
                body(listOf(2 to I32)) {
                    b(0x20, 0, 0x41, 10, 0x6A, 0x21, 1) // local1 = param0 + 10
                    b(0x20, 1, 0x41, 5, 0x6A, 0x21, 2) // local2 = local1 + 5; local1 is dead now
                    b(0x20, 0, 0x10, 0) // call h(param0)
                    b(0x20, 2, 0x6A) // + local2
                    b(0x20, 0, 0x6A) // + param0
                }
            }
        }
        val (function, table, frame) = compileFirst(wasm)
        val callPos = function.instructions.indexOfFirst { it is ControlInstruction.Call }
        val live = SuspendLiveness(function, table, frame.frameSlots).liveAfterCall(callPos).toSet()

        assertTrue(frame.frameSlots >= 4, "expected at least 4 frame slots, got ${frame.frameSlots}")
        assertTrue(0 in live, "param 0 read after call must be live; live=$live")
        assertTrue(live.size == 2, "only param 0 and local 2 should be live across call, got $live out of ${frame.frameSlots} slots")
    }

    @Test
    fun `try_table handler live-ins are included while catch payload slots are excluded`() {
        val wasm = buildWasm {
            section(1) {
                uleb(2)
                funcType(listOf(I32), emptyList())
                funcType(listOf(I32), listOf(I32))
            }
            section(2) {
                uleb(2)
                str("env")
                str("err")
                b(0x04, 0x00)
                uleb(0)
                str("env")
                str("fail")
                b(0x00)
                uleb(1)
            }
            section(3) {
                uleb(1)
                uleb(1)
            }
            section(10) {
                uleb(1)
                // param 0, local 1 (only used in catch handler), local 2 (dead before call)
                body(listOf(2 to I32)) {
                    b(0x20, 0, 0x41, 7, 0x6A, 0x21, 2) // local2 = param0 + 7
                    b(0x20, 2, 0x41, 3, 0x6A, 0x21, 1) // local1 = local2 + 3 (local2 dead)
                    b(0x02, I32) // block $h (result i32)
                    b(0x1F, I32, 0x01, 0x00, 0x00, 0x00) // try_table (catch 0 $h)
                    b(0x20, 0, 0x10, 0) // call fail(param0)
                    b(0x0B) // end try_table
                    b(0x0F) // return result directly (local1 is NOT used on normal path!)
                    b(0x0B) // end $h: stack has caught payload
                    b(0x20, 1, 0x6A) // payload + local1
                }
            }
        }
        val (function, table, frame) = compileFirst(wasm)
        val callPos = function.instructions.indexOfFirst { it is ControlInstruction.Call }
        val catches = function.exceptions!!.regions.flatMap { it.catches }
        val payloadSlots = catches.flatMap { it.payloadSlots.toList() }.toSet()
        val handlerStart = catches.first().target.index
        val handlerReads = (table.reads.start[handlerStart] until table.reads.end(handlerStart))
            .map { idx -> table.reads.slots[idx] }
            .filter { slot -> slot !in payloadSlots }
            .toSet()
        val live = SuspendLiveness(function, table, frame.frameSlots).liveAfterCall(callPos).toSet()

        assertTrue(handlerReads.isNotEmpty(), "expected catch handler to read a saved local")
        for (slot in handlerReads) {
            assertTrue(slot in live, "slot $slot read in catch handler must be live across call; live=$live")
        }
        for (payload in payloadSlots) {
            assertFalse(payload in live, "catch payload slot $payload written by handler dispatch must not be live before call")
        }
        assertTrue(live.size == handlerReads.size, "only catch-handler live locals should be preserved, got $live")
    }

    private fun compileFirst(wasm: ByteArray): Triple<io.github.charlietap.chasm.cir.CirFunction, InstructionTable, FrameFacts> {
        val config = ModuleConfig()
        val decoded = WasmModuleDecoder(config, wasm).getOrThrow { IllegalStateException(it.toString()) }
        val validated = WasmModuleValidator(config, decoded).getOrThrow { IllegalStateException(it.toString()) }
        val compiled = ModuleCompiler(validated).getOrThrow { IllegalStateException(it.toString()) }
        val function = compiled.functions.first()
        val forms = InstructionForms(function, ModuleIndexSpaces(validated))
        return Triple(function, instructionTable(forms, objectReferences = true), frameFacts(forms))
    }
}

private const val I32 = 0x7F

private class AnalysisWasmBuilder {
    private val out = ArrayList<Byte>()

    init {
        b(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }

    fun b(vararg v: Int) = v.forEach { out.add(it.toByte()) }

    fun raw(arr: ByteArray) = arr.forEach { out.add(it) }

    fun uleb(v: Int) {
        var n = v
        do {
            var byte = n and 0x7F
            n = n ushr 7
            if (n != 0) byte = byte or 0x80
            out.add(byte.toByte())
        } while (n != 0)
    }

    fun str(s: String) {
        val utf8 = s.encodeToByteArray()
        uleb(utf8.size)
        raw(utf8)
    }

    fun section(id: Int, block: AnalysisWasmBuilder.() -> Unit) {
        val inner = AnalysisWasmBuilder().apply {
            out.clear()
            block()
        }.toByteArray()
        b(id)
        uleb(inner.size)
        raw(inner)
    }

    fun funcType(params: List<Int>, results: List<Int>) {
        b(0x60)
        uleb(params.size)
        params.forEach { b(it) }
        uleb(results.size)
        results.forEach { b(it) }
    }

    fun body(locals: List<Pair<Int, Int>> = emptyList(), block: AnalysisWasmBuilder.() -> Unit) {
        val inner = AnalysisWasmBuilder().apply {
            out.clear()
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

    fun toByteArray(): ByteArray = out.toByteArray()
}

private fun buildWasm(block: AnalysisWasmBuilder.() -> Unit) = AnalysisWasmBuilder().apply(block).toByteArray()
