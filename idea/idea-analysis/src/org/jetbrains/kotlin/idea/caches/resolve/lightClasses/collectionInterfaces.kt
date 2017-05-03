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


import com.intellij.openapi.util.Pair
import com.intellij.psi.*
import com.intellij.psi.impl.InheritanceImplUtil
import com.intellij.psi.impl.PsiClassImplUtil
import com.intellij.psi.impl.PsiSubstitutorImpl
import com.intellij.psi.impl.PsiSuperMethodImplUtil
import com.intellij.psi.impl.light.*
import com.intellij.psi.impl.source.ClassInnerStuffCache
import com.intellij.psi.impl.source.PsiExtensibleClass
import com.intellij.psi.impl.source.PsiImmediateClassType
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.caches.resolve.getJavaClassDescriptor
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.TargetPlatform
import org.jetbrains.kotlin.resolve.descriptorUtil.module


class KtLightReadonlyListWrapper(private val javaUtilList: PsiClass) : KtAbstractContainerWrapper("kotlin.List", javaUtilList), PsiClass {
    private val _methods by lazyPub { calcMethods() }

    override fun getOwnMethods(): List<PsiMethod> {
        return _methods
    }

    private fun calcMethods(): List<PsiMethod> {
        val builtIns = DefaultBuiltIns.Instance
        val list = builtIns.list
        return javaUtilList.allMethods.mapNotNull {
            if (!isInInterface(it, list) || it.name == "toArray") {
                KtLightMethodWrapper(this, it)
            }
            else null
        }
    }

    private fun isInInterface(it: PsiMethod, list: ClassDescriptor) = Name.identifier(it.name).let {
        val scope = list.unsubstitutedMemberScope
        scope.getContributedFunctions(it, NoLookupLocation.FROM_IDE).isNotEmpty()
        || scope.getContributedVariables(it, NoLookupLocation.FROM_IDE).isNotEmpty()
    }
}

class KtLightMethodWrapper(private val containingClass: KtAbstractContainerWrapper, private val baseMethod: PsiMethod)
    : LightMethod(containingClass, baseMethod, PsiSubstitutor.EMPTY /*TODO*/) {
    override fun hasModifierProperty(name: String) =
            when (name) {
                PsiModifier.DEFAULT -> true
                PsiModifier.ABSTRACT -> false
                else -> baseMethod.hasModifierProperty(name)
            }

    override fun getParameterList(): PsiParameterList {
        return LightParameterListBuilder(manager, KotlinLanguage.INSTANCE).apply {
            val invertedMap: Map<PsiTypeParameter, PsiTypeParameter> = containingClass.typeParametersMap.entries.associateBy({ it.value }, { it.key })
            val substitutor = PsiSubstitutorImpl.createSubstitutor(invertedMap.mapValues { PsiImmediateClassType(it.key, PsiSubstitutor.EMPTY) })
            baseMethod.parameterList.parameters.forEach { paramFromJava ->
                addParameter(LightParameter(paramFromJava.name ?: "", substitutor.substitute(paramFromJava.type), this, KotlinLanguage.INSTANCE))
            }
        }
    }
}


abstract class KtAbstractContainerWrapper(private val fqName: String, private val superInterface: PsiClass)
    : LightElement(superInterface.manager, KotlinLanguage.INSTANCE), PsiExtensibleClass {

    val typeParametersMap: Map<PsiTypeParameter, PsiTypeParameter> = superInterface.typeParameters.withIndex().associateBy(
            keySelector = { (index, supersParameter) ->
                LightTypeParameterBuilder(supersParameter.name ?: "", this, index)
            },
            valueTransform = { (_, supersParameter) -> supersParameter }
    )

    override fun getSupers() = arrayOf(superInterface)

    override fun getOwnInnerClasses() = emptyList<PsiClass>()

    override fun getQualifiedName() = fqName

    override fun getOwnFields() = emptyList<PsiField>()

    override fun getAllMethodsAndTheirSubstitutors(): List<Pair<PsiMethod, PsiSubstitutor>> =
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
            typeParametersMap.keys.forEach { addParameter(it) }
        } // TODO: lazyPub
    }

    override fun isAnnotationType() = false

    override fun getNameIdentifier(): PsiIdentifier? {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getFields() = PsiField.EMPTY_ARRAY

    override fun getSuperClass() = null

    override fun findMethodsAndTheirSubstitutorsByName(name: String?, checkBases: Boolean)
            = PsiClassImplUtil.findMethodsAndTheirSubstitutorsByName(this, name, checkBases)

    override fun getImplementsList() = _implementsList

    override fun getSuperTypes() = arrayOf(PsiImmediateClassType(superInterface, PsiSubstitutorImpl.createSubstitutor(typeParametersMap.mapValues { PsiImmediateClassType(it.value, PsiSubstitutor.EMPTY) }))) // TODO:

    override fun getMethods() = memberCache.methods

    override fun getRBrace() = null

    override fun getLBrace() = null

    override fun getInitializers() = PsiClassInitializer.EMPTY_ARRAY

    override fun getContainingClass() = null

    override fun isInheritorDeep(baseClass: PsiClass, classToByPass: PsiClass?) = InheritanceImplUtil.isInheritorDeep(this, baseClass, classToByPass)

    override fun isInterface() = true

    override fun getTypeParameters() = typeParametersMap.keys.toTypedArray()

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