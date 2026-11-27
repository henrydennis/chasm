package io.github.charlietap.chasm.cir

sealed class ControlInstruction : Instruction() {

    @CirTag("control.unreachable")
    data object Unreachable : ControlInstruction() {
        init {
            opcode = 672
        }
    }

    @CirTag("control.call")
    data class Call(
        val function: FunctionIndex,
        val operands: OperandTransfer,
        val callFrameOffset: Int,
        val resultDestinationSlot: Int?,
    ) : ControlInstruction() {
        init {
            opcode = 673
        }
    }

    @CirTag("control.return_call")
    data class ReturnCall(
        val function: FunctionIndex,
        val operands: TailCallOperandTransfer,
        val callFrameOffset: Int,
        val callerActivationHeaderSlot: Int,
    ) : ControlInstruction() {
        init {
            opcode = 674
        }
    }

    @CirTag("control.call_indirect.i")
    data class CallIndirectI(
        val elementIndex: Int,
        val operands: OperandTransfer,
        val type: TypeIndex,
        val table: TableIndex,
        val callFrameOffset: Int,
        val resultDestinationSlot: Int?,
    ) : ControlInstruction() {
        init {
            opcode = 675
        }
    }

    @CirTag("control.call_indirect.s")
    data class CallIndirectS(
        val elementIndexSlot: Int,
        val operands: OperandTransfer,
        val type: TypeIndex,
        val table: TableIndex,
        val callFrameOffset: Int,
        val resultDestinationSlot: Int?,
    ) : ControlInstruction() {
        init {
            opcode = 676
        }
    }

    @CirTag("control.call_ref")
    data class CallRefS(
        val functionSlot: Int,
        val operands: OperandTransfer,
        val type: TypeIndex,
        val callFrameOffset: Int,
        val resultDestinationSlot: Int?,
    ) : ControlInstruction() {
        init {
            opcode = 677
        }
    }

    @CirTag("control.return_call_indirect.i")
    data class ReturnCallIndirectI(
        val elementIndex: Int,
        val operands: TailCallOperandTransfer,
        val type: TypeIndex,
        val table: TableIndex,
        val callFrameOffset: Int,
        val callerActivationHeaderSlot: Int,
    ) : ControlInstruction() {
        init {
            opcode = 678
        }
    }

    @CirTag("control.return_call_indirect.s")
    data class ReturnCallIndirectS(
        val elementIndexSlot: Int,
        val operands: TailCallOperandTransfer,
        val type: TypeIndex,
        val table: TableIndex,
        val callFrameOffset: Int,
        val callerActivationHeaderSlot: Int,
    ) : ControlInstruction() {
        init {
            opcode = 679
        }
    }

    @CirTag("control.return_call_ref")
    data class ReturnCallRefS(
        val functionSlot: Int,
        val operands: TailCallOperandTransfer,
        val type: TypeIndex,
        val callFrameOffset: Int,
        val callerActivationHeaderSlot: Int,
    ) : ControlInstruction() {
        init {
            opcode = 680
        }
    }

    @CirTag("control.return")
    data class FunctionReturn(
        val results: OperandTransfer,
        val activationHeaderSlot: Int,
    ) : ControlInstruction() {
        init {
            opcode = 681
        }
    }

    @CirTag("control.throw")
    data class Throw(
        val exceptionTag: TagIndex,
        val firstPayloadSlot: Int,
    ) : ControlInstruction() {
        init {
            opcode = 682
        }
    }

    @CirTag("control.throw_ref")
    data class ThrowRefS(
        val exceptionSlot: Int,
    ) : ControlInstruction() {
        init {
            opcode = 683
        }
    }

    sealed interface ResumeHandler {

        val tagIndex: TagIndex

        @CirTag("on_label")
        class OnLabel(
            override val tagIndex: TagIndex,
            val targetOffset: Int,
            val targetSlots: IntArray,
            val stackSlotCount: Int,
        ) : ResumeHandler {
            override fun equals(other: Any?): Boolean =
                this === other || (
                    other is OnLabel &&
                        tagIndex == other.tagIndex &&
                        targetOffset == other.targetOffset &&
                        targetSlots.contentEquals(other.targetSlots) &&
                        stackSlotCount == other.stackSlotCount
                )

            override fun hashCode(): Int {
                var result = tagIndex.hashCode()
                result = 31 * result + targetOffset
                result = 31 * result + targetSlots.contentHashCode()
                result = 31 * result + stackSlotCount
                return result
            }
        }

        @CirTag("on_switch")
        data class OnSwitch(
            override val tagIndex: TagIndex,
        ) : ResumeHandler
    }

    @CirTag("control.cont_new")
    data class ContNew(
        val type: TypeIndex,
        val functionSlot: Int,
        val destinationSlot: Int,
    ) : ControlInstruction() {
        init {
            opcode = 708
        }
    }

    @CirTag("control.cont_bind")
    data class ContBind(
        val inputType: TypeIndex,
        val outputType: TypeIndex,
        val continuationSlot: Int,
        val firstArgumentSlot: Int,
        val argumentCount: Int,
        val destinationSlot: Int,
    ) : ControlInstruction() {
        init {
            opcode = 709
        }
    }

    @CirTag("control.suspend")
    data class Suspend(
        val tagIndex: TagIndex,
        val firstPayloadSlot: Int,
        val payloadCount: Int,
        val firstResultSlot: Int,
        val resultCount: Int,
    ) : ControlInstruction() {
        init {
            opcode = 710
        }
    }

    @CirTag("control.resume")
    data class Resume(
        val type: TypeIndex,
        val continuationSlot: Int,
        val firstArgumentSlot: Int,
        val argumentCount: Int,
        val firstResultSlot: Int,
        val resultCount: Int,
        val handlers: List<ResumeHandler>,
    ) : ControlInstruction() {
        init {
            opcode = 711
        }
    }

    @CirTag("control.resume_throw")
    data class ResumeThrow(
        val type: TypeIndex,
        val exceptionTag: TagIndex,
        val continuationSlot: Int,
        val firstPayloadSlot: Int,
        val payloadCount: Int,
        val firstResultSlot: Int,
        val resultCount: Int,
        val handlers: List<ResumeHandler>,
    ) : ControlInstruction() {
        init {
            opcode = 712
        }
    }

    @CirTag("control.resume_throw_ref")
    data class ResumeThrowRef(
        val type: TypeIndex,
        val continuationSlot: Int,
        val exceptionSlot: Int,
        val firstResultSlot: Int,
        val resultCount: Int,
        val handlers: List<ResumeHandler>,
    ) : ControlInstruction() {
        init {
            opcode = 713
        }
    }

    @CirTag("control.switch")
    data class Switch(
        val type: TypeIndex,
        val tagIndex: TagIndex,
        val continuationSlot: Int,
        val firstArgumentSlot: Int,
        val argumentCount: Int,
        val firstResultSlot: Int,
        val resultCount: Int,
    ) : ControlInstruction() {
        init {
            opcode = 714
        }
    }
}
