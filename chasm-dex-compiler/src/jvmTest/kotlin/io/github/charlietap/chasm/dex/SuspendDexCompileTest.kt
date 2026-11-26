@file:OptIn(InternalChasmApi::class)

package io.github.charlietap.chasm.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.charlietap.chasm.InternalChasmApi
import io.github.charlietap.chasm.config.RuntimeConfig
import io.github.charlietap.chasm.embedding.shapes.expect
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuspendDexCompileTest {

    @Test
    fun `DEX compiler emits suspendable metadata and CPS state machine only when enabled and reachable`() = runTest {
        val wasm = buildLeafAndImportWasm()

        val plainOutput = DexOutput.memory()
        compile(wasm, plainOutput, CompileConfig("test.dex.suspend.PlainModule")).expect("plain compiles")
        assertEquals(0L, moduleBooleanSetting(plainOutput.dexFiles, "Ltest/dex/suspend/PlainModule;", "suspendable"))
        assertFalse(anyDexMethodCalls(plainOutput.dexFiles, "pushSuspendedFrame"))
        assertFalse(anyDexMethodCalls(plainOutput.dexFiles, "popSuspendedFrame"))

        val suspendOutput = DexOutput.memory()
        compile(wasm, suspendOutput, CompileConfig("test.dex.suspend.SuspendModule", RuntimeConfig(suspendable = true))).expect("suspend compiles")
        assertEquals(1L, moduleBooleanSetting(suspendOutput.dexFiles, "Ltest/dex/suspend/SuspendModule;", "suspendable"))
        assertTrue(anyDexMethodCalls(suspendOutput.dexFiles, "pushSuspendedFrame"))
        assertTrue(anyDexMethodCalls(suspendOutput.dexFiles, "popSuspendedFrame"))
        assertTrue(dexMethodHasOpcode(suspendOutput.dexFiles, "f2", Opcode.PACKED_SWITCH))

        // Pure leaf function f1 (!maySuspend) must emit zero suspension machinery even when suspendable = true
        assertFalse(dexMethodCalls(suspendOutput.dexFiles, "f1", "pushSuspendedFrame"))
        assertFalse(dexMethodCalls(suspendOutput.dexFiles, "f1", "popSuspendedFrame"))
    }

    @Test
    fun `DEX compiler lowers direct, indirect, tail-call, loop, and try_table suspending modules cleanly`() = runTest {
        val scenarios = listOf(
            "MultiFrame" to buildMultiFrameWasm(),
            "Loop" to buildLoopWasm(),
            "Indirect" to buildIndirectWasm(),
            "TailCall" to buildTailCallWasm(),
            "TryTable" to buildTryTableWasm(),
        )

        for ((name, wasm) in scenarios) {
            val output = DexOutput.memory()
            val config = CompileConfig("test.dex.suspend.$name", RuntimeConfig(suspendable = true))
            val info = compile(wasm, output, config).expect("$name compiles to DEX")

            assertTrue(output.dexFiles.isNotEmpty(), "$name produced DEX")
            assertTrue("test.dex.suspend.$name" in info.classes, "$name includes entry class")
            assertTrue(anyDexMethodCalls(output.dexFiles, "pushSuspendedFrame"), "$name emits pushSuspendedFrame")
            assertTrue(anyDexMethodCalls(output.dexFiles, "popSuspendedFrame"), "$name emits popSuspendedFrame")
        }
    }

    private fun moduleBooleanSetting(
        dexFiles: List<ByteArray>,
        typeDescriptor: String,
        methodName: String,
    ): Long {
        val method = dexFiles
            .flatMap { bytes -> DexBackedDexFile(Opcodes.forApi(30), bytes).classes }
            .single { clazz -> clazz.type == typeDescriptor }
            .methods
            .single { method -> method.name == methodName }
        return method.implementation!!.instructions.filterIsInstance<WideLiteralInstruction>().single().wideLiteral
    }

    private fun anyDexMethodCalls(
        dexFiles: List<ByteArray>,
        targetName: String,
    ): Boolean {
        return dexFiles.any { bytes ->
            DexBackedDexFile(Opcodes.forApi(30), bytes).classes.any { clazz ->
                clazz.methods.any { method ->
                    method.implementation?.instructions?.any { insn ->
                        insn is ReferenceInstruction && (insn.reference as? MethodReference)?.name == targetName
                    } == true
                }
            }
        }
    }

    private fun dexMethodCalls(
        dexFiles: List<ByteArray>,
        methodName: String,
        targetName: String,
    ): Boolean {
        return dexFiles.any { bytes ->
            DexBackedDexFile(Opcodes.forApi(30), bytes).classes.any { clazz ->
                clazz.methods.any { method ->
                    method.name == methodName &&
                        method.implementation?.instructions?.any { insn ->
                            insn is ReferenceInstruction && (insn.reference as? MethodReference)?.name == targetName
                        } == true
                }
            }
        }
    }

    private fun dexMethodHasOpcode(
        dexFiles: List<ByteArray>,
        methodName: String,
        opcode: Opcode,
    ): Boolean {
        return dexFiles.any { bytes ->
            DexBackedDexFile(Opcodes.forApi(30), bytes).classes.any { clazz ->
                clazz.methods.any { method ->
                    method.name == methodName &&
                        method.implementation?.instructions?.any { insn -> insn.opcode == opcode } == true
                }
            }
        }
    }
}

