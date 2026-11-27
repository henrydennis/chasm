package io.github.charlietap.chasm.validator.validator.instruction.control

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.binding
import io.github.charlietap.chasm.ast.instruction.ControlInstruction
import io.github.charlietap.chasm.ast.module.Index
import io.github.charlietap.chasm.type.AbstractHeapType
import io.github.charlietap.chasm.type.CompositeType
import io.github.charlietap.chasm.type.ConcreteHeapType
import io.github.charlietap.chasm.type.DefinedType
import io.github.charlietap.chasm.type.FunctionType
import io.github.charlietap.chasm.type.HeapType
import io.github.charlietap.chasm.type.ReferenceType
import io.github.charlietap.chasm.type.ResultType
import io.github.charlietap.chasm.type.ValueType
import io.github.charlietap.chasm.type.matching.FunctionTypeMatcher
import io.github.charlietap.chasm.type.matching.ResultTypeMatcher
import io.github.charlietap.chasm.type.matching.ValueTypeMatcher
import io.github.charlietap.chasm.validator.context.ModuleValidationContext
import io.github.charlietap.chasm.validator.error.ModuleValidatorError
import io.github.charlietap.chasm.validator.error.TagValidatorError
import io.github.charlietap.chasm.validator.error.TypeValidatorError
import io.github.charlietap.chasm.validator.ext.branchValues
import io.github.charlietap.chasm.validator.ext.peek
import io.github.charlietap.chasm.validator.ext.popValues
import io.github.charlietap.chasm.validator.ext.pushValues
import io.github.charlietap.chasm.validator.ext.tagType
import io.github.charlietap.chasm.validator.ext.type

private data class ResolvedContinuationType(
    val contDefinedType: DefinedType,
    val funcDefinedType: DefinedType,
    val functionType: FunctionType,
)

private fun resolveContinuationType(
    context: ModuleValidationContext,
    typeIndex: Index.TypeIndex,
): Result<ResolvedContinuationType, ModuleValidatorError> = binding {
    val contDefinedType = context.type(typeIndex).bind()
    resolveContinuationDefinedType(context, contDefinedType).bind()
}

private fun resolveContinuationHeapType(
    context: ModuleValidationContext,
    heapType: HeapType,
): Result<ResolvedContinuationType, ModuleValidatorError> = binding {
    val contDefinedType = when (heapType) {
        is ConcreteHeapType.Defined -> heapType.definedType
        is ConcreteHeapType.TypeIndex -> context.type(heapType.index).bind()
        else -> Err(TypeValidatorError.TypeMismatch).bind()
    }
    resolveContinuationDefinedType(context, contDefinedType).bind()
}

private fun resolveContinuationDefinedType(
    context: ModuleValidationContext,
    contDefinedType: DefinedType,
): Result<ResolvedContinuationType, ModuleValidatorError> = binding {
    val contComposite = contDefinedType.asSubType.compositeType as? CompositeType.Cont
        ?: Err(TypeValidatorError.TypeMismatch).bind()
    val funcDefinedType = when (val funcHeap = contComposite.functionType) {
        is ConcreteHeapType.Defined -> funcHeap.definedType
        is ConcreteHeapType.TypeIndex -> context.type(funcHeap.index).bind()
        is ConcreteHeapType.RecursiveTypeIndex -> Err(TypeValidatorError.TypeMismatch).bind()
    }
    val funcComposite = funcDefinedType.asSubType.compositeType as? CompositeType.Function
        ?: Err(TypeValidatorError.TypeMismatch).bind()
    ResolvedContinuationType(contDefinedType, funcDefinedType, funcComposite.functionType)
}

internal fun ContNewInstructionValidator(
    context: ModuleValidationContext,
    instruction: ControlInstruction.ContNew,
): Result<Unit, ModuleValidatorError> = binding {
    val resolved = resolveContinuationType(context, instruction.typeIndex).bind()
    val funcRef = ValueType.Reference(ReferenceType.RefNull(ConcreteHeapType.Defined(resolved.funcDefinedType)))
    val contRef = ValueType.Reference(ReferenceType.Ref(ConcreteHeapType.Defined(resolved.contDefinedType)))
    context.popValues(listOf(funcRef)).bind()
    context.pushValues(listOf(contRef))
}

internal fun ContBindInstructionValidator(
    context: ModuleValidationContext,
    instruction: ControlInstruction.ContBind,
): Result<Unit, ModuleValidatorError> = binding {
    val input = resolveContinuationType(context, instruction.inputTypeIndex).bind()
    val output = resolveContinuationType(context, instruction.outputTypeIndex).bind()
    val ts1 = input.functionType.params.types
    val ts1Prime = output.functionType.params.types
    if (ts1.size < ts1Prime.size) {
        Err(TypeValidatorError.TypeMismatch).bind()
    }
    val splitAt = ts1.size - ts1Prime.size
    val ts11 = ts1.subList(0, splitAt)
    val ts12 = ts1.subList(splitAt, ts1.size)
    val remainingType = FunctionType(ResultType(ts12), input.functionType.results)
    if (!FunctionTypeMatcher(remainingType, output.functionType, context)) {
        Err(TypeValidatorError.TypeMismatch).bind()
    }
    val inputContRef = ValueType.Reference(ReferenceType.RefNull(ConcreteHeapType.Defined(input.contDefinedType)))
    val outputContRef = ValueType.Reference(ReferenceType.Ref(ConcreteHeapType.Defined(output.contDefinedType)))
    context.popValues(ts11 + inputContRef).bind()
    context.pushValues(listOf(outputContRef))
}

