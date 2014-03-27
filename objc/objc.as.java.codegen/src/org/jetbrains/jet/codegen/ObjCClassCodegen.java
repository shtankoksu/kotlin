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

package org.jetbrains.jet.codegen;

import jet.objc.Native;
import jet.objc.ObjCObject;
import jet.objc.helpers.HelpersPackage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.asm4.ClassWriter;
import org.jetbrains.asm4.MethodVisitor;
import org.jetbrains.asm4.Type;
import org.jetbrains.asm4.commons.InstructionAdapter;
import org.jetbrains.asm4.commons.Method;
import org.jetbrains.jet.codegen.state.JetTypeMapper;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.resolve.DescriptorUtils;
import org.jetbrains.jet.lang.resolve.java.JvmAbi;
import org.jetbrains.jet.lang.resolve.objc.ObjCMethodDescriptor;
import org.jetbrains.jet.lang.resolve.scopes.JetScope;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.lang.types.TypeProjection;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.jetbrains.asm4.Opcodes.*;
import static org.jetbrains.asm4.Type.*;

public class ObjCClassCodegen {
    public static final String NATIVE = Type.getType(Native.class).getInternalName();
    public static final String NATIVE_HELPERS = Type.getType(HelpersPackage.class).getInternalName();

    public static final Type JL_OBJECT_TYPE = Type.getType(Object.class);
    public static final Type JL_STRING_TYPE = Type.getType(String.class);

    public static final Type OBJC_OBJECT_TYPE = Type.getType(ObjCObject.class);

    public static final String OBJC_SEND_MESSAGE_DESCRIPTOR =
            getMethodDescriptor(JL_OBJECT_TYPE, JL_STRING_TYPE, OBJC_OBJECT_TYPE, JL_STRING_TYPE, Type.getType(Object[].class));

    private final JetTypeMapper typeMapper;
    private final ClassDescriptor descriptor;
    private final File dylib;

    private final ClassWriter cw;
    private final Type asmType;

    private final Type classObjectAsmType;

    private final Type superClassAsmType;
    private final String[] superInterfaceNames;

    public ObjCClassCodegen(@NotNull JetTypeMapper typeMapper, @NotNull ClassDescriptor descriptor, @NotNull File dylib) {
        this.typeMapper = typeMapper;
        this.descriptor = descriptor;
        this.dylib = dylib;

        cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        asmType = typeMapper.mapClass(descriptor);

        ClassDescriptor classObject = descriptor.getClassObjectDescriptor();
        classObjectAsmType = classObject != null ? typeMapper.mapType(classObject) : null;

        superClassAsmType = computeSuperClassAsmType();
        superInterfaceNames = computeSuperInterfaceNames();
    }

    private interface MethodCodegen {
        void generate(@NotNull InstructionAdapter v);
    }

    private void newMethod(int flags, @NotNull String name, @NotNull String descriptor, @NotNull MethodCodegen codegen) {
        if (this.descriptor.getKind() == ClassKind.TRAIT) return;

        MethodVisitor mv = cw.visitMethod(flags, name, descriptor, null, null);
        mv.visitCode();
        codegen.generate(new InstructionAdapter(mv));
        mv.visitMaxs(-1, -1);
        mv.visitEnd();
    }

