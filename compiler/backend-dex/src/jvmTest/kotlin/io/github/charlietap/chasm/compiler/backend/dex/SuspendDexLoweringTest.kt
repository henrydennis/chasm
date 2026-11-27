@file:OptIn(InternalChasmApi::class)

package io.github.charlietap.chasm.compiler.backend.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import io.github.charlietap.chasm.InternalChasmApi
import io.github.charlietap.chasm.config.RuntimeConfig
import io.github.charlietap.chasm.runtime.compiled.CHUNK_METHOD_SEPARATOR
import kotlin.test.Test
import kotlin.test.assertTrue

class SuspendDexLoweringTest {

    @Test
    fun `chunked suspendable DEX functions emit chunk and dispatcher suspension machinery`() {
        val scenarios = listOf(
            "Loop" to buildLoopWasm(),
            "TryTable" to buildTryTableWasm(),
            "Mixed" to buildMixedWasm(),
        )

        for ((name, wasm) in scenarios) {
            val generated = generate(
                decodeModule(wasm),
                DexOptions(
                    entryClass = "test.dex.chunked.$name",
                    runtime = RuntimeConfig(suspendable = true),
                    splitInstructions = 3,
                ),
            )
            val methods = generated.dex
                .flatMap { bytes -> DexBackedDexFile(Opcodes.forApi(MIN_API), bytes).classes }
                .flatMap { clazz -> clazz.methods }

            val chunks = methods.filter { method -> CHUNK_METHOD_SEPARATOR in method.name }
            assertTrue(chunks.isNotEmpty(), "$name expected chunked DEX methods")

            val dispatchers = methods.filter { method ->
                val base = method.name
                chunks.any { chunk -> chunk.name.startsWith("$base$CHUNK_METHOD_SEPARATOR") }
            }
            assertTrue(dispatchers.isNotEmpty(), "$name expected dispatcher methods")

            assertTrue(
                chunks.any { method -> callsMethod(method, "pushSuspendedFrame") && callsMethod(method, "popSuspendedFrame") },
                "$name expected chunk suspend/resume calls",
            )
            assertTrue(
                dispatchers.any { method ->
                    callsMethod(method, "pushSuspendedFrame") &&
                        callsMethod(method, "popSuspendedFrame") &&
                        callsMethod(method, "openFrame") &&
                        callsMethod(method, "closeFrame")
                },
                "$name expected dispatcher suspend/resume frame save/restore",
            )
        }
    }

    @Test
    fun `liveness-pruned DEX spilling saves only live slots and avoids temporary register inflation`() {
        val wasm = buildDeadLocalsWasm()
        val module = decodeModule(wasm)

        val plain = generate(module, DexOptions(entryClass = "test.dex.live.Plain"))
        val suspendable = generate(
            module,
            DexOptions(entryClass = "test.dex.live.Suspend", runtime = RuntimeConfig(suspendable = true)),
        )

        val plainF1 = findMethod(plain.dex, "f1")
        val suspendF1 = findMethod(suspendable.dex, "f1")

        val plainRegs = plainF1.implementation!!.registerCount
        val suspendRegs = suspendF1.implementation!!.registerCount
        assertTrue(
            suspendRegs <= plainRegs + 2,
            "suspendable DEX register count ($suspendRegs) should stay within +2 of plain ($plainRegs)",
        )

        // Find the const size passed to new-array [J in the suspend save path
        val insns = suspendF1.implementation!!.instructions.toList()
        val arraySizes = mutableListOf<Long>()
        for (i in insns.indices) {
            val insn = insns[i]
            if (insn.opcode == Opcode.NEW_ARRAY &&
                insn is ReferenceInstruction &&
                (insn.reference as? TypeReference)?.type == "[J"
            ) {
                val window = insns.subList(i, minOf(insns.size, i + 30))
                if (window.any { it is ReferenceInstruction && (it.reference as? MethodReference)?.name == "pushSuspendedFrame" }) {
                    val sizeReg = (insn as TwoRegisterInstruction).registerB
                    for (j in i - 1 downTo maxOf(0, i - 4)) {
                        val prev = insns[j]
                        if (prev is OneRegisterInstruction && prev is WideLiteralInstruction && prev.registerA == sizeReg) {
                            arraySizes += prev.wideLiteral
                            break
                        }
                    }
                }
            }
        }
        assertTrue(arraySizes.isNotEmpty(), "expected new-array [J before pushSuspendedFrame")
        assertTrue(arraySizes.all { size -> size <= 2L }, "expected <= 2 live numeric slots spilled, got $arraySizes")
    }