private const val I32 = 0x7f
private const val FUNCREF = 0x70

private class DexTestWasm {
    private val out = ArrayList<Byte>()

    init {
        bytes(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
    }

    fun section(id: Int, block: Buf.() -> Unit) {
        val content = Buf().apply(block).toBytes()
        bytes(id)
        uleb(content.size)
        content.forEach { out.add(it) }
    }

    fun toBytes(): ByteArray = out.toByteArray()

    private fun bytes(vararg values: Int) = values.forEach { out.add(it.toByte()) }

    private fun uleb(value: Int) {
        var rest = value
        do {
            val low = rest and 0x7f
            rest = rest ushr 7
            out.add((if (rest != 0) low or 0x80 else low).toByte())
        } while (rest != 0)
    }

    class Buf {
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

        fun str(s: String) {
            val bytes = s.encodeToByteArray()
            uleb(bytes.size)
            bytes.forEach { out.add(it) }
        }

        fun funcType(params: List<Int>, results: List<Int>) {
            b(0x60)
            uleb(params.size)
            params.forEach { b(it) }
            uleb(results.size)
            results.forEach { b(it) }
        }

        fun body(locals: List<Pair<Int, Int>> = emptyList(), block: Buf.() -> Unit) {
            val bodyBytes = Buf().apply {
                uleb(locals.size)
                locals.forEach { (c, t) ->
                    uleb(c)
                    b(t)
                }
                block()
                b(0x0b)
            }.toBytes()
            uleb(bodyBytes.size)
            bodyBytes.forEach { out.add(it) }
        }

        fun toBytes(): ByteArray = out.toByteArray()
    }
}

private fun dexWasm(block: DexTestWasm.() -> Unit): ByteArray = DexTestWasm().apply(block).toBytes()

private fun buildLeafAndImportWasm(): ByteArray = dexWasm {
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
        body { b(0x20, 0, 0x41, 1, 0x6a) }
        body { b(0x20, 0, 0x10, 0, 0x41, 1, 0x6a) }
    }
}

private fun buildMultiFrameWasm(): ByteArray = dexWasm {
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
        uleb(2)
        uleb(0)
        uleb(0)
    }
    section(7) {
        uleb(1)
        str("outer")
        b(0x00)
        uleb(2)
    }
    section(10) {
        uleb(2)
        body { b(0x20, 0, 0x10, 0, 0x20, 0, 0x6a) }
        body { b(0x20, 0, 0x10, 1, 0x20, 0, 0x6a) }
    }
}

private fun buildLoopWasm(): ByteArray = dexWasm {
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
    section(5) {
        uleb(1)
        b(0x00)
        uleb(1)
    }
    section(7) {
        uleb(1)
        str("loop")
        b(0x00)
        uleb(1)
    }
    section(10) {
        uleb(1)
        body(listOf(2 to I32)) {
            b(0x02, 0x40, 0x03, 0x40)
            b(0x20, 1, 0x20, 0, 0x4e, 0x0d, 1)
            b(0x20, 1, 0x10, 0, 0x20, 2, 0x6a, 0x21, 2)
            b(0x20, 1, 0x41, 1, 0x6a, 0x21, 1, 0x0c, 0, 0x0b, 0x0b, 0x20, 2)
        }
    }
}

private fun buildIndirectWasm(): ByteArray = dexWasm {
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
        uleb(2)
        uleb(0)
        uleb(0)
    }
    section(4) {
        uleb(1)
        b(FUNCREF, 0x00)
        uleb(1)
    }
    section(7) {
        uleb(1)
        str("caller")
        b(0x00)
        uleb(2)
    }
    section(9) {
        uleb(1)
        b(0x00, 0x41, 0x00, 0x0b)
        uleb(1)
        uleb(1)
    }
    section(10) {
        uleb(2)
        body { b(0x20, 0, 0x10, 0, 0x20, 0, 0x6a) }
        body { b(0x20, 0, 0x41, 0x00, 0x11, 0x00, 0x00, 0x20, 0, 0x6a) }
    }
}

private fun buildTailCallWasm(): ByteArray = dexWasm {
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
        body { b(0x20, 0, 0x10, 0, 0x41, 1, 0x6a) }
        body { b(0x20, 0, 0x41, 3, 0x6a, 0x12, 1) }
        body { b(0x20, 0, 0x10, 2, 0x20, 0, 0x6a) }
    }
}

private fun buildTryTableWasm(): ByteArray = dexWasm {
    section(1) {
        uleb(2)
        funcType(listOf(I32), emptyList())
        funcType(listOf(I32), listOf(I32))
    }
    section(2) {
        uleb(2)
        str("env")
        str("e")
        b(0x04, 0x00)
        uleb(0)
        str("env")
        str("h")
        b(0x00)
        uleb(1)
    }
    section(3) {
        uleb(1)
        uleb(1)
    }
    section(7) {
        uleb(1)
        str("caught")
        b(0x00)
        uleb(1)
    }
    section(10) {
        uleb(1)
        body {
            b(0x02, I32, 0x1f, I32, 0x01, 0x00, 0x00, 0x00, 0x20, 0, 0x10, 0, 0x0b, 0x0f, 0x0b, 0x20, 0, 0x6a)
        }
    }
}
