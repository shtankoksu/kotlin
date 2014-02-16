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

package org.jetbrains.jet.lang.resolve.objc;

import jet.Function0;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.descriptors.annotations.Annotations;
import org.jetbrains.jet.lang.descriptors.impl.MutablePackageFragmentDescriptor;
import org.jetbrains.jet.lang.descriptors.impl.PackageLikeBuilder;
import org.jetbrains.jet.lang.descriptors.impl.SimpleFunctionDescriptorImpl;
import org.jetbrains.jet.lang.descriptors.impl.ValueParameterDescriptorImpl;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.jet.lang.resolve.name.SpecialNames;
import org.jetbrains.jet.lang.resolve.objc.builtins.ObjCBuiltIns;
import org.jetbrains.jet.lang.resolve.scopes.WritableScope;
import org.jetbrains.jet.lang.types.JetType;

import java.util.*;

import static org.jetbrains.jet.lang.descriptors.impl.PackageLikeBuilder.ClassObjectStatus;
import static org.jetbrains.jet.lang.resolve.objc.ObjCIndex.*;

public class ObjCDescriptorResolver {
    private static final String PROTOCOL_NAME_SUFFIX = "Protocol";

    private final ObjCTypeResolver typeResolver;

    private final MutablePackageFragmentDescriptor objcPackage;
    private final Map<String, Name> protocolNames = new HashMap<String, Name>();

    public ObjCDescriptorResolver(@NotNull MutablePackageFragmentDescriptor objcPackage) {
        this.objcPackage = objcPackage;
        this.typeResolver = new ObjCTypeResolver(objcPackage);
    }

    public void processTranslationUnit(@NotNull TranslationUnit tu) {
        calculateProtocolNames(tu);

        WritableScope scope = objcPackage.getMemberScope();

        List<ObjCClassDescriptor> classes = new ArrayList<ObjCClassDescriptor>(tu.getClassCount() + tu.getProtocolCount());

        for (ObjCClass clazz : tu.getClassList()) {
            ObjCClassDescriptor descriptor = resolveClass(clazz);
            classes.add(descriptor);
            scope.addClassifierAlias(descriptor.getName(), descriptor);

            ObjCClassDescriptor metaclass = resolveMetaclass(descriptor, clazz.getMethodList());
            classes.add(metaclass);
            scope.addClassifierAlias(metaclass.getName(), metaclass);

            resolveClassObject(descriptor, metaclass);
        }

        for (ObjCProtocol protocol : tu.getProtocolList()) {
            ObjCClassDescriptor descriptor = resolveProtocol(protocol);
            classes.add(descriptor);
            scope.addClassifierAlias(descriptor.getName(), descriptor);

            ObjCClassDescriptor metaclass = resolveMetaclass(descriptor, protocol.getMethodList());
            classes.add(metaclass);
            scope.addClassifierAlias(metaclass.getName(), metaclass);
        }

        for (ObjCCategory category : tu.getCategoryList()) {
            ObjCClassDescriptor descriptor = resolveCategory(category);
            classes.add(descriptor);
            scope.addClassifierAlias(descriptor.getName(), descriptor);

            ObjCClassDescriptor metaclass = resolveMetaclass(descriptor, category.getMethodList());
            classes.add(metaclass);
            scope.addClassifierAlias(metaclass.getName(), metaclass);
        }

        for (ObjCClassDescriptor descriptor : classes) {
            descriptor.initialize();

            ObjCClassDescriptor classObject = descriptor.getClassObjectDescriptor();
            if (classObject != null) {
                classObject.initialize();
            }
        }

        new ObjCOverrideResolver().process(classes);

        for (ObjCClassDescriptor descriptor : classes) {
            descriptor.lockScopes();
        }
    }

    private void calculateProtocolNames(@NotNull TranslationUnit tu) {
        Set<Name> existingNames = new HashSet<Name>();
        for (ObjCClass clazz : tu.getClassList()) {
            existingNames.add(Name.identifier(clazz.getName()));
        }

        for (ObjCProtocol protocol : tu.getProtocolList()) {
            String protocolName = protocol.getName();
            Name name = Name.identifier(protocolName);
            if (existingNames.contains(name)) {
                // Since Objective-C classes and protocols exist in different namespaces and Kotlin classes and traits don't,
                // we invent a new name here for the trait when a class with the same name exists already
                // TODO: handle collisions (where both classes X and XProtocol and a protocol X exist)
                name = Name.identifier(protocolName + PROTOCOL_NAME_SUFFIX);
            }

            protocolNames.put(protocolName, name);
            existingNames.add(name);
        }
    }

