package org.jetbrains.jet.plugin.completion.smart

import com.intellij.codeInsight.lookup.LookupElement
import org.jetbrains.jet.lang.types.TypeUtils
import org.jetbrains.jet.lang.descriptors.ClassDescriptor
import org.jetbrains.jet.lang.resolve.scopes.JetScope
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptor
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptorWithVisibility
import org.jetbrains.jet.lang.descriptors.Visibilities
import org.jetbrains.jet.lang.descriptors.CallableDescriptor
import org.jetbrains.jet.lang.descriptors.ClassKind
import org.jetbrains.jet.lang.resolve.java.descriptor.JavaClassDescriptor
import org.jetbrains.jet.plugin.completion.DescriptorLookupConverter
import org.jetbrains.jet.lang.resolve.DescriptorUtils
import org.jetbrains.jet.plugin.completion.handlers.CaretPosition
import org.jetbrains.jet.lang.descriptors.FunctionDescriptor
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import org.jetbrains.jet.renderer.DescriptorRenderer
import com.intellij.codeInsight.completion.InsertionContext
import org.jetbrains.jet.plugin.project.ResolveSessionForBodies
import org.jetbrains.jet.lang.resolve.BindingContext
import org.jetbrains.jet.lang.psi.JetExpression
import org.jetbrains.jet.plugin.completion.ExpectedInfo
import java.util.ArrayList

// adds java static members, enum members and members from class object
class StaticMembers(val bindingContext: BindingContext, val resolveSession: ResolveSessionForBodies) {
    public fun addToCollection(collection: MutableCollection<LookupElement>, expectedInfos: Collection<ExpectedInfo>, context: JetExpression) {
        val scope = bindingContext[BindingContext.RESOLUTION_SCOPE, context]
        if (scope == null) return

        val expectedInfosByClass = expectedInfos.groupBy { TypeUtils.getClassDescriptor(it.`type`) }
        for ((classDescriptor, expectedInfosForClass) in expectedInfosByClass) {
            if (classDescriptor != null && !classDescriptor.getName().isSpecial()) {
                addToCollection(collection, classDescriptor, expectedInfosForClass, scope)
            }
        }
    }

    private fun addToCollection(
            collection: MutableCollection<LookupElement>,
            classDescriptor: ClassDescriptor,
            expectedInfos: Collection<ExpectedInfo>,
            scope: JetScope) {

        fun processMember(descriptor: DeclarationDescriptor) {
            if (descriptor is DeclarationDescriptorWithVisibility && !Visibilities.isVisible(descriptor, scope.getContainingDeclaration())) return

            val classifier: (ExpectedInfo) -> ExpectedInfoClassification
            if (descriptor is CallableDescriptor) {
                val returnType = descriptor.getReturnType()
                if (returnType == null) return
                classifier = {
                    expectedInfo ->
                        when {
                            returnType.isSubtypeOf(expectedInfo.`type`) -> ExpectedInfoClassification.MATCHES
                            returnType.isNullable() && TypeUtils.makeNotNullable(returnType).isSubtypeOf(expectedInfo.`type`) -> ExpectedInfoClassification.MAKE_NOT_NULLABLE
                            else -> ExpectedInfoClassification.NOT_MATCHES
                        }
                }
            }
            else if (DescriptorUtils.isEnumEntry(descriptor)) {
                classifier = { ExpectedInfoClassification.MATCHES } /* we do not need to check type of enum entry because it's taken from proper enum */
            }
            else {
                return
            }

            collection.addLookupElements(expectedInfos, classifier, { createLookupElement(descriptor, classDescriptor) })
        }

        if (classDescriptor is JavaClassDescriptor) {
            val pseudoPackage = classDescriptor.getCorrespondingPackageFragment()
            if (pseudoPackage != null) {
                pseudoPackage.getMemberScope().getAllDescriptors().forEach(::processMember)
            }
        }

        val classObject = classDescriptor.getClassObjectDescriptor()
        if (classObject != null) {
            classObject.getDefaultType().getMemberScope().getAllDescriptors().forEach(::processMember)
        }

        if (classDescriptor.getKind() == ClassKind.ENUM_CLASS) {
            classDescriptor.getDefaultType().getMemberScope().getAllDescriptors().forEach(::processMember)
        }
    }

    private fun createLookupElement(memberDescriptor: DeclarationDescriptor, classDescriptor: ClassDescriptor): LookupElement {
        val lookupElement = DescriptorLookupConverter.createLookupElement(resolveSession, bindingContext, memberDescriptor)
        val qualifierPresentation = classDescriptor.getName().asString()
        val lookupString = qualifierPresentation + "." + lookupElement.getLookupString()
        val qualifierText = DescriptorUtils.getFqName(classDescriptor).asString() //TODO: escape keywords

        return object: LookupElementDecorator<LookupElement>(lookupElement) {
            override fun getLookupString() = lookupString

            override fun renderElement(presentation: LookupElementPresentation) {
                getDelegate().renderElement(presentation)

                presentation.setItemText(qualifierPresentation + "." + presentation.getItemText())

                val tailText = " (" + DescriptorUtils.getFqName(classDescriptor.getContainingDeclaration()) + ")"
                if (memberDescriptor is FunctionDescriptor) {
                    presentation.appendTailText(tailText, true)
                }
                else {
                    presentation.setTailText(tailText, true)
                }

                if (presentation.getTypeText().isNullOrEmpty()) {
                    presentation.setTypeText(DescriptorRenderer.SHORT_NAMES_IN_TYPES.renderType(classDescriptor.getDefaultType()))
                }
            }

            override fun handleInsert(context: InsertionContext) {
                var text = qualifierText + "." + memberDescriptor.getName().asString() //TODO: escape

                context.getDocument().replaceString(context.getStartOffset(), context.getTailOffset(), text)
                context.setTailOffset(context.getStartOffset() + text.length)

                if (memberDescriptor is FunctionDescriptor) {
                    getDelegate().handleInsert(context)
                }

                shortenReferences(context, context.getStartOffset(), context.getTailOffset())
            }
        }
    }
}
