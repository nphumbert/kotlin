/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.codegen.inline

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.codegen.*
import org.jetbrains.kotlin.codegen.binding.CalculatedClosure
import org.jetbrains.kotlin.codegen.binding.CodegenBinding
import org.jetbrains.kotlin.codegen.binding.CodegenBinding.*
import org.jetbrains.kotlin.codegen.binding.MutableClosure
import org.jetbrains.kotlin.codegen.context.EnclosedValueDescriptor
import org.jetbrains.kotlin.codegen.state.KotlinTypeMapper
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCallWithAssert
import org.jetbrains.kotlin.resolve.jvm.AsmTypes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.Method
import org.jetbrains.org.objectweb.asm.tree.FieldInsnNode
import org.jetbrains.org.objectweb.asm.tree.MethodNode

abstract class LambdaInfo(@JvmField val isCrossInline: Boolean, @JvmField val isBoundCallableReference: Boolean) : LabelOwner {

    abstract val lambdaClassType: Type

    abstract val invokeMethod: Method

    abstract val invokeMethodDescriptor: FunctionDescriptor

    abstract val capturedVars: List<CapturedParamDesc>

    lateinit var node: SMAPAndMethodNode

    abstract fun generateLambdaBody(codegen: ExpressionCodegen)

    fun addAllParameters(remapper: FieldRemapper): Parameters {
        val builder = ParametersBuilder.initializeBuilderFrom(AsmTypes.OBJECT_TYPE, invokeMethod.descriptor, this)

        for (info in capturedVars) {
            val field = remapper.findField(FieldInsnNode(0, info.containingLambdaName, info.fieldName, "")) ?:
                        error("Captured field not found: " + info.containingLambdaName + "." + info.fieldName)
            builder.addCapturedParam(field, info.fieldName)
        }

        return builder.buildParameters()
    }


    companion object {
        fun LambdaInfo.getCapturedParamInfo(descriptor: EnclosedValueDescriptor): CapturedParamDesc {
            return CapturedParamDesc(lambdaClassType, descriptor.fieldName, descriptor.type)
        }
    }
}


class DefaultLambda(override val lambdaClassType: Type, lambdaArgs: Array<Type>) : LambdaInfo(false, false) {
    override val invokeMethod: Method
        get() = TODO("not implemented")

    override val invokeMethodDescriptor: FunctionDescriptor
        get() = TODO("not implemented")

    override val capturedVars: List<CapturedParamDesc>
        get() = TODO("not implemented")

    override fun isMyLabel(name: String): Boolean = false

    override fun generateLambdaBody(codegen: ExpressionCodegen) {
        TODO("not implemented")
    }
}

