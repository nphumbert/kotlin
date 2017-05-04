/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.caches.resolve.lightClasses


import com.intellij.psi.*
import com.intellij.psi.impl.InheritanceImplUtil
import com.intellij.psi.impl.PsiClassImplUtil
import com.intellij.psi.impl.PsiSubstitutorImpl
import com.intellij.psi.impl.PsiSubstitutorImpl.createSubstitutor
import com.intellij.psi.impl.PsiSuperMethodImplUtil
import com.intellij.psi.impl.light.*
import com.intellij.psi.impl.source.ClassInnerStuffCache
import com.intellij.psi.impl.source.PsiExtensibleClass
import com.intellij.psi.impl.source.PsiImmediateClassType
import com.intellij.psi.util.MethodSignatureBackedByPsiMethod
import com.siyeh.ig.psiutils.TypeUtils
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.load.java.BuiltinMethodsWithSpecialGenericSignature
import org.jetbrains.kotlin.load.java.BuiltinSpecialProperties
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.JavaToKotlinClassMap
import org.jetbrains.kotlin.platform.JavaToKotlinClassMap.PlatformMutabilityMapping
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

private val readOnlyQualifiedNamesToJavaClass = JavaToKotlinClassMap.mutabilityMappings.associateBy {
    (_, readOnly, _) ->
    readOnly.asSingleFqName()
}

private val mutableQualifiedNamesToJavaClass = JavaToKotlinClassMap.mutabilityMappings.associateBy {
    (_, _, mutable) ->
    mutable.asSingleFqName()
}

private val membersWithSpecializedSignature: Set<String> =
        BuiltinMethodsWithSpecialGenericSignature.ERASED_VALUE_PARAMETERS_SIGNATURES.mapTo(LinkedHashSet()) {
            val fqNameString = it.substringBefore('(').replace('/', '.')
            FqName(fqNameString).shortName().asString()
        }

private val javaGetterNameToKotlinGetterName: Map<String, String> = BuiltinSpecialProperties.PROPERTY_FQ_NAME_TO_JVM_GETTER_NAME_MAP.map {
    (propertyFqName, javaGetterShortName) ->
    Pair(javaGetterShortName.asString(), JvmAbi.getterName(propertyFqName.shortName().asString()))
}.toMap()

fun platformMutabilityWrapper(fqName: FqName, findJavaClass: (String) -> PsiClass?): PsiClass? {
    readOnlyQualifiedNamesToJavaClass.get(fqName)?.let {
        mapping ->
        val javaBaseClass = findJavaClass(mapping.javaClass.asSingleFqName().asString()) ?: return null
        return KtLightReadOnlyPlatformReadOnlyClassWrapper(javaBaseClass, mapping)
    }
    mutableQualifiedNamesToJavaClass.get(fqName)?.let {
        // return mutableWrapper
    }
    return null
}

