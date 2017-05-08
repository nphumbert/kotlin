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
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCallWithAssert
import org.jetbrains.kotlin.resolve.jvm.AsmTypes
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.Method
import org.jetbrains.org.objectweb.asm.tree.AbstractInsnNode
import org.jetbrains.org.objectweb.asm.tree.FieldInsnNode
import org.jetbrains.org.objectweb.asm.tree.MethodNode

abstract class LambdaInfo(@JvmField val isCrossInline: Boolean, @JvmField val isBoundCallableReference: Boolean) : LabelOwner {

    abstract val lambdaClassType: Type

    abstract val invokeMethod: Method

    abstract val erasedInvokeMethodDescriptor: FunctionDescriptor

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
            return capturedParamDesc(descriptor.fieldName, descriptor.type)
        }

        fun LambdaInfo.capturedParamDesc(fieldName: String, fieldType: Type): CapturedParamDesc {
            return CapturedParamDesc(lambdaClassType, fieldName, fieldType)
        }
    }
}


class DefaultLambda(
        override val lambdaClassType: Type,
        val capturedArgs: Array<Type>,
        val parameterDescriptor: ValueParameterDescriptor,
        val initInstuctions: List<AbstractInsnNode>,
        val offset: Int
) : LambdaInfo(parameterDescriptor.isCrossinline, false) {

    override lateinit var invokeMethod: Method
        private set

    override val erasedInvokeMethodDescriptor: FunctionDescriptor =
            parameterDescriptor.type.memberScope.getContributedFunctions(OperatorNameConventions.INVOKE, NoLookupLocation.FROM_BACKEND).single()

    override lateinit var capturedVars: List<CapturedParamDesc>
        private set

    override fun isMyLabel(name: String): Boolean = false

    override fun generateLambdaBody(codegen: ExpressionCodegen) {
        val classReader = InlineCodegenUtil.buildClassReaderByInternalName(codegen.state, lambdaClassType.internalName)

        val descriptor = Type.getMethodDescriptor(Type.VOID_TYPE, *capturedArgs)
        val constructor = InlineCodegenUtil.getMethodNode(
                classReader.b,
                "<init>",
                descriptor,
                lambdaClassType.internalName)?.node

        assert(constructor != null || capturedArgs.isEmpty()) {
            "Can't find non-default constructor <init>$descriptor for default lambda $lambdaClassType"
        }

        capturedVars = constructor?.findCapturedFieldAssignmentInstructions()?.map {
            fieldNode ->
            capturedParamDesc(fieldNode.name, Type.getType(fieldNode.desc))
        }?.toList() ?: emptyList()

        //TODO more wise invoke extraction
        val invokes = arrayListOf<String>()
        val INVOKE_NAME = OperatorNameConventions.INVOKE.asString()
        classReader.accept(object : ClassVisitor(InlineCodegenUtil.API) {
            override fun visitMethod(access: Int, name: String, desc: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                if (INVOKE_NAME == name) {
                    invokes.add(desc)
                }
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.EXPAND_FRAMES)

        val invokeDesc = when (invokes.size) {
            0 -> error("Can't find invoke method in $lambdaClassType")
            1 -> invokes.single()
            2 -> {
                val erasedInvoke = codegen.state.typeMapper.mapSignatureSkipGeneric(erasedInvokeMethodDescriptor).asmMethod.descriptor
                invokes.filter { it != erasedInvoke }.let {
                    assert(it.size == 1) {
                        "There are to many invoke methods in class $lambdaClassType, invoke descriptors: " + invokes.joinToString()
                    }
                    it.single()
                }
            }
            else -> error("There are to many invoke methods in class $lambdaClassType, invoke descriptors: " + invokes.joinToString())
        }

        invokeMethod = Method(INVOKE_NAME, invokeDesc)
        node = InlineCodegenUtil.getMethodNode(
                classReader.b,
                INVOKE_NAME,
                invokeDesc,
                lambdaClassType.internalName)!!
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

    val invokeMethodDescriptor: FunctionDescriptor

    override val erasedInvokeMethodDescriptor: FunctionDescriptor

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
        erasedInvokeMethodDescriptor = ClosureCodegen.getErasedInvokeFunction(invokeMethodDescriptor)
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