internal fun SuspendInstructionValidator(
    context: ModuleValidationContext,
    instruction: ControlInstruction.Suspend,
): Result<Unit, ModuleValidatorError> = binding {
    val tagType = context.tagType(instruction.tagIndex).bind()
    val functionType = tagType.functionType
    context.popValues(functionType.params.types).bind()
    context.pushValues(functionType.results.types)
}

internal fun ResumeInstructionValidator(
    context: ModuleValidationContext,
    instruction: ControlInstruction.Resume,
): Result<Unit, ModuleValidatorError> = binding {
    val resolved = resolveContinuationType(context, instruction.typeIndex).bind()
    validateOnClauses(context, resolved.functionType.results, instruction.handlers).bind()
    val contRef = ValueType.Reference(ReferenceType.RefNull(ConcreteHeapType.Defined(resolved.contDefinedType)))
    context.popValues(resolved.functionType.params.types + contRef).bind()
    context.pushValues(resolved.functionType.results.types)
}

internal fun ResumeThrowInstructionValidator(
    context: ModuleValidationContext,
    instruction: ControlInstruction.ResumeThrow,
): Result<Unit, ModuleValidatorError> = binding {
    val resolved = resolveContinuationType(context, instruction.typeIndex).bind()
    val tagType = context.tagType(instruction.tagIndex).bind()
    if (tagType.functionType.results.types.isNotEmpty()) {
        Err(TagValidatorError.InvalidTagType).bind()
    }
    validateOnClauses(context, resolved.functionType.results, instruction.handlers).bind()
    val contRef = ValueType.Reference(ReferenceType.RefNull(ConcreteHeapType.Defined(resolved.contDefinedType)))
    context.popValues(tagType.functionType.params.types + contRef).bind()
    context.pushValues(resolved.functionType.results.types)
}

internal fun ResumeThrowRefInstructionValidator(
    context: ModuleValidationContext,
    instruction: ControlInstruction.ResumeThrowRef,
): Result<Unit, ModuleValidatorError> = binding {
    val resolved = resolveContinuationType(context, instruction.typeIndex).bind()
    validateOnClauses(context, resolved.functionType.results, instruction.handlers).bind()
    val exnRef = ValueType.Reference(ReferenceType.RefNull(AbstractHeapType.Exception))
    val contRef = ValueType.Reference(ReferenceType.RefNull(ConcreteHeapType.Defined(resolved.contDefinedType)))
    context.popValues(listOf(exnRef, contRef)).bind()
    context.pushValues(resolved.functionType.results.types)
}

internal fun SwitchInstructionValidator(
    context: ModuleValidationContext,
    instruction: ControlInstruction.Switch,
): Result<Unit, ModuleValidatorError> = binding {
    val target = resolveContinuationType(context, instruction.typeIndex).bind()
    val ts11 = target.functionType.params.types
    val ts12 = target.functionType.results
    val lastParam = ts11.lastOrNull() as? ValueType.Reference
        ?: Err(TypeValidatorError.TypeMismatch).bind()
    val current = resolveContinuationHeapType(context, lastParam.referenceType.heapType).bind()
    val tagType = context.tagType(instruction.tagIndex).bind()
    if (tagType.functionType.params.types.isNotEmpty()) {
        Err(TypeValidatorError.TypeMismatch).bind()
    }
    val tagResults = tagType.functionType.results
    if (!ResultTypeMatcher(ts12, tagResults, context) || !ResultTypeMatcher(tagResults, current.functionType.results, context)) {
        Err(TypeValidatorError.TypeMismatch).bind()
    }
    val targetContRef = ValueType.Reference(ReferenceType.RefNull(ConcreteHeapType.Defined(target.contDefinedType)))
    context.popValues(ts11.dropLast(1) + targetContRef).bind()
    context.pushValues(current.functionType.params.types)
}

private fun validateOnClauses(
    context: ModuleValidationContext,
    contResults: ResultType,
    handlers: List<ControlInstruction.OnClause>,
): Result<Unit, ModuleValidatorError> = binding {
    handlers.forEach { clause ->
        when (clause) {
            is ControlInstruction.OnClause.OnLabel -> {
                val tagFunctionType = context.tagType(clause.tagIndex).bind().functionType
                val label = context.labels.peek(clause.labelIndex).bind()
                val labelTypes = label.branchValues.types
                val lastLabelType = labelTypes.lastOrNull() as? ValueType.Reference
                    ?: Err(TypeValidatorError.TypeMismatch).bind()
                val handlerCont = resolveContinuationHeapType(context, lastLabelType.referenceType.heapType).bind()
                val expectedContFunc = FunctionType(tagFunctionType.results, contResults)
                if (!FunctionTypeMatcher(expectedContFunc, handlerCont.functionType, context)) {
                    Err(TypeValidatorError.TypeMismatch).bind()
                }
                val delivered = tagFunctionType.params.types + ValueType.Reference(
                    ReferenceType.Ref(ConcreteHeapType.Defined(handlerCont.contDefinedType)),
                )
                if (delivered.size != labelTypes.size) {
                    Err(TypeValidatorError.TypeMismatch).bind()
                }
                for (index in delivered.indices) {
                    if (!ValueTypeMatcher(delivered[index], labelTypes[index], context)) {
                        Err(TypeValidatorError.TypeMismatch).bind()
                    }
                }
            }
            is ControlInstruction.OnClause.OnSwitch -> {
                val tagFunctionType = context.tagType(clause.tagIndex).bind().functionType
                if (tagFunctionType.params.types.isNotEmpty()) {
                    Err(TypeValidatorError.TypeMismatch).bind()
                }
            }
        }
    }
}