    private fun findMethod(
        dexFiles: List<ByteArray>,
        name: String,
    ) = dexFiles
        .flatMap { bytes -> DexBackedDexFile(Opcodes.forApi(MIN_API), bytes).classes }
        .flatMap { clazz -> clazz.methods }
        .single { method -> method.name == name }

    private fun callsMethod(
        method: com.android.tools.smali.dexlib2.iface.Method,
        targetName: String,
    ): Boolean {
        return method.implementation?.instructions?.any { insn ->
            insn is ReferenceInstruction && (insn.reference as? MethodReference)?.name == targetName
        } == true
    }
}

private const val I32 = 0x7F
private const val I64 = 0x7E
private const val EXTERNREF = 0x6F

private class DexTestWasmBuilder {
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
            out.add(byte.toByte())
        }
    }

    fun str(s: String) {
        val utf8 = s.encodeToByteArray()
        uleb(utf8.size)
        raw(utf8)
    }

    fun section(id: Int, block: DexTestWasmBuilder.() -> Unit) {
        val inner = DexTestWasmBuilder().apply {
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

    fun body(locals: List<Pair<Int, Int>> = emptyList(), block: DexTestWasmBuilder.() -> Unit) {
        val inner = DexTestWasmBuilder().apply {
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

private fun wasm(block: DexTestWasmBuilder.() -> Unit) = DexTestWasmBuilder().apply(block).toByteArray()

private fun buildDeadLocalsWasm(): ByteArray = wasm {
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
    section(7) {
        uleb(1)
        str("sparse")
        b(0x00)
        uleb(1)
    }
    section(10) {
        uleb(1)
        body(listOf(11 to I32)) {
            for (i in 1..10) {
                b(0x41)
                sleb(i)
                b(0x21, i)
            }
            b(0x20, 1)
            for (i in 2..10) {
                b(0x20, i, 0x6A)
            }
            b(0x21, 11)
            b(0x20, 0, 0x10, 0)
            b(0x20, 11, 0x6A)
            b(0x20, 0, 0x6A)
        }
    }
}

private fun buildLoopWasm(): ByteArray = wasm {
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
    section(7) {
        uleb(1)
        str("loop_sum")
        b(0x00)
        uleb(1)
    }
    section(10) {
        uleb(1)
        body(listOf(2 to I32)) {
            b(0x02, 0x40, 0x03, 0x40)
            b(0x20, 1, 0x20, 0, 0x4E, 0x0D, 1)
            b(0x20, 1, 0x10, 0, 0x20, 2, 0x6A, 0x21, 2)
            b(0x20, 1, 0x41, 1, 0x6A, 0x21, 1)
            b(0x0C, 0, 0x0B, 0x0B)
            b(0x20, 2)
        }
    }
}

private fun buildTryTableWasm(): ByteArray = wasm {
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
    section(7) {
        uleb(1)
        str("caught")
        b(0x00)
        uleb(1)
    }
    section(10) {
        uleb(1)
        body(listOf(1 to I32)) {
            b(0x20, 0, 0x41, 1, 0x6A, 0x21, 1)
            b(0x02, I32)
            b(0x1F, I32, 0x01, 0x00, 0x00, 0x00)
            b(0x20, 0, 0x10, 0)
            b(0x0B, 0x0F, 0x0B)
            b(0x20, 1, 0x6A)
        }
    }
}

private fun buildMixedWasm(): ByteArray = wasm {
    val sig = listOf(I32, I64, EXTERNREF)
    section(1) {
        uleb(1)
        funcType(sig, sig)
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
        str("run")
        b(0x00)
        uleb(1)
    }
    section(10) {
        uleb(1)
        body(listOf(1 to I32, 1 to I64, 1 to EXTERNREF)) {
            b(0x20, 0, 0x20, 1, 0x20, 2, 0x10, 0)
            b(0x21, 5, 0x21, 4, 0x21, 3)
            b(0x20, 3, 0x20, 0, 0x6A)
            b(0x20, 4, 0x20, 1, 0x7C)
            b(0x20, 5)
        }
    }
}