class KtLightReadOnlyPlatformReadOnlyClassWrapper(
        private val javaBaseClass: PsiClass,
        private val mapping: PlatformMutabilityMapping
) : KtAbstractContainerWrapper(mapping.kotlinReadOnly.asSingleFqName(), javaBaseClass), PsiClass {
    private val _methods by lazyPub { calcMethods() }

    override fun getOwnMethods() = _methods

    private fun calcMethods(): List<PsiMethod> {
        val builtIns = DefaultBuiltIns.Instance
        val readOnlyClass = builtIns.getBuiltInClassByFqName(mapping.kotlinReadOnly.asSingleFqName())
        return javaBaseClass.methods.flatMap { method ->
            val methodName = method.name

            javaGetterNameToKotlinGetterName.get(methodName)?.let { kotlinName ->
                if (kotlinName == methodName) return@flatMap listOf(KtLightMethodWrapper(this, method, shouldBeFinal = false))
                val finalBridge = KtLightMethodWrapper(this, method, shouldBeFinal = true)
                val abstractKotlinGetter = KtLightMethodWrapper(this, method, _name = kotlinName, shouldBeFinal = false)
                return@flatMap listOf(finalBridge, abstractKotlinGetter)
            }
            if (!isInInterface(method, readOnlyClass)) {
                return@flatMap listOf(KtLightMethodWrapper(this, method, shouldBeFinal = true))
            }
            if (methodName in membersWithSpecializedSignature) {
                if ((methodName == "get" || methodName == "remove") && javaBaseClass.qualifiedName != CommonClassNames.JAVA_UTIL_MAP) return@flatMap emptyList<PsiMethod>()

                val finalBridgeWithObject = KtLightMethodWrapper(this, method, replaceObjectWithGeneric = false, shouldBeFinal = true)
                val abstractKotlinVariantWithGeneric = methodWithSpecialSignature(method) ?: KtLightMethodWrapper(this, method, replaceObjectWithGeneric = true, shouldBeFinal = false)
                return@flatMap listOf(finalBridgeWithObject, abstractKotlinVariantWithGeneric)
            }

            //TODO: figure out toArray
//            if (methodName == "toArray") {
//                return@flatMap listOf(KtLightMethodWrapper(this, method, shouldBeFinal = true))
//            }
            return@flatMap emptyList<PsiMethod>()
        }
    }

    private fun methodWithSpecialSignature(method: PsiMethod): KtLightMethodWrapper? {
//        return null
        if (javaBaseClass.qualifiedName != CommonClassNames.JAVA_UTIL_MAP) return null

        val k = typeParameters[0].asType()
        val v = typeParameters[1].asType()

        val signature = when (method.name) {
            "get" -> MethodSignature(
                    parameterTypes = listOf(k),
                    returnType = v
            )
            "getOrDefault" -> MethodSignature(
                    parameterTypes = listOf(k, v),
                    returnType = v
            )
            "containsKey" -> MethodSignature(
                    parameterTypes = listOf(k),
                    returnType = PsiType.BOOLEAN
            )
            "containsValue" -> MethodSignature(
                    parameterTypes = listOf(v),
                    returnType = PsiType.BOOLEAN
            )
            "remove" ->
                when (method.parameterList.parametersCount) {
                    1 -> MethodSignature(
                            parameterTypes = listOf(k),
                            returnType = v
                    )
                    2 ->MethodSignature(
                            parameterTypes = listOf(k, v),
                            returnType = PsiType.BOOLEAN
                    )
                    else -> null
                }
            else -> null
        } ?: return null
        return KtLightMethodWrapper(this, method, providedSignature = signature, shouldBeFinal = false)
    }

    private fun isInInterface(it: PsiMethod, list: ClassDescriptor) = Name.identifier(it.name).let {
        val scope = list.unsubstitutedMemberScope
        scope.getContributedFunctions(it, NoLookupLocation.FROM_IDE).isNotEmpty()
        || scope.getContributedVariables(it, NoLookupLocation.FROM_IDE).isNotEmpty()
    }
}

private data class MethodSignature(val parameterTypes: List<PsiType>, val returnType: PsiType)

