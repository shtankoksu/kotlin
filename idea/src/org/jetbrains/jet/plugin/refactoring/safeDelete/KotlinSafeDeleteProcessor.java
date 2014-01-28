/*
 * Copyright 2010-2013 JetBrains s.r.o.
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

package org.jetbrains.jet.plugin.refactoring.safeDelete;

import com.intellij.ide.IdeBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Conditions;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.OverridingMethodsSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.safeDelete.JavaSafeDeleteProcessor;
import com.intellij.refactoring.safeDelete.NonCodeUsageSearchInfo;
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteOverrideAnnotation;
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteOverridingMethodUsageInfo;
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceJavaDeleteUsageInfo;
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceSimpleDeleteUsageInfo;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.asJava.AsJavaPackage;
import org.jetbrains.jet.asJava.LightClassUtil;
import org.jetbrains.jet.lang.descriptors.CallableMemberDescriptor;
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptor;
import org.jetbrains.jet.lang.descriptors.Modality;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.psi.psiUtil.PsiUtilPackage;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.java.jetAsJava.KotlinLightMethod;
import org.jetbrains.jet.lexer.JetTokens;
import org.jetbrains.jet.plugin.JetBundle;
import org.jetbrains.jet.plugin.project.AnalyzerFacadeWithCache;
import org.jetbrains.jet.plugin.refactoring.JetRefactoringUtil;
import org.jetbrains.jet.plugin.references.JetPsiReference;

import java.util.*;

public class KotlinSafeDeleteProcessor extends JavaSafeDeleteProcessor {
    public static boolean canDeleteElement(@NotNull PsiElement element) {
        if (PsiUtilPackage.isObjectLiteral(element)) return false;
        if (element instanceof JetParameter) {
            JetDeclaration declaration = PsiTreeUtil.getParentOfType(element, JetDeclaration.class);
            return declaration != null && !(declaration instanceof JetPropertyAccessor && ((JetPropertyAccessor) declaration).isSetter());
        }
        return element instanceof JetClassOrObject
               || element instanceof JetNamedFunction
               || element instanceof PsiMethod
               || element instanceof JetProperty
               || element instanceof JetTypeParameter;
    }

    @Override
    public boolean handlesElement(@NotNull PsiElement element) {
        return canDeleteElement(element);
    }

    @NotNull
    protected static NonCodeUsageSearchInfo getSearchInfo(
            @NotNull PsiElement element, @NotNull Collection<? extends PsiElement> ignoredElements
    ) {

        return new NonCodeUsageSearchInfo(getCondition(ignoredElements), element);
    }

    private static Condition<PsiElement> getCondition(final Collection<? extends PsiElement> ignoredElements) {
        return new Condition<PsiElement>() {
            @Override
            public boolean value(PsiElement usage) {
                if (usage instanceof JetFile) return false;
                return isInside(usage, ignoredElements);
            }
        };
    }

    @NotNull
    protected static NonCodeUsageSearchInfo getSearchInfo(@NotNull PsiElement element, @NotNull PsiElement[] ignoredElements) {
        return getSearchInfo(element, Arrays.asList(ignoredElements));
    }

    @Nullable
    @Override
    public NonCodeUsageSearchInfo findUsages(
            @NotNull PsiElement element, @NotNull PsiElement[] allElementsToDelete, @NotNull List<UsageInfo> result
    ) {
        if (element instanceof JetClassOrObject) {
            return delegateToJavaProcessor(LightClassUtil.getPsiClass((JetClassOrObject) element), allElementsToDelete, result);
        }
        if (element instanceof JetNamedFunction) {
            JetNamedFunction function = (JetNamedFunction) element;
            if (function.isLocal()) {
                return findKotlinDeclarationUsages(function, allElementsToDelete, result);
            }
            PsiMethod method = LightClassUtil.getLightClassMethod((JetNamedFunction) element);
            if (method != null) return delegateToJavaProcessor(method, allElementsToDelete, result);

            return getSearchInfo(element, allElementsToDelete);
        }
        if (element instanceof PsiMethod) {
            return delegateToJavaProcessor(element, allElementsToDelete, result);
        }
        if (element instanceof JetProperty) {
            JetProperty property = (JetProperty) element;

            if (property.isLocal()) return findKotlinDeclarationUsages(property, allElementsToDelete, result);
            return delegateToJavaProcessor(property, allElementsToDelete, result);
        }
        if (element instanceof JetTypeParameter) {
            JetTypeParameter typeParameter = (JetTypeParameter) element;

            findTypeParameterUsages(typeParameter, result);
            return delegateToJavaProcessor(typeParameter, allElementsToDelete, result);
        }
        if (element instanceof JetParameter) {
            return delegateToJavaProcessor((JetParameter) element, allElementsToDelete, result);
        }

        return getSearchInfo(element, allElementsToDelete);
    }

    private NonCodeUsageSearchInfo delegateToJavaProcessor(
            JetDeclaration jetDeclaration,
            PsiElement[] allElementsToDelete,
            List<UsageInfo> result
    ) {
        return new NonCodeUsageSearchInfo(
                delegateToJavaProcessorAndCombineConditions(
                        AsJavaPackage.toLightElements(jetDeclaration),
                        getCondition(Arrays.asList(allElementsToDelete)),
                        allElementsToDelete,
                        result
                ),
                jetDeclaration
        );
    }

    private Condition<PsiElement> delegateToJavaProcessorAndCombineConditions(
            Iterable<? extends PsiElement> elements,
            Condition<PsiElement> insideDeleted,
            PsiElement[] allElementsToDelete,
            List<UsageInfo> result
    ) {
        for (PsiElement element: elements) {
            NonCodeUsageSearchInfo accessorSearchInfo = delegateToJavaProcessor(element, allElementsToDelete, result);
            if (accessorSearchInfo == null) continue;

            insideDeleted = Conditions.or(insideDeleted, accessorSearchInfo.getInsideDeletedCondition());
        }
        return insideDeleted;
    }

    @SuppressWarnings("MethodOverridesPrivateMethodOfSuperclass")
    protected static boolean isInside(@NotNull PsiElement place, @NotNull PsiElement[] ancestors) {
        return isInside(place, Arrays.asList(ancestors));
    }

    @SuppressWarnings("MethodOverridesPrivateMethodOfSuperclass")
    protected static boolean isInside(@NotNull PsiElement place, @NotNull Collection<? extends PsiElement> ancestors) {
        for (PsiElement element : ancestors) {
            if (isInside(place, element)) return true;
        }
        return false;
    }

    @SuppressWarnings("MethodOverridesStaticMethodOfSuperclass")
    public static boolean isInside(@NotNull PsiElement place, @NotNull PsiElement ancestor) {
        if (ancestor instanceof KotlinLightMethod) {
            ancestor = ((KotlinLightMethod) ancestor).getOrigin();
        }
        return JavaSafeDeleteProcessor.isInside(place, ancestor);
    }

    @Nullable
    protected NonCodeUsageSearchInfo delegateToJavaProcessor(
            @Nullable PsiElement element,
            @NotNull PsiElement[] allElementsToDelete,
            @NotNull List<UsageInfo> result
    ) {
        if (element == null) return null;

        List<UsageInfo> javaUsages = new ArrayList<UsageInfo>();
        NonCodeUsageSearchInfo searchInfo = super.findUsages(element, allElementsToDelete, javaUsages);

        for (UsageInfo usageInfo : javaUsages) {
            if (usageInfo instanceof SafeDeleteOverridingMethodUsageInfo) {
                SafeDeleteOverridingMethodUsageInfo overrideUsageInfo = (SafeDeleteOverridingMethodUsageInfo) usageInfo;

                PsiElement usageElement = overrideUsageInfo.getSmartPointer().getElement();
                usageInfo = (usageElement != null)
                            ? new KotlinSafeDeleteOverridingUsageInfo(usageElement, overrideUsageInfo.getReferencedElement())
                            : null;
            }
            else if (usageInfo instanceof SafeDeleteOverrideAnnotation) {
                SafeDeleteOverrideAnnotation overrideAnnotationUsageInfo = (SafeDeleteOverrideAnnotation) usageInfo;

                PsiElement targetElement = overrideAnnotationUsageInfo.getSmartPointer().getElement();
                if (targetElement != null) {
                    boolean noSuperMethods = ContainerUtil.and(
                            AsJavaPackage.toLightMethods(targetElement),
                            new Condition<PsiMethod>() {
                                @Override
                                public boolean value(PsiMethod method) {
                                    return method.findSuperMethods().length == 0;
                                }
                            }
                    );

                    usageInfo = noSuperMethods
                                ? new KotlinSafeDeleteOverrideAnnotation(targetElement, overrideAnnotationUsageInfo.getReferencedElement())
                                : null;
                }
                else {
                    usageInfo = null;
                }
            }
            else if (usageInfo instanceof SafeDeleteReferenceJavaDeleteUsageInfo) {
                SafeDeleteReferenceJavaDeleteUsageInfo javaDeleteUsageInfo = (SafeDeleteReferenceJavaDeleteUsageInfo) usageInfo;
                PsiElement usageElement = javaDeleteUsageInfo.getElement();
                JetImportDirective importDirective = PsiTreeUtil.getParentOfType(usageElement, JetImportDirective.class, false);
                if (importDirective != null) {
                    usageInfo = new SafeDeleteImportDirectiveUsageInfo(
                            importDirective, (JetDeclaration) AsJavaPackage.getUnwrapped(element)
                    );
                }
            }
            if (usageInfo != null) {
                result.add(usageInfo);
            }
        }

        return searchInfo;
    }

    @NotNull
    protected static NonCodeUsageSearchInfo findKotlinDeclarationUsages(
            @NotNull final JetDeclaration declaration,
            @NotNull final PsiElement[] allElementsToDelete,
            @NotNull final List<UsageInfo> result
    ) {
        ReferencesSearch.search(declaration, declaration.getUseScope()).forEach(new Processor<PsiReference>() {
            @Override
            public boolean process(PsiReference reference) {
                PsiElement element = reference.getElement();
                if (!isInside(element, allElementsToDelete)) {
                    JetImportDirective importDirective = PsiTreeUtil.getParentOfType(element, JetImportDirective.class, false);
                    if (importDirective != null) {
                        result.add(new SafeDeleteImportDirectiveUsageInfo(importDirective, declaration));
                    }
                    else {
                        result.add(new SafeDeleteReferenceSimpleDeleteUsageInfo(element, declaration, false));
                    }
                }
                return true;
            }
        });

        return getSearchInfo(declaration, allElementsToDelete);
    }

    protected static void findTypeParameterUsages(
            @NotNull final JetTypeParameter parameter,
            @NotNull final List<UsageInfo> result
    ) {
        JetTypeParameterListOwner owner = PsiTreeUtil.getParentOfType(parameter, JetTypeParameterListOwner.class);
        if (owner == null) return;

        List<JetTypeParameter> parameterList = owner.getTypeParameters();
        final int parameterIndex = parameterList.indexOf(parameter);

        ReferencesSearch.search(owner).forEach(
                new Processor<PsiReference>() {
                    @Override
                    public boolean process(PsiReference reference) {
                        if (reference instanceof JetPsiReference) {
                            processKotlinTypeArgumentListCandidate(reference, parameterIndex, result, parameter);
                        }
                        return true;
                    }
                }
        );
    }

    private static void processKotlinTypeArgumentListCandidate(
            @NotNull PsiReference reference,
            int parameterIndex,
            @NotNull List<UsageInfo> result,
            @NotNull JetTypeParameter parameter
    ) {
        PsiElement referencedElement = reference.getElement();

        JetTypeArgumentList argList = null;

        JetUserType type = PsiTreeUtil.getParentOfType(referencedElement, JetUserType.class);
        if (type != null) {
            argList = type.getTypeArgumentList();
        }
        else {
            JetCallExpression callExpression = PsiTreeUtil.getParentOfType(referencedElement, JetCallExpression.class);
            if (callExpression != null) {
                argList = callExpression.getTypeArgumentList();
            }
        }

        if (argList != null) {
            List<JetTypeProjection> projections = argList.getArguments();
            if (parameterIndex < projections.size()) {
                result.add(new SafeDeleteTypeArgumentListUsageInfo(projections.get(parameterIndex), parameter));
            }
        }
    }

    @Override
    @Nullable
    public Collection<String> findConflicts(@NotNull PsiElement element, @NotNull PsiElement[] allElementsToDelete) {
        if (element instanceof JetNamedFunction || element instanceof JetProperty) {
            JetClass jetClass = PsiTreeUtil.getParentOfType(element, JetClass.class);
            if (jetClass == null || jetClass.getBody() != element.getParent()) return null;

            JetModifierList modifierList = jetClass.getModifierList();
            if (modifierList != null && modifierList.hasModifier(JetTokens.ABSTRACT_KEYWORD)) return null;

            BindingContext bindingContext =
                    AnalyzerFacadeWithCache.analyzeFileWithCache((JetFile) element.getContainingFile()).getBindingContext();

            DeclarationDescriptor declarationDescriptor = bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, element);
            if (!(declarationDescriptor instanceof CallableMemberDescriptor)) return null;

            List<String> messages = new ArrayList<String>();
            CallableMemberDescriptor callableDescriptor = (CallableMemberDescriptor) declarationDescriptor;
            for (CallableMemberDescriptor overridenDescriptor : callableDescriptor.getOverriddenDescriptors()) {
                if (overridenDescriptor.getModality() == Modality.ABSTRACT) {
                    String message = JetBundle.message(
                            "x.implements.y",
                            JetRefactoringUtil.formatFunction(callableDescriptor, bindingContext, true),
                            JetRefactoringUtil.formatClass(callableDescriptor.getContainingDeclaration(), bindingContext, true),
                            JetRefactoringUtil.formatFunction(overridenDescriptor, bindingContext, true),
                            JetRefactoringUtil.formatClass(overridenDescriptor.getContainingDeclaration(), bindingContext, true)
                    );
                    messages.add(message);
                }
            }

            if (!messages.isEmpty()) return messages;
        }
        return super.findConflicts(element, allElementsToDelete);
    }

    /*
     * Mostly copied from JavaSafeDeleteProcessor.preprocessUsages
     * Revision: d4fc033
     * (replaced original dialog)
     */
    @Nullable
    @Override
    public UsageInfo[] preprocessUsages(@NotNull Project project, @NotNull UsageInfo[] usages) {
        ArrayList<UsageInfo> result = new ArrayList<UsageInfo>();
        ArrayList<UsageInfo> overridingMethodUsages = new ArrayList<UsageInfo>();

        for (UsageInfo usage : usages) {
            if (usage instanceof KotlinSafeDeleteOverridingUsageInfo) {
                overridingMethodUsages.add(usage);
            }
            else {
                result.add(usage);
            }
        }

        if (!overridingMethodUsages.isEmpty()) {
            if (ApplicationManager.getApplication().isUnitTestMode()) {
                result.addAll(overridingMethodUsages);
            }
            else {
                KotlinOverridingDialog dialog = new KotlinOverridingDialog(project, overridingMethodUsages);
                dialog.show();
                if (!dialog.isOK()) return null;
                result.addAll(dialog.getSelected());
            }
        }

        return result.toArray(new UsageInfo[result.size()]);
    }

    public static void removeOverrideModifier(@NotNull PsiElement element) {
        if (element instanceof JetNamedFunction || element instanceof JetProperty) {
            JetModifierList modifierList = ((JetModifierListOwner) element).getModifierList();
            if (modifierList == null) return;

            PsiElement overrideModifier = modifierList.getModifier(JetTokens.OVERRIDE_KEYWORD);
            if (overrideModifier != null) {
                overrideModifier.delete();
            }
        }
        else if (element instanceof PsiMethod) {
            PsiMethod method = (PsiMethod) element;

            PsiAnnotation overrideAnnotation = null;
            for (PsiAnnotation annotation : method.getModifierList().getAnnotations()) {
                if ("java.lang.Override".equals(annotation.getQualifiedName())) {
                    overrideAnnotation = annotation;
                    break;
                }
            }

            if (overrideAnnotation != null) {
                overrideAnnotation.delete();
            }
        }
    }

    @Nullable
    private static PsiParameter getPsiParameter(@NotNull JetParameter parameter) {
        JetNamedFunction function = PsiTreeUtil.getParentOfType(parameter, JetNamedFunction.class);
        if (function == null || parameter.getParent() != function.getValueParameterList()) return null;

        PsiMethod lightMethod = LightClassUtil.getLightClassMethod(function);
        if (lightMethod == null) return null;

        int parameterIndex = function.getValueParameters().indexOf(parameter);
        return lightMethod.getParameterList().getParameters()[parameterIndex];
    }

    @Override
    public void prepareForDeletion(@NotNull PsiElement element) throws IncorrectOperationException {
        if (element instanceof PsiMethod) {
            cleanUpOverrides((PsiMethod) element);
        }
        else if (element instanceof JetNamedFunction) {
            PsiMethod lightMethod = LightClassUtil.getLightClassMethod((JetNamedFunction) element);
            if (lightMethod == null) {
                return;
            }

            cleanUpOverrides(lightMethod);
        }
        else if (element instanceof JetProperty) {
            LightClassUtil.PropertyAccessorsPsiMethods propertyMethods =
                    LightClassUtil.getLightClassPropertyMethods((JetProperty) element);
            PsiMethod getter = propertyMethods.getGetter();
            PsiMethod setter = propertyMethods.getSetter();

            if (getter != null) {
                cleanUpOverrides(getter);
            }
            if (setter != null) {
                cleanUpOverrides(setter);
            }
        }
        else if (element instanceof JetTypeParameter) {
            deleteElementAndCleanParent(element);
        }
        else if (element instanceof JetParameter) {
            JetPsiUtil.deleteElementWithDelimiters(element);
        }
    }

    public static void deleteElementAndCleanParent(@NotNull PsiElement element) {
        PsiElement parent = element.getParent();
        JetPsiUtil.deleteElementWithDelimiters(element);
        JetPsiUtil.deleteChildlessElement(parent, element.getClass());
    }

    private static boolean checkPsiMethodEquality(@NotNull PsiMethod method1, @NotNull PsiMethod method2) {
        if (method1 instanceof KotlinLightMethod && method2 instanceof KotlinLightMethod) {
            return ((KotlinLightMethod) method1).getOrigin().equals(((KotlinLightMethod) method2).getOrigin());
        }
        return method1.equals(method2);
    }

    public static void cleanUpOverrides(@NotNull PsiMethod method) {
        Collection<PsiMethod> superMethods = Arrays.asList(method.findSuperMethods(true));
        Collection<PsiMethod> overridingMethods = OverridingMethodsSearch.search(method, true).findAll();
        overrideLoop:
        for (PsiMethod overridingMethod : overridingMethods) {
            PsiElement overridingElement = overridingMethod instanceof KotlinLightMethod
                                           ? ((KotlinLightMethod) overridingMethod).getOrigin()
                                           : overridingMethod;

            Collection<PsiMethod> currentSuperMethods = new ArrayList<PsiMethod>();
            ContainerUtil.addAll(currentSuperMethods, overridingMethod.findSuperMethods(true));
            currentSuperMethods.addAll(superMethods);
            for (PsiMethod superMethod : currentSuperMethods) {
                if (!checkPsiMethodEquality(superMethod, method)) continue overrideLoop;
            }

            removeOverrideModifier(overridingElement);
        }
    }

    @Nullable
    @Override
    public Collection<? extends PsiElement> getElementsToSearch(
            @NotNull PsiElement element, @Nullable Module module, @NotNull Collection<PsiElement> allElementsToDelete
    ) {
        if (element instanceof JetParameter) {
            PsiParameter psiParameter = getPsiParameter((JetParameter) element);
            if (psiParameter != null) return checkParametersInMethodHierarchy(psiParameter);
        }

        if (element instanceof PsiParameter) {
            return checkParametersInMethodHierarchy((PsiParameter) element);
        }

        if (ApplicationManager.getApplication().isUnitTestMode()) {
            return Collections.singletonList(element);
        }

        if (element instanceof JetNamedFunction || element instanceof JetProperty) {
            return JetRefactoringUtil.checkSuperMethods(
                    (JetDeclaration) element, allElementsToDelete, "super.methods.delete.with.usage.search"
            );
        }

        return super.getElementsToSearch(element, module, allElementsToDelete);
    }

    @Nullable
    private static Collection<? extends PsiElement> checkParametersInMethodHierarchy(@NotNull PsiParameter parameter) {
        PsiMethod method = (PsiMethod)parameter.getDeclarationScope();
        int parameterIndex = method.getParameterList().getParameterIndex(parameter);

        Set<PsiElement> parametersToDelete = collectParametersToDelete(method, parameterIndex);
        if (parametersToDelete.size() > 1) {
            if (ApplicationManager.getApplication().isUnitTestMode()) {
                return parametersToDelete;
            }

            String message =
                    JetBundle.message("delete.param.in.method.hierarchy", JetRefactoringUtil.formatJavaOrLightMethod(method));
            int exitCode = Messages.showOkCancelDialog(
                    parameter.getProject(), message, IdeBundle.message("title.warning"), Messages.getQuestionIcon()
            );
            if (exitCode == Messages.OK) {
                return parametersToDelete;
            }
            else {
                return null;
            }
        }

        return parametersToDelete;
    }

    // TODO: generalize breadth-first search
    @NotNull
    private static Set<PsiElement> collectParametersToDelete(@NotNull PsiMethod method, int parameterIndex) {
        Deque<PsiMethod> queue = new ArrayDeque<PsiMethod>();
        Set<PsiMethod> visited = new HashSet<PsiMethod>();
        Set<PsiElement> parametersToDelete = new HashSet<PsiElement>();

        queue.add(method);
        while (!queue.isEmpty()) {
            PsiMethod currentMethod = queue.poll();

            visited.add(currentMethod);
            addParameter(currentMethod, parametersToDelete, parameterIndex);

            for (PsiMethod superMethod : currentMethod.findSuperMethods(true)) {
                if (!visited.contains(superMethod)) {
                    queue.offer(superMethod);
                }
            }
            for (PsiMethod overrider : OverridingMethodsSearch.search(currentMethod)) {
                if (!visited.contains(overrider)) {
                    queue.offer(overrider);
                }
            }
        }
        return parametersToDelete;
    }

    private static void addParameter(@NotNull PsiMethod method, @NotNull Set<PsiElement> result, int parameterIndex) {
        if (method instanceof KotlinLightMethod) {
            JetDeclaration declaration = ((KotlinLightMethod) method).getOrigin();
            if (declaration instanceof JetNamedFunction) {
                result.add(((JetNamedFunction) declaration).getValueParameters().get(parameterIndex));
            }
        }
        else {
            result.add(method.getParameterList().getParameters()[parameterIndex]);
        }
    }
}
