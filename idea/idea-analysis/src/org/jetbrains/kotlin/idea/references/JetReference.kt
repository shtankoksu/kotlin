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

package org.jetbrains.kotlin.idea.references

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveResult
import com.intellij.psi.PsiElementResolveResult
import com.intellij.util.IncorrectOperationException
import com.intellij.psi.PsiReference
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.resolve.BindingContext
import java.util.Collections
import org.jetbrains.kotlin.psi.JetReferenceExpression
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToDeclarationUtil
import org.jetbrains.kotlin.psi.JetElement
import org.jetbrains.kotlin.utils.keysToMap
import org.jetbrains.kotlin.idea.caches.resolve.analyze

public trait JetReference : PsiPolyVariantReference {
    public fun resolveToDescriptors(): Collection<DeclarationDescriptor>
    public fun resolveMap(): Map<DeclarationDescriptor, Collection<PsiElement>>
}

public abstract class AbstractJetReference<T : JetElement>(element: T)
: PsiPolyVariantReferenceBase<T>(element), JetReference {

    public val expression: T
        get() = getElement()

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        return PsiElementResolveResult.createResults(resolveToPsiElements())
    }

    override fun resolve(): PsiElement? {
        val psiElements = resolveToPsiElements()
        if (psiElements.size() == 1) {
            return psiElements.iterator().next()
        }
        return null
    }

    override fun isReferenceTo(element: PsiElement?): Boolean {
        return element != null && matchesTarget(element)
    }

    override fun getCanonicalText(): String = "<TBD>"

    public open fun canRename(): Boolean = false
    override fun handleElementRename(newElementName: String?): PsiElement? = throw IncorrectOperationException()

    override fun bindToElement(element: PsiElement): PsiElement = throw IncorrectOperationException()

    [suppress("CAST_NEVER_SUCCEEDS")]
    override fun getVariants(): Array<Any> = PsiReference.EMPTY_ARRAY as Array<Any>

    override fun isSoft(): Boolean = false

    private fun resolveToPsiElements(): Collection<PsiElement> {
        val bindingContext = expression.analyze()
        return resolveToPsiElements(bindingContext, getTargetDescriptors(bindingContext))
    }

    override fun resolveToDescriptors(): Collection<DeclarationDescriptor> {
        return getTargetDescriptors(expression.analyze())
    }

    override fun resolveMap(): Map<DeclarationDescriptor, Collection<PsiElement>> {
        return getTargetDescriptors(expression.analyze()) keysToMap { DescriptorToDeclarationUtil.resolveToPsiElements(expression.getProject(), it) }
    }

    private fun resolveToPsiElements(context: BindingContext, targetDescriptors: Collection<DeclarationDescriptor>): Collection<PsiElement> {
        if (targetDescriptors.isNotEmpty()) {
            return targetDescriptors flatMap { target -> DescriptorToDeclarationUtil.resolveToPsiElements(expression.getProject(), target) }
        }

        val labelTargets = getLabelTargets(context)
        if (labelTargets != null) {
            return labelTargets
        }

        return Collections.emptySet()
    }

    protected abstract fun getTargetDescriptors(context: BindingContext): Collection<DeclarationDescriptor>

    private fun getLabelTargets(context: BindingContext): Collection<PsiElement>? {
        val reference = expression
        if (reference !is JetReferenceExpression) {
            return null
        }
        val labelTarget = context[BindingContext.LABEL_TARGET, reference]
        if (labelTarget != null) {
            return listOf(labelTarget)
        }
        return context[BindingContext.AMBIGUOUS_LABEL_TARGET, reference]
    }
}

public abstract class JetSimpleReference<T : JetReferenceExpression>(expression: T) : AbstractJetReference<T>(expression) {
    override fun getTargetDescriptors(context: BindingContext): Collection<DeclarationDescriptor> {
        val targetDescriptor = context[BindingContext.REFERENCE_TARGET, expression]
        if (targetDescriptor != null) {
            return listOf(targetDescriptor)
        }
        return context[BindingContext.AMBIGUOUS_REFERENCE_TARGET, expression].orEmpty()
    }
}

public abstract class JetMultiReference<T : JetElement>(expression: T) : AbstractJetReference<T>(expression)