private class KtLightMethodWrapper(
        private val containingClass: KtAbstractContainerWrapper,
        private val baseMethod: PsiMethod,
        private val _name: String = baseMethod.name,
        private val shouldBeFinal: Boolean,
        private val replaceObjectWithGeneric: Boolean = false,
        private val providedSignature: MethodSignature? = null
) //TODO: drop LightMethod inheritance
    : LightMethod(containingClass, baseMethod, PsiSubstitutor.EMPTY /*TODO*/) {

    private fun substituteType(psiType: PsiType, isParameter: Boolean = true): PsiType {
        val substituted = containingClass._substitutor.substitute(psiType)
        if (!replaceObjectWithGeneric || !TypeUtils.isJavaLangObject(substituted)) return substituted

//        val correspondingClass = DefaultBuiltIns.Instance.getBuiltInClassByFqName(containingClass.fqName)
//        val correspondingFunction = correspondingClass.unsubstitutedMemberScope.getContributedFunctions(Name.identifier(_name), NoLookupLocation.FROM_IDE)
//        correspondingFunction.
        val typeParameter = containingClass.typeParameters.singleOrNull() ?: return psiType
        return PsiImmediateClassType(typeParameter, PsiSubstitutor.EMPTY)
    }


    override fun hasModifierProperty(name: String) =
            when (name) {
                PsiModifier.DEFAULT -> shouldBeFinal
                PsiModifier.ABSTRACT -> !shouldBeFinal
                PsiModifier.FINAL -> shouldBeFinal
                else -> baseMethod.hasModifierProperty(name)
            }

    override fun getParameterList(): PsiParameterList {
        return LightParameterListBuilder(manager, KotlinLanguage.INSTANCE).apply {
            baseMethod.parameterList.parameters.forEachIndexed { index, paramFromJava ->
                val type = providedSignature?.parameterTypes?.get(index) ?: substituteType(paramFromJava.type)
                addParameter(LightParameter(paramFromJava.name ?: "it", type, this, KotlinLanguage.INSTANCE))
            }
        }
    }

    override fun getName() = _name

    override fun getReturnType() = providedSignature?.returnType ?: baseMethod.returnType?.let { substituteType(it) }

    override fun findSuperMethods(checkAccess: Boolean) = PsiSuperMethodImplUtil.findSuperMethods(this, checkAccess)

    override fun findSuperMethods(parentClass: PsiClass) = PsiSuperMethodImplUtil.findSuperMethods(this, parentClass)

    override fun findSuperMethods() = PsiSuperMethodImplUtil.findSuperMethods(this)

    override fun findSuperMethodSignaturesIncludingStatic(checkAccess: Boolean) = PsiSuperMethodImplUtil.findSuperMethodSignaturesIncludingStatic(this, checkAccess)

    override fun findDeepestSuperMethod() = PsiSuperMethodImplUtil.findDeepestSuperMethod(this)

    override fun findDeepestSuperMethods() = PsiSuperMethodImplUtil.findDeepestSuperMethods(this)

    override fun getHierarchicalMethodSignature() = PsiSuperMethodImplUtil.getHierarchicalMethodSignature(this)

    override fun getSignature(substitutor: PsiSubstitutor) = MethodSignatureBackedByPsiMethod.create(this, substitutor)
}


