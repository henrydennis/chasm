package io.github.charlietap.chasm.compiler.backend.analysis

import io.github.charlietap.chasm.cir.CirFunction
import io.github.charlietap.chasm.cir.ExceptionRegion

/**
 * Computes the frame slots live across any suspendable call instruction, accounting for
 * fallthrough, branch targets, loops, and enclosing exception handlers (minus catch payload writes).
 */
class SuspendLiveness(
    private val function: CirFunction,
    private val table: InstructionTable,
    private val frameSlots: Int,
) {
    private val size = function.instructions.size
    private val words = (frameSlots + 63) / 64
    private val regions: List<ExceptionRegion> = function.exceptions?.regions.orEmpty()
    private val innermost: IntArray = if (regions.isEmpty()) IntArray(0) else innermostRegions(regions, size)
    private val parentRegion: IntArray = computeParents(regions)

    private val blocks: BasicBlocks
    private val successorStart: IntArray
    private val successors: IntArray
    private val liveIn: LongArray

    init {
        if (size == 0 || frameSlots == 0) {
            blocks = BasicBlocks(IntArray(0), IntArray(0))
            successorStart = IntArray(1)
            successors = IntArray(0)
            liveIn = LongArray(0)
        } else {
            val jumps = table.jumps
            val isLeader = BooleanArray(size + 1)
            isLeader[0] = true
            for (edge in 0 until jumps.count) {
                val target = jumps.targets[edge]
                if (target < size) isLeader[target] = true
            }
            for (region in regions) {
                for (handler in region.catches) {
                    val target = handler.target.index
                    if (target < size) isLeader[target] = true
                }
            }
            for (position in 0 until size) {
                val jumping = jumps.end(position) > jumps.start[position]
                if (position + 1 < size && (!jumps.fallsThrough[position] || jumping)) {
                    isLeader[position + 1] = true
                }
            }

            blocks = basicBlocks(isLeader, size)
            val count = blocks.leaders.size
            val blockAt = blocks.blockAt
            val succStart = IntArray(count + 1)
            var succs = IntArray(maxOf(count * 2, 4))
            var edges = 0
            for (block in 0 until count) {
                val last = blocks.end(block) - 1
                val targets = jumps.end(last) - jumps.start[last]
                if (edges + targets + 2 > succs.size) {
                    succs = succs.copyOf(maxOf(succs.size * 2, edges + targets + 2))
                }
                if (jumps.fallsThrough[last] && last + 1 < size) {
                    succs[edges++] = blockAt[last + 1]
                }
                for (edge in jumps.start[last] until jumps.end(last)) {
                    val target = jumps.targets[edge]
                    if (target < size) {
                        succs[edges++] = blockAt[target]
                    }
                }
                succStart[block + 1] = edges
            }
            successorStart = succStart
            successors = succs
            liveIn = LongArray(count * words)
            solve(count)
        }
    }

    /**
     * Returns the sorted frame slot indices that must be preserved across a suspendable call at [position].
     */
    fun liveAfterCall(position: Int): IntArray {
        if (frameSlots == 0 || position !in 0 until size) return EMPTY_SLOTS
        val bits = LongArray(words)
        val block = blocks.blockAt[position]
        val end = blocks.end(block)
        val scratch = LongArray(words)

        liveOut(block, bits)
        for (index in end - 1 downTo position + 1) {
            transfer(index, bits, scratch)
        }

        // The call's own results will be written by afterInvoke on normal resumption.
        clearUnconditionalWrites(position, bits)

        // If the resumed call throws, afterInvoke jumps to an enclosing handler without writing results.
        addHandlerLiveIn(position, bits, scratch)

        return extractSlots(bits)
    }

    private fun solve(count: Int) {
        val scratch = LongArray(words)
        val handlerScratch = LongArray(words)
        var changed = true
        while (changed) {
            changed = false
            for (block in count - 1 downTo 0) {
                liveOut(block, scratch)
                val start = blocks.leaders[block]
                for (position in blocks.end(block) - 1 downTo start) {
                    transfer(position, scratch, handlerScratch)
                }
                val offset = block * words
                for (word in 0 until words) {
                    if (scratch[word] != liveIn[offset + word]) {
                        liveIn[offset + word] = scratch[word]
                        changed = true
                    }
                }
            }
        }
    }

    private fun liveOut(
        block: Int,
        into: LongArray,
    ) {
        into.fill(0L)
        for (edge in successorStart[block] until successorStart[block + 1]) {
            liveIn.orInto(into, words, successors[edge] * words)
        }
    }

    private fun transfer(
        position: Int,
        bits: LongArray,
        handlerScratch: LongArray,
    ) {
        clearUnconditionalWrites(position, bits)
        addHandlerLiveIn(position, bits, handlerScratch)
        val reads = table.reads
        for (index in reads.start[position] until reads.end(position)) {
            bits.setBit(reads.slots[index])
        }
        if (reads.unknown?.get(position) == true) {
            setAllSlots(bits)
        }
    }

    private fun clearUnconditionalWrites(
        position: Int,
        bits: LongArray,
    ) {
        val writes = table.writes
        if (writes.modes[position] != SlotWriteTable.CONDITIONAL) {
            for (index in writes.start[position] until writes.end(position)) {
                bits.clearBit(writes.slots[index])
            }
        }
    }

    private fun addHandlerLiveIn(
        position: Int,
        bits: LongArray,
        scratch: LongArray,
    ) {
        if (regions.isEmpty()) return
        var regionIndex = innermost[position]
        while (regionIndex >= 0) {
            val region = regions[regionIndex]
            for (handler in region.catches) {
                val target = handler.target.index
                if (target < size) {
                    val offset = blocks.blockAt[target] * words
                    for (word in 0 until words) scratch[word] = liveIn[offset + word]
                    for (payloadSlot in handler.payloadSlots) {
                        if (payloadSlot in 0 until frameSlots) {
                            scratch.clearBit(payloadSlot)
                        }
                    }
                    scratch.orInto(bits, words)
                }
            }
            regionIndex = parentRegion[regionIndex]
        }
    }

    private fun setAllSlots(bits: LongArray) {
        for (word in 0 until frameSlots / 64) bits[word] = -1L
        if (frameSlots % 64 != 0) {
            bits[frameSlots / 64] = bits[frameSlots / 64] or ((1L shl (frameSlots % 64)) - 1)
        }
    }

    private fun extractSlots(bits: LongArray): IntArray {
        var count = 0
        for (word in bits) count += word.countOneBits()
        if (count == 0) return EMPTY_SLOTS
        val result = IntArray(count)
        var out = 0
        for (wordIndex in bits.indices) {
            var remaining = bits[wordIndex]
            while (remaining != 0L) {
                val bit = remaining.countTrailingZeroBits()
                val slot = wordIndex * 64 + bit
                if (slot < frameSlots) {
                    result[out++] = slot
                }
                remaining = remaining and (remaining - 1)
            }
        }
        return if (out == count) result else result.copyOf(out)
    }

    private companion object {
        val EMPTY_SLOTS = IntArray(0)

        fun computeParents(regions: List<ExceptionRegion>): IntArray {
            val parents = IntArray(regions.size) { -1 }
            val stack = IntArray(regions.size)
            var depth = 0
            for (index in regions.indices) {
                val region = regions[index]
                while (depth > 0 && regions[stack[depth - 1]].end.index <= region.start.index) {
                    depth--
                }
                if (depth > 0) {
                    parents[index] = stack[depth - 1]
                }
                stack[depth++] = index
            }
            return parents
        }
    }
}