    @NotNull
    public byte[] generateClass() {
        cw.visit(V1_6,
                 computeAccessFlagsForClass(),
                 asmType.getInternalName(),
                 null,
                 superClassAsmType.getInternalName(),
                 superInterfaceNames
        );

        cw.visitSource(null, null);

        if (classObjectAsmType != null) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, JvmAbi.CLASS_OBJECT_FIELD, classObjectAsmType.getDescriptor(), null, null);
        }

        generateStaticInitializer();

        generateConstructor();

        JetScope scope = descriptor.getMemberScope(Collections.<TypeProjection>emptyList());
        for (DeclarationDescriptor member : scope.getAllDescriptors()) {
            if (member instanceof FunctionDescriptor) {
                generateMethod((FunctionDescriptor) member);
            }
        }

        cw.visitEnd();

        return cw.toByteArray();
    }

    private int computeAccessFlagsForClass() {
        int access;
        if (descriptor.getKind() == ClassKind.TRAIT) {
            access = ACC_ABSTRACT | ACC_INTERFACE;
        }
        else {
            access = ACC_SUPER;
        }
        return access | ACC_PUBLIC;
    }

    @NotNull
    private Type computeSuperClassAsmType() {
        for (JetType supertype : descriptor.getTypeConstructor().getSupertypes()) {
            ClassifierDescriptor superDescriptor = supertype.getConstructor().getDeclarationDescriptor();
            assert superDescriptor instanceof ClassDescriptor : "Supertype is not a class for Obj-C descriptor: " + descriptor;
            if (((ClassDescriptor) superDescriptor).getKind() == ClassKind.CLASS) {
                return typeMapper.mapType(supertype);
            }
        }

        if (descriptor.getKind() == ClassKind.TRAIT) {
            return JL_OBJECT_TYPE;
        }

        return OBJC_OBJECT_TYPE;
    }

    @NotNull
    private String[] computeSuperInterfaceNames() {
        Collection<JetType> supertypes = descriptor.getTypeConstructor().getSupertypes();

        List<String> superInterfacesNames = new ArrayList<String>(supertypes.size());
        for (JetType supertype : supertypes) {
            ClassifierDescriptor superDescriptor = supertype.getConstructor().getDeclarationDescriptor();
            assert superDescriptor instanceof ClassDescriptor : "Supertype is not a class for Obj-C descriptor: " + descriptor;
            if (((ClassDescriptor) superDescriptor).getKind() == ClassKind.TRAIT) {
                superInterfacesNames.add(typeMapper.mapClass(superDescriptor).getInternalName());
            }
        }

        return superInterfacesNames.toArray(new String[superInterfacesNames.size()]);
    }

    private void generateStaticInitializer() {
        newMethod(ACC_PUBLIC | ACC_STATIC, "<clinit>", getMethodDescriptor(VOID_TYPE), new MethodCodegen() {
            @Override
            public void generate(@NotNull InstructionAdapter v) {
                if (classObjectAsmType != null) {
                    v.anew(classObjectAsmType);
                    v.dup();
                    v.invokespecial(classObjectAsmType.getInternalName(), "<init>", "()V");
                    v.putstatic(asmType.getInternalName(), JvmAbi.CLASS_OBJECT_FIELD, classObjectAsmType.getDescriptor());
                }
                v.visitLdcInsn(dylib.toString());
                v.invokestatic(NATIVE_HELPERS, "loadLibrary", getMethodDescriptor(VOID_TYPE, JL_STRING_TYPE));
                v.areturn(VOID_TYPE);
            }
        });
    }

    private void generateConstructor() {
        final String objcObjectConstructor = getMethodDescriptor(VOID_TYPE, LONG_TYPE);
        if (descriptor.getKind() == ClassKind.CLASS_OBJECT) {
            newMethod(ACC_PUBLIC, "<init>", getMethodDescriptor(VOID_TYPE), new MethodCodegen() {
                @Override
                public void generate(@NotNull InstructionAdapter v) {
                    v.load(0, asmType);
                    v.visitLdcInsn(descriptor.getContainingDeclaration().getName().asString());
                    v.invokestatic(NATIVE, "objc_getClass", getMethodDescriptor(LONG_TYPE, JL_STRING_TYPE));
                    v.invokespecial(superClassAsmType.getInternalName(), "<init>", objcObjectConstructor);
                    v.areturn(VOID_TYPE);
                }
            });
        }
        else {
            newMethod(ACC_PUBLIC, "<init>", objcObjectConstructor, new MethodCodegen() {
                @Override
                public void generate(@NotNull InstructionAdapter v) {
                    v.load(0, asmType);
                    v.load(1, LONG_TYPE);
                    v.invokespecial(superClassAsmType.getInternalName(), "<init>", objcObjectConstructor);
                    v.areturn(VOID_TYPE);
                }
            });
        }
    }

    private void generateMethod(@NotNull final FunctionDescriptor method) {
        if (!method.getKind().isReal() && getDeclaringClassOfMethod(method).getKind() != ClassKind.TRAIT) {
            // Don't generate code for fake overrides, unless this method is declared in a trait.
            // This is needed since we don't generate TImpl methods for metaclasses and categories, which are traits
            // (this, in turn, is not needed since there's only one instance of both a metaclass and a category).
            return;
        }

        if ("finalize".equals(method.getName().asString()) && method.getValueParameters().isEmpty()) {
            // Generating -[NSObject finalize] will mess with the semantics of the method with the same name in JVM.
            // In particular, when a JVM object is GC'd and finalize is called, it'll call Objective-C's finalize,
            // which is a bad thing to do and will break on Objective-C libraries compiled without GC support (-fno-objc-gc)
            return;
        }

        final Method asmMethod = typeMapper.mapSignature(method).getAsmMethod();

        newMethod(ACC_PUBLIC, asmMethod.getName(), asmMethod.getDescriptor(), new MethodCodegen() {
            @Override
            public void generate(@NotNull InstructionAdapter v) {
                Type returnType = asmMethod.getReturnType();
                v.visitLdcInsn(getTypeReflectString(returnType));

                v.load(0, asmType);

                v.visitLdcInsn(getObjCMethodName(method));

                putArgumentsAsNativeValueArray(v);

                v.invokestatic(NATIVE, "objc_msgSend", OBJC_SEND_MESSAGE_DESCRIPTOR);

                StackValue.coerce(JL_OBJECT_TYPE, returnType, v);

                v.areturn(returnType);
            }

            private void putArgumentsAsNativeValueArray(@NotNull InstructionAdapter v) {
                List<ValueParameterDescriptor> parameters = method.getValueParameters();

                v.iconst(parameters.size());
                v.newarray(JL_OBJECT_TYPE);

                int localIndex = 1;
                for (ValueParameterDescriptor parameter : parameters) {
                    v.dup();
                    v.iconst(parameter.getIndex());

                    Type asmType = typeMapper.mapType(parameter.getType());
                    StackValue.local(localIndex, asmType).put(JL_OBJECT_TYPE, v);
                    localIndex += asmType.getSize();

                    v.astore(JL_OBJECT_TYPE);
                }
            }
        });
    }

    @NotNull
    private static ClassDescriptor getDeclaringClassOfMethod(@NotNull FunctionDescriptor method) {
        DeclarationDescriptor declaration = DescriptorUtils.unwrapFakeOverride(method).getContainingDeclaration();
        assert declaration instanceof ClassDescriptor : "Obj-C method isn't declared in a class: " + method;
        return (ClassDescriptor) declaration;
    }

    @NotNull
    private static String getObjCMethodName(@NotNull FunctionDescriptor method) {
        FunctionDescriptor unwrapped = DescriptorUtils.unwrapFakeOverride(method);
        assert unwrapped instanceof ObjCMethodDescriptor : "Obj-C method original is not an Obj-C method: " + method + ", " + unwrapped;
        return ((ObjCMethodDescriptor) unwrapped).getObjCName();
    }

    @NotNull
    private static String getTypeReflectString(@NotNull Type type) {
        switch (type.getSort()) {
            case Type.VOID: return "void";
            case Type.BOOLEAN: return "boolean";
            case Type.CHAR: return "char";
            case Type.BYTE: return "byte";
            case Type.SHORT: return "short";
            case Type.INT: return "int";
            case Type.FLOAT: return "float";
            case Type.LONG: return "long";
            case Type.DOUBLE: return "double";
            case Type.ARRAY: throw new IllegalStateException("Unsupported type: " + type);
        }
        assert type.getSort() == Type.OBJECT : "Unsupported type sort: " + type;

        String className = type.getClassName();
        if ("jet.objc.ObjCClass".equals(className)) {
            return "interface " + className;
        }

        return "class " + className;
    }
}