abstract class KtAbstractContainerWrapper(internal val fqName: FqName, private val superInterface: PsiClass)
    : LightElement(superInterface.manager, KotlinLanguage.INSTANCE), PsiExtensibleClass {

    // TODO:
    val superClassTypeParametersToMyTypeParameters: Map<PsiTypeParameter, PsiTypeParameter> = superInterface.typeParameters.withIndex().associateBy(
            keySelector = { (_, supersParameter) -> supersParameter },
            valueTransform = {
                (index, supersParameter) ->
                // TODO:
                LightTypeParameterBuilder("K" + (supersParameter.name ?: ""), this, index)
            }
    )


    // TODO: name
    val _substitutor = createSubstitutor(superClassTypeParametersToMyTypeParameters.mapValues {
        PsiImmediateClassType(it.value, PsiSubstitutor.EMPTY)
    })

    override fun getSupers() = arrayOf(superInterface)

    override fun getOwnInnerClasses() = emptyList<PsiClass>()

    override fun getQualifiedName() = fqName.asString()

    override fun getOwnFields() = emptyList<PsiField>()

    override fun getAllMethodsAndTheirSubstitutors() =
            PsiClassImplUtil.getAllWithSubstitutorsByMap<PsiMethod>(this, PsiClassImplUtil.MemberType.METHOD)

    private val memberCache = ClassInnerStuffCache(this)

    private val _implementsList by lazyPub {
        LightReferenceListBuilder(manager, PsiReferenceList.Role.IMPLEMENTS_LIST).apply {
            addReference(superInterface)
        }
    }

    override fun toString() = "$javaClass:$name"

    override fun hasModifierProperty(name: String) = name in setOf(PsiModifier.PUBLIC, PsiModifier.ABSTRACT)

    override fun getInnerClasses() = PsiClass.EMPTY_ARRAY

    override fun findMethodBySignature(patternMethod: PsiMethod, checkBases: Boolean)
            = PsiClassImplUtil.findMethodBySignature(this, patternMethod, checkBases)

    override fun findInnerClassByName(name: String?, checkBases: Boolean) = null

    override fun getExtendsListTypes() = PsiClassType.EMPTY_ARRAY

    override fun getTypeParameterList(): PsiTypeParameterList? {
        return LightTypeParameterListBuilder(manager, KotlinLanguage.INSTANCE).apply {
            superClassTypeParametersToMyTypeParameters.values.forEach { addParameter(it) }
        } // TODO: lazyPub
    }

    override fun isAnnotationType() = false

    // TODO_R: lazyPub
    override fun getNameIdentifier() = LightIdentifier(manager, name)

    override fun getName() = fqName.shortName().asString()

    override fun getFields() = PsiField.EMPTY_ARRAY

    override fun getSuperClass() = null

    override fun findMethodsAndTheirSubstitutorsByName(name: String?, checkBases: Boolean)
            = PsiClassImplUtil.findMethodsAndTheirSubstitutorsByName(this, name, checkBases)

    override fun getImplementsList() = _implementsList

    // TODO: is this correct
    override fun getSuperTypes(): Array<PsiImmediateClassType> {
        val javaSupertype = PsiImmediateClassType(superInterface, _substitutor)

        val correspondingBuiltInClass = DefaultBuiltIns.Instance.getBuiltInClassByFqName(fqName)
        val superWrappers = correspondingBuiltInClass.typeConstructor.supertypes.mapNotNull {
            platformMutabilityWrapper(DescriptorUtils.getClassDescriptorForType(it).fqNameSafe) {
                JavaPsiFacade.getInstance(project).findClass(it, superInterface.resolveScope)
            }?.let {
                wrapperAncestor ->
                PsiImmediateClassType(wrapperAncestor, PsiSubstitutorImpl.createSubstitutor(
                        wrapperAncestor.typeParameters.zip(this.typeParameters.map { it.asType() }).toMap()
                ))
            }
        }

        return (superWrappers + javaSupertype).toTypedArray()
    }

    override fun getMethods() = memberCache.methods

    override fun getRBrace() = null

    override fun getLBrace() = null

    override fun getInitializers() = PsiClassInitializer.EMPTY_ARRAY

    override fun getContainingClass() = null

    override fun isInheritorDeep(baseClass: PsiClass, classToByPass: PsiClass?) = InheritanceImplUtil.isInheritorDeep(this, baseClass, classToByPass)

    override fun isInterface() = true

    override fun getTypeParameters() = superClassTypeParametersToMyTypeParameters.values.toTypedArray()

    override fun getInterfaces() = arrayOf(superInterface)

    override fun isInheritor(baseClass: PsiClass, checkDeep: Boolean) = InheritanceImplUtil.isInheritor(this, baseClass, checkDeep)

    override fun findFieldByName(name: String?, checkBases: Boolean) = null

    override fun getAllFields() = PsiClassImplUtil.getAllFields(this)

    override fun hasTypeParameters() = true

    override fun getAllInnerClasses() = PsiClassImplUtil.getAllInnerClasses(this)

    override fun getExtendsList() = null

    override fun getVisibleSignatures() = PsiSuperMethodImplUtil.getVisibleSignatures(this)

    override fun isEnum() = false

    override fun findMethodsByName(name: String?, checkBases: Boolean) = memberCache.findMethodsByName(name, checkBases)

    override fun getDocComment() = null

    override fun getAllMethods() = PsiClassImplUtil.getAllMethods(this)

    override fun getModifierList() = LightModifierList(manager, KotlinLanguage.INSTANCE) // TODO: lazypub

    override fun getScope(): PsiElement {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getImplementsListTypes() = superTypes

    override fun getConstructors() = PsiMethod.EMPTY_ARRAY

    override fun isDeprecated() = false

    override fun setName(name: String): PsiElement {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun findMethodsBySignature(patternMethod: PsiMethod, checkBases: Boolean)
            = PsiClassImplUtil.findMethodsBySignature(this, patternMethod, checkBases)
}

private fun PsiTypeParameter.asType() = PsiImmediateClassType(this, PsiSubstitutor.EMPTY)