class ExpressionLambda(
        expression: KtExpression,
        private val typeMapper: KotlinTypeMapper,
        isCrossInline: Boolean,
        isBoundCallableReference: Boolean
) : LambdaInfo(isCrossInline, isBoundCallableReference) {

    override val lambdaClassType: Type

    override val invokeMethod: Method

    override val invokeMethodDescriptor: FunctionDescriptor

    val classDescriptor: ClassDescriptor

    val propertyReferenceInfo: PropertyReferenceInfo?

    val functionWithBodyOrCallableReference: KtExpression = (expression as? KtLambdaExpression)?.functionLiteral ?: expression

    private val labels: Set<String>

    private lateinit var closure: CalculatedClosure

    init {
        val bindingContext = typeMapper.bindingContext
        val function = bindingContext.get<PsiElement, SimpleFunctionDescriptor>(BindingContext.FUNCTION, functionWithBodyOrCallableReference)
        if (function == null && expression is KtCallableReferenceExpression) {
            val variableDescriptor = bindingContext.get<PsiElement, VariableDescriptor>(BindingContext.VARIABLE, functionWithBodyOrCallableReference)
            assert(variableDescriptor is VariableDescriptorWithAccessors) {
                """Reference expression not resolved to variable descriptor with accessors: ${expression.getText()}"""
            }
            classDescriptor = CodegenBinding.anonymousClassForCallable(bindingContext, variableDescriptor!!)
            lambdaClassType = typeMapper.mapClass(classDescriptor)
            val getFunction = PropertyReferenceCodegen.findGetFunction(variableDescriptor)
            invokeMethodDescriptor = PropertyReferenceCodegen.createFakeOpenDescriptor(getFunction, classDescriptor)
            val resolvedCall = expression.callableReference.getResolvedCallWithAssert(bindingContext)
            propertyReferenceInfo = PropertyReferenceInfo(
                    resolvedCall.resultingDescriptor as VariableDescriptor, getFunction
            )
        }
        else {
            propertyReferenceInfo = null
            assert(function != null) { "Function is not resolved to descriptor: " + expression.text }
            invokeMethodDescriptor = function!!
            classDescriptor = anonymousClassForCallable(bindingContext, invokeMethodDescriptor)
            lambdaClassType = asmTypeForAnonymousClass(bindingContext, invokeMethodDescriptor)
        }

        bindingContext.get<ClassDescriptor, MutableClosure>(CLOSURE, classDescriptor).let {
            assert(it != null) { "Closure for lambda should be not null " + expression.text }
            closure = it!!
        }

        labels = InlineCodegen.getDeclarationLabels(expression, invokeMethodDescriptor)
        invokeMethod = typeMapper.mapAsmMethod(invokeMethodDescriptor)
    }

    override val capturedVars: List<CapturedParamDesc> by lazy {
        arrayListOf<CapturedParamDesc>().apply {
            if (closure.captureThis != null) {
                val type = typeMapper.mapType(closure.captureThis!!)
                val descriptor = EnclosedValueDescriptor(
                        AsmUtil.CAPTURED_THIS_FIELD, null,
                        StackValue.field(type, lambdaClassType, AsmUtil.CAPTURED_THIS_FIELD, false, StackValue.LOCAL_0),
                        type
                )
                add(getCapturedParamInfo(descriptor))
            }

            if (closure.captureReceiverType != null) {
                val type = typeMapper.mapType(closure.captureReceiverType!!)
                val descriptor = EnclosedValueDescriptor(
                        AsmUtil.CAPTURED_RECEIVER_FIELD, null,
                        StackValue.field(type, lambdaClassType, AsmUtil.CAPTURED_RECEIVER_FIELD, false, StackValue.LOCAL_0),
                        type
                )
                add(getCapturedParamInfo(descriptor))
            }

            closure.captureVariables.values.forEach {
                descriptor ->
                add(getCapturedParamInfo(descriptor))
            }
        }
    }

    override fun isMyLabel(name: String): Boolean {
        return labels.contains(name)
    }

    val isPropertyReference: Boolean
        get() = propertyReferenceInfo != null

    override fun generateLambdaBody(codegen: ExpressionCodegen) {
        val closureContext =
                if (isPropertyReference)
                    codegen.getContext().intoAnonymousClass(classDescriptor, codegen, OwnerKind.IMPLEMENTATION)
                else
                    codegen.getContext().intoClosure(invokeMethodDescriptor, codegen, typeMapper)
        val context = closureContext.intoInlinedLambda(invokeMethodDescriptor, isCrossInline, isPropertyReference)

        val jvmMethodSignature = typeMapper.mapSignatureSkipGeneric(invokeMethodDescriptor)
        val asmMethod = jvmMethodSignature.asmMethod
        val methodNode = MethodNode(
                InlineCodegenUtil.API, AsmUtil.getMethodAsmFlags(invokeMethodDescriptor, context.contextKind, codegen.state),
                asmMethod.name, asmMethod.descriptor, null, null
        )

        node = InlineCodegenUtil.wrapWithMaxLocalCalc(methodNode).let { adapter ->
            val smap = InlineCodegen.generateMethodBody(
                    adapter, invokeMethodDescriptor, context, functionWithBodyOrCallableReference, jvmMethodSignature, codegen, this
            )
            adapter.visitMaxs(-1, -1)
            SMAPAndMethodNode(methodNode, smap)
        }
    }
}