    @NotNull
    private Name nameForProtocol(@NotNull String protocolName) {
        return protocolNames.get(protocolName);
    }

    @NotNull
    private ObjCClassDescriptor resolveClass(@NotNull ObjCClass clazz) {
        Name name = Name.identifier(clazz.getName());

        List<JetType> supertypes = new ArrayList<JetType>(clazz.getProtocolCount() + clazz.getCategoryCount() + 1);
        if (clazz.hasBaseClass()) {
            Name baseName = Name.identifier(clazz.getBaseClass());
            JetType supertype = typeResolver.createTypeForClass(baseName);
            supertypes.add(supertype);
        }
        else {
            supertypes.add(ObjCBuiltIns.getInstance().getObjCObjectClass().getDefaultType());
        }

        for (String baseProtocolName : clazz.getProtocolList()) {
            Name baseName = nameForProtocol(baseProtocolName);
            JetType supertype = typeResolver.createTypeForClass(baseName);
            supertypes.add(supertype);
        }

        for (String categoryName : clazz.getCategoryList()) {
            Name baseName = Name.identifier(categoryName);
            JetType supertype = typeResolver.createTypeForClass(baseName);
            supertypes.add(supertype);
        }

        ObjCClassDescriptor descriptor = new ObjCClassDescriptor(objcPackage, ClassKind.CLASS, Modality.OPEN, name, supertypes);
        addMethodsToClassScope(clazz.getMethodList(), descriptor, MethodKind.INSTANCE_METHOD);

        return descriptor;
    }

    @NotNull
    private ObjCClassDescriptor resolveProtocol(@NotNull ObjCProtocol protocol) {
        Name name = nameForProtocol(protocol.getName());

        List<JetType> supertypes = new ArrayList<JetType>(protocol.getBaseProtocolCount());
        for (String baseProtocolName : protocol.getBaseProtocolList()) {
            Name baseName = nameForProtocol(baseProtocolName);
            JetType supertype = typeResolver.createTypeForClass(baseName);
            supertypes.add(supertype);
        }

        ObjCClassDescriptor descriptor = new ObjCClassDescriptor(objcPackage, ClassKind.TRAIT, Modality.ABSTRACT, name, supertypes);
        addMethodsToClassScope(protocol.getMethodList(), descriptor, MethodKind.INSTANCE_METHOD);

        return descriptor;
    }

    @NotNull
    private ObjCClassDescriptor resolveCategory(@NotNull ObjCCategory category) {
        Name name = Name.identifier(category.getName());

        List<JetType> supertypes = new ArrayList<JetType>(category.getBaseProtocolCount());
        for (String baseProtocolName : category.getBaseProtocolList()) {
            Name baseName = nameForProtocol(baseProtocolName);
            JetType supertype = typeResolver.createTypeForClass(baseName);
            supertypes.add(supertype);
        }

        ObjCClassDescriptor descriptor = new ObjCClassDescriptor(objcPackage, ClassKind.TRAIT, Modality.ABSTRACT, name, supertypes);
        addMethodsToClassScope(category.getMethodList(), descriptor, MethodKind.INSTANCE_METHOD);

        return descriptor;
    }

    private static void resolveClassObject(@NotNull ObjCClassDescriptor descriptor, @NotNull ObjCClassDescriptor metaclass) {
        assert descriptor.getKind() == ClassKind.CLASS : "Class objects exist only for Objective-C classes: " + descriptor;

        Name name = SpecialNames.getClassObjectName(descriptor.getName());

        Collection<JetType> supertypes = Arrays.asList(
                ObjCBuiltIns.getInstance().getObjCClassClass().getDefaultType(),
                metaclass.getDefaultType(),
                new DeferredHierarchyRootType(descriptor)
        );

        ObjCClassDescriptor classObject = new ObjCClassDescriptor(descriptor, ClassKind.CLASS_OBJECT, Modality.FINAL, name, supertypes);

        ClassObjectStatus result = descriptor.getBuilder().setClassObjectDescriptor(classObject);
        assert result == ClassObjectStatus.OK : result;
    }

