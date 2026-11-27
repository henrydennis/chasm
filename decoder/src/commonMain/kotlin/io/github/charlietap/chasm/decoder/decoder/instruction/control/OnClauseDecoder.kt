package io.github.charlietap.chasm.decoder.decoder.instruction.control

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.binding
import io.github.charlietap.chasm.ast.instruction.ControlInstruction
import io.github.charlietap.chasm.ast.module.Index
import io.github.charlietap.chasm.decoder.context.CodeBodyDecoderContext
import io.github.charlietap.chasm.decoder.decoder.CodeBodyDecoder
import io.github.charlietap.chasm.decoder.decoder.section.index.LabelIndexDecoder
import io.github.charlietap.chasm.decoder.decoder.section.index.TagIndexDecoder
import io.github.charlietap.chasm.decoder.error.InstructionDecodeError
import io.github.charlietap.chasm.decoder.error.WasmDecodeError

internal fun OnClauseDecoder(
    context: CodeBodyDecoderContext,
): Result<ControlInstruction.OnClause, WasmDecodeError> = OnClauseDecoder(
    context = context,
    labelIndexDecoder = ::LabelIndexDecoder,
    tagIndexDecoder = ::TagIndexDecoder,
)

internal inline fun OnClauseDecoder(
    context: CodeBodyDecoderContext,
    crossinline labelIndexDecoder: CodeBodyDecoder<Index.LabelIndex>,
    crossinline tagIndexDecoder: CodeBodyDecoder<Index.TagIndex>,
): Result<ControlInstruction.OnClause, WasmDecodeError> = binding {
    when (val clause = context.reader.ubyte()) {
        ON_CLAUSE_LABEL -> {
            val tagIndex = tagIndexDecoder(context).bind()
            val labelIndex = labelIndexDecoder(context).bind()
            ControlInstruction.OnClause.OnLabel(tagIndex, labelIndex)
        }
        ON_CLAUSE_SWITCH -> {
            val tagIndex = tagIndexDecoder(context).bind()
            ControlInstruction.OnClause.OnSwitch(tagIndex)
        }
        else -> Err(InstructionDecodeError.InvalidOnClause(clause)).bind()
    }
}

internal const val ON_CLAUSE_LABEL: UByte = 0x00u
internal const val ON_CLAUSE_SWITCH: UByte = 0x01u
