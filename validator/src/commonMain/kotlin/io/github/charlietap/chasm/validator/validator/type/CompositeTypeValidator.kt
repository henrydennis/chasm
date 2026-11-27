package io.github.charlietap.chasm.validator.validator.type

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.binding
import io.github.charlietap.chasm.type.ArrayType
import io.github.charlietap.chasm.type.CompositeType
import io.github.charlietap.chasm.type.ConcreteHeapType
import io.github.charlietap.chasm.type.FunctionType
import io.github.charlietap.chasm.type.StructType
import io.github.charlietap.chasm.validator.CoreTypeValidator
import io.github.charlietap.chasm.validator.context.CoreTypeValidationContext
import io.github.charlietap.chasm.validator.error.ModuleValidatorError
import io.github.charlietap.chasm.validator.error.TypeValidatorError
import io.github.charlietap.chasm.validator.ext.type

internal fun CompositeTypeValidator(
    context: CoreTypeValidationContext,
    type: CompositeType,
): Result<Unit, ModuleValidatorError> =
    CompositeTypeValidator(
        context = context,
        type = type,
        arrayTypeValidator = ::ArrayTypeValidator,
        functionTypeValidator = ::FunctionTypeValidator,
        structTypeValidator = ::StructTypeValidator,
    )

internal inline fun CompositeTypeValidator(
    context: CoreTypeValidationContext,
    type: CompositeType,
    crossinline arrayTypeValidator: CoreTypeValidator<ArrayType>,
    crossinline functionTypeValidator: CoreTypeValidator<FunctionType>,
    crossinline structTypeValidator: CoreTypeValidator<StructType>,
): Result<Unit, ModuleValidatorError> = binding {
    when (type) {
        is CompositeType.Array -> arrayTypeValidator(context, type.arrayType).bind()
        is CompositeType.Function -> functionTypeValidator(context, type.functionType).bind()
        is CompositeType.Struct -> structTypeValidator(context, type.structType).bind()
        is CompositeType.Cont -> {
            val target = when (val heapType = type.functionType) {
                is ConcreteHeapType.TypeIndex -> context.type(heapType.index).bind()
                is ConcreteHeapType.Defined -> heapType.definedType
                is ConcreteHeapType.RecursiveTypeIndex -> Err(TypeValidatorError.TypeMismatch).bind()
            }
            if (target.asSubType.compositeType !is CompositeType.Function) {
                Err(TypeValidatorError.TypeMismatch).bind()
            }
        }
    }
}