    private static class DeferredHierarchyRootType extends ObjCDeferredType {
        public DeferredHierarchyRootType(@NotNull final ObjCClassDescriptor descriptor) {
            super(new Function0<JetType>() {
                @Override
                public JetType invoke() {
                    return getHierarchyRoot(descriptor).getDefaultType();
                }

                @NotNull
                private ObjCClassDescriptor getHierarchyRoot(@NotNull ObjCClassDescriptor descriptor) {
                    // If there's ObjCObject in the immediate supertypes of this class, it's a hierarchy root
                    Collection<JetType> supertypes = descriptor.getSupertypes();
                    for (JetType supertype : supertypes) {
                        if (supertype.getConstructor().getDeclarationDescriptor() == ObjCBuiltIns.getInstance().getObjCObjectClass()) {
                            return descriptor;
                        }
                    }

                    // Otherwise there's exactly one class (kind == CLASS) in its supertypes, for which we calculate the root recursively
                    ObjCClassDescriptor superclass = null;
                    for (JetType supertype : supertypes) {
                        ClassifierDescriptor declaration = supertype.getConstructor().getDeclarationDescriptor();
                        if (declaration instanceof ObjCClassDescriptor) {
                            ObjCClassDescriptor objcDescriptor = (ObjCClassDescriptor) declaration;
                            if (objcDescriptor.getKind() == ClassKind.CLASS) {
                                assert superclass == null : "More than one superclass for Obj-C class: " + descriptor;
                                superclass = objcDescriptor;
                            }
                        }
                    }
                    assert superclass != null : "No superclass for Obj-C class: " + descriptor;

                    return getHierarchyRoot(superclass);
                }
            });
        }
    }

    @NotNull
    private ObjCMetaclassDescriptor resolveMetaclass(@NotNull ObjCClassDescriptor descriptor, @NotNull List<ObjCMethod> methods) {
        List<JetType> supertypes = createDeferredSupertypesForMetaclass(descriptor);

        ObjCMetaclassDescriptor metaclass = new ObjCMetaclassDescriptor(descriptor, supertypes);
        addMethodsToClassScope(methods, metaclass, MethodKind.CLASS_METHOD);

        return metaclass;
    }

    @NotNull
    private List<JetType> createDeferredSupertypesForMetaclass(@NotNull ObjCClassDescriptor descriptor) {
        List<JetType> supertypes = new ArrayList<JetType>(1);
        for (JetType supertype : descriptor.getLazySupertypes()) {
            if (supertype instanceof ObjCClassType) {
                Name supertypeName = ((ObjCClassType) supertype).getClassName();
                Name superMetaName = ObjCMetaclassDescriptor.getMetaclassName(supertypeName);
                supertypes.add(typeResolver.createTypeForClass(superMetaName));
            }
        }
        return supertypes;
    }

    private enum MethodKind {
        CLASS_METHOD,
        INSTANCE_METHOD;

        public boolean isKind(@NotNull ObjCMethod method) {
            return method.getClassMethod() == (this == CLASS_METHOD);
        }
    }

    private void addMethodsToClassScope(
            @NotNull List<ObjCMethod> methods,
            @NotNull ObjCClassDescriptor descriptor,
            @NotNull MethodKind kind
    ) {
        PackageLikeBuilder builder = descriptor.getBuilder();
        for (ObjCMethod method : methods) {
            if (kind.isKind(method)) {
                SimpleFunctionDescriptor functionDescriptor = resolveMethod(method, descriptor);
                builder.addFunctionDescriptor(functionDescriptor);
            }
        }
    }

    @NotNull
    private SimpleFunctionDescriptor resolveMethod(@NotNull ObjCMethod method, @NotNull ObjCClassDescriptor containingClass) {
        Function function = method.getFunction();
        SimpleFunctionDescriptorImpl descriptor = new ObjCMethodDescriptor(containingClass, Annotations.EMPTY, function.getName());

        int params = function.getParameterCount();
        List<ValueParameterDescriptor> valueParameters = new ArrayList<ValueParameterDescriptor>(params);
        for (int i = 0; i < params; i++) {
            ValueParameterDescriptor parameter = resolveFunctionParameter(function.getParameter(i), descriptor, i);
            valueParameters.add(parameter);
        }

        JetType returnType = typeResolver.resolveType(function.getReturnType());

        descriptor.initialize(
                /* receiverParameterType */ null,
                containingClass.getThisAsReceiverParameter(),
                Collections.<TypeParameterDescriptor>emptyList(),
                valueParameters,
                returnType,
                Modality.OPEN,
                Visibilities.PUBLIC
        );

        return descriptor;
    }

    @NotNull
    private ValueParameterDescriptor resolveFunctionParameter(
            @NotNull Function.Parameter parameter,
            @NotNull FunctionDescriptor containingFunction,
            int index
    ) {
        Name name = Name.identifier(parameter.getName());
        return new ValueParameterDescriptorImpl(
                containingFunction,
                index,
                Annotations.EMPTY,
                name,
                typeResolver.resolveType(parameter.getType()),
                /* declaresDefaultValue */ false,
                /* varargElementType */ null
        );
    }
}
