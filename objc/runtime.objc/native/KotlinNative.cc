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

#include "KotlinNative.h"

#include <dlfcn.h>

#include <ffi.h>

#include <objc/message.h>
#include <objc/objc.h>
#include <objc/runtime.h>

#include <cassert>
#include <cstdio>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

const std::string OBJC_PACKAGE_PREFIX = "objc/";

// TODO: hide everything util under a namespace
// TODO: process all possible JNI errors
// TODO: delete local JNI references where there can be too many of them
// TODO: fail gracefully if any class/method/field is not found

class AutoJString {
    JNIEnv *const env;
    const jstring jstr;
    const char *const chars;

    public:

    AutoJString(JNIEnv *env, jstring jstr):
        env(env),
        jstr(jstr),
        chars(env->GetStringUTFChars(jstr, 0))
    { }

    ~AutoJString() {
        env->ReleaseStringUTFChars(jstr, chars);
    }

    const char *const str() const {
        return chars;
    }
};

// --------------------------------------------------------
// Classes, methods, fields cache
// --------------------------------------------------------

class JVMDeclarationsCache {
    JNIEnv *const env;

    public:

    jclass objcObjectClass;
    jclass objcSelectorClass;
    jclass pointerClass;
    jclass nilClass;

    jclass integerClass;
    jclass longClass;
    jclass shortClass;
    jclass floatClass;
    jclass doubleClass;
    jclass characterClass;
    jclass booleanClass;
    jclass unitClass;

    jfieldID objcObjectPointerField;
    jfieldID pointerPeerField;
    jfieldID nilInstanceField;

    jmethodID objectGetClassMethod;
    jmethodID objectToStringMethod;
    jmethodID classGetNameMethod;
    jmethodID classGetDeclaredMethodsMethod;
    jmethodID classIsAssignableFromMethod;
    jmethodID methodIsBridgeMethod;
    jmethodID methodGetReturnTypeMethod;
    jmethodID methodGetParameterTypesMethod;
    jfieldID methodNameField;

    jfieldID integerValueField;
    jfieldID longValueField;
    jfieldID shortValueField;
    jfieldID floatValueField;
    jfieldID doubleValueField;
    jfieldID characterValueField;
    jfieldID booleanValueField;
    jmethodID integerValueOfMethod;
    jmethodID longValueOfMethod;
    jmethodID shortValueOfMethod;
    jmethodID floatValueOfMethod;
    jmethodID doubleValueOfMethod;
    jmethodID characterValueOfMethod;
    jmethodID booleanValueOfMethod;

    jmethodID objcSelectorConstructor;
    jmethodID pointerConstructor;

    JVMDeclarationsCache(JNIEnv *env): env(env) {
        objcObjectClass = findClass("jet/objc/ObjCObject");
        objcSelectorClass = findClass("jet/objc/ObjCSelector");
        pointerClass = findClass("jet/objc/Pointer");
        nilClass = findClass("jet/objc/Nil");

        objcObjectPointerField = env->GetFieldID(objcObjectClass, "pointer", "J");
        pointerPeerField = env->GetFieldID(pointerClass, "peer", "J");
        nilInstanceField = env->GetStaticFieldID(nilClass, "INSTANCE", "Ljet/objc/Nil;");

        integerClass = findClass("java/lang/Integer");
        longClass = findClass("java/lang/Long");
        shortClass = findClass("java/lang/Short");
        floatClass = findClass("java/lang/Float");
        doubleClass = findClass("java/lang/Double");
        characterClass = findClass("java/lang/Character");
        booleanClass = findClass("java/lang/Boolean");
        unitClass = findClass("kotlin/Unit");

        jclass objectClass = findClass("java/lang/Object");
        jclass classClass = findClass("java/lang/Class");
        jclass methodClass = findClass("java/lang/reflect/Method");
        objectGetClassMethod = env->GetMethodID(objectClass, "getClass", "()Ljava/lang/Class;");
        objectToStringMethod = env->GetMethodID(objectClass, "toString", "()Ljava/lang/String;");
        classGetNameMethod = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
        classGetDeclaredMethodsMethod = env->GetMethodID(classClass, "getDeclaredMethods", "()[Ljava/lang/reflect/Method;");
        classIsAssignableFromMethod = env->GetMethodID(classClass, "isAssignableFrom", "(Ljava/lang/Class;)Z");
        methodIsBridgeMethod = env->GetMethodID(methodClass, "isBridge", "()Z");
        methodGetReturnTypeMethod = env->GetMethodID(methodClass, "getReturnType", "()Ljava/lang/Class;");
        methodGetParameterTypesMethod = env->GetMethodID(methodClass, "getParameterTypes", "()[Ljava/lang/Class;");
        methodNameField = env->GetFieldID(methodClass, "name", "Ljava/lang/String;");

        integerValueField = env->GetFieldID(integerClass, "value", "I");
        longValueField = env->GetFieldID(longClass, "value", "J");
        shortValueField = env->GetFieldID(shortClass, "value", "S");
        floatValueField = env->GetFieldID(floatClass, "value", "F");
        doubleValueField = env->GetFieldID(doubleClass, "value", "D");
        characterValueField = env->GetFieldID(characterClass, "value", "C");
        booleanValueField = env->GetFieldID(booleanClass, "value", "Z");

        integerValueOfMethod = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
        longValueOfMethod = env->GetStaticMethodID(longClass, "valueOf", "(J)Ljava/lang/Long;");
        shortValueOfMethod = env->GetStaticMethodID(shortClass, "valueOf", "(S)Ljava/lang/Short;");
        floatValueOfMethod = env->GetStaticMethodID(floatClass, "valueOf", "(F)Ljava/lang/Float;");
        doubleValueOfMethod = env->GetStaticMethodID(doubleClass, "valueOf", "(D)Ljava/lang/Double;");
        characterValueOfMethod = env->GetStaticMethodID(characterClass, "valueOf", "(C)Ljava/lang/Character;");
        booleanValueOfMethod = env->GetStaticMethodID(booleanClass, "valueOf", "(Z)Ljava/lang/Boolean;");

        objcSelectorConstructor = env->GetMethodID(objcSelectorClass, "<init>", "(J)V");
        pointerConstructor = env->GetMethodID(pointerClass, "<init>", "(J)V");
    }

    ~JVMDeclarationsCache() {
        // TODO: delete global references
    }

    private:

    jclass findClass(const char *name) {
        jclass localRef = env->FindClass(name);
        // TODO: figure out why JNA uses weak global references for this
        jclass globalRef = (jclass) env->NewGlobalRef(localRef);
        env->DeleteLocalRef(localRef);
        return globalRef;
    }
};

JVMDeclarationsCache *cache;
id autoReleasePool;

id createAutoreleasePool() {
    static Class autoreleasePoolClass = (Class) objc_getClass("NSAutoreleasePool");
    static SEL alloc = sel_registerName("alloc");
    id pool = objc_msgSend((id) autoreleasePoolClass, alloc);
    static SEL init = sel_registerName("init");
    return objc_msgSend(pool, init);
}

void drainAutoreleasePool(id pool) {
    static SEL drain = sel_registerName("drain");
    objc_msgSend(pool, drain);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    // TODO: extract repeating code
    JNIEnv *env;
    int attached = vm->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK;
    if (!attached) {
        if (vm->AttachCurrentThread((void **) &env, 0) != JNI_OK) {
            fprintf(stderr, "Error attaching native thread to VM on load\n");
            return 0;
        }
    }

    cache = new JVMDeclarationsCache(env);

    autoReleasePool = createAutoreleasePool();

    if (!attached) {
        vm->DetachCurrentThread();
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *) {
    JNIEnv *env;
    int attached = vm->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK;
    if (!attached) {
        if (vm->AttachCurrentThread((void **) &env, 0) != JNI_OK) {
            fprintf(stderr, "Error attaching native thread to VM on unload\n");
            return;
        }
    }

    drainAutoreleasePool(autoReleasePool);

    delete cache;

    if (!attached) {
        vm->DetachCurrentThread();
    }
}

std::string getClassGetName(JNIEnv *env, jobject object) {
    jobject classObject = env->CallObjectMethod(object, cache->objectGetClassMethod);
    AutoJString className(env, (jstring) env->CallObjectMethod(classObject, cache->classGetNameMethod));
    return className.str();
}

// --------------------------------------------------------
// Dynamic libraries
// --------------------------------------------------------

JNIEXPORT void JNICALL Java_jet_objc_Native_dlopen(
        JNIEnv *env,
        jclass,
        jstring path
) {
    AutoJString pathStr(env, path);
    if (!dlopen(pathStr.str(), RTLD_GLOBAL)) {
        // TODO: report an error properly
        fprintf(stderr, "Library not found: %s\n", pathStr.str());
        exit(42);
    }
}


// --------------------------------------------------------
// Pointers
// --------------------------------------------------------

JNIEXPORT jlong JNICALL Java_jet_objc_Native_malloc(
        JNIEnv *,
        jclass,
        jlong bytes
) {
    void *memory = malloc(bytes);
    return *(jlong *)&memory;
}

JNIEXPORT void JNICALL Java_jet_objc_Native_free(
        JNIEnv *,
        jclass,
        jlong pointer
) {
    free(*(void **)&pointer);
}

JNIEXPORT jlong JNICALL Java_jet_objc_Native_getWord(
        JNIEnv *,
        jclass,
        jlong pointer
) {
    return *(jlong *)pointer;
}

JNIEXPORT void JNICALL Java_jet_objc_Native_setWord(
        JNIEnv *,
        jclass,
        jlong pointer,
        jlong value
) {
    *(jlong *)pointer = value;
}


// --------------------------------------------------------
// Objective-C
// --------------------------------------------------------

JNIEXPORT jlong JNICALL Java_jet_objc_Native_objc_1getClass(
        JNIEnv *env,
        jclass,
        jstring name
) {
    AutoJString nameStr(env, name);
    return (jlong) objc_getClass(nameStr.str());
}


void *createNativeClosureForFunction(JNIEnv *env, jobject function);

bool isKotlinFunction(JNIEnv *env, jobject object) {
    // Check if object implements any FunctionN interface
    // TODO: check for some special annotation or a common superclass of FunctionN instead
    for (unsigned i = 0; i < 23; i++) {
        std::ostringstream className;
        className << "kotlin/Function" << i;
        std::string nameStr = className.str();
        jclass clazz = env->FindClass(nameStr.c_str());
        if (env->IsInstanceOf(object, clazz)) {
            return true;
        }
    }
    return false;
}

void coerceJVMToNative(JNIEnv *env, jobject object, void *ret) {
    std::string nameStr = getClassGetName(env, object);
    const char *name = nameStr.c_str();
    if (!strncmp(name, "java.lang.", 10)) {
        const char *simple = name + 10;
        if (!strcmp(simple, "Integer")) {
            *(int *) ret = env->GetIntField(object, cache->integerValueField);
        } else if (!strcmp(simple, "Long")) {
            *(long *) ret = env->GetLongField(object, cache->longValueField);
        } else if (!strcmp(simple, "Short")) {
            *(short *) ret = env->GetShortField(object, cache->shortValueField);
        } else if (!strcmp(simple, "Float")) {
            *(float *) ret = env->GetFloatField(object, cache->floatValueField);
        } else if (!strcmp(simple, "Double")) {
            *(double *) ret = env->GetDoubleField(object, cache->doubleValueField);
        } else if (!strcmp(simple, "Character")) {
            *(char *) ret = env->GetCharField(object, cache->characterValueField);
        } else if (!strcmp(simple, "Boolean")) {
            *(BOOL *) ret = env->GetBooleanField(object, cache->booleanValueField);
        } else {
            fprintf(stderr, "Unsupported JVM primitive wrapper type: %s\n", name);
            *(void **) ret = NULL;
        }
    } else if (!strcmp(name, "kotlin.Unit")) {
        *(void **) ret = NULL;
    } else if (env->IsInstanceOf(object, cache->pointerClass)) {
        *(long *) ret = env->GetLongField(object, cache->pointerPeerField);
    } else if (env->IsInstanceOf(object, cache->objcObjectClass)) {
        *(long *) ret = env->GetLongField(object, cache->objcObjectPointerField);
    } else if (isKotlinFunction(env, object)) {
        *(void **) ret = createNativeClosureForFunction(env, object);
    } else {
        fprintf(stderr, "Unsupported JVM object type: %s\n", name);
        *(void **) ret = NULL;
    }
}

void coerceArrayOfJVMToNative(JNIEnv *env, jobjectArray argArray, std::vector<void *>& args) {
    jsize length = env->GetArrayLength(argArray);
    args.reserve(length);

    for (jsize i = 0; i < length; i++) {
        jobject argObj = env->GetObjectArrayElement(argArray, i);
        void *arg;
        coerceJVMToNative(env, argObj, &arg);
        args.push_back(arg);
    }
}

jobject createMirrorObjectOfClass(JNIEnv *env, id object, jclass jvmClass) {
    /*
    // TODO: retain the object here, release in finalize()
    static SEL retain = sel_registerName("retain");
    objc_msgSend(object, retain);
    */

    jmethodID constructor = env->GetMethodID(jvmClass, "<init>", "(J)V");
    return env->NewObject(jvmClass, constructor, object);
}

// These qualifiers (Objective-C Runtime Type Encodings, Table 6-2) are discarded when decoding types:
// r const, n in, N inout, o out, O bycopy, R byref, V oneway
const std::string IGNORED_TYPE_ENCODINGS = "rnNoORV";

ffi_type *ffiTypeFromEncoding(char *encoding) {
    while (IGNORED_TYPE_ENCODINGS.find(*encoding) != std::string::npos) {
        encoding++;
    }
    switch (*encoding) {
        case _C_CHR: case _C_UCHR: return &ffi_type_schar;
        case _C_INT: case _C_UINT: return &ffi_type_sint;
        case _C_SHT: case _C_USHT: return &ffi_type_sshort;
        case _C_LNG: case _C_ULNG: return &ffi_type_slong;
        case _C_LNG_LNG: case _C_ULNG_LNG: return &ffi_type_sint64;
        case _C_FLT: return &ffi_type_float;
        case _C_DBL: return &ffi_type_double;
        case _C_VOID: return &ffi_type_void;
        // TODO: structs, arrays, other types
        default: return &ffi_type_pointer;
    }
}

enum TypeKind {
    TYPE_VOID,
    TYPE_INT,
    TYPE_LONG,
    TYPE_SHORT,
    TYPE_FLOAT,
    TYPE_DOUBLE,
    TYPE_CHAR,
    TYPE_BOOLEAN,
    TYPE_POINTER,
    TYPE_SELECTOR,
    TYPE_CLASS,
    TYPE_ID,
    TYPE_OBJECT,
};

struct Type {
    TypeKind kind;
    // For kind = TYPE_OBJECT, internal name of the corresponding JVM class (e.g. "objc/NSObject")
    std::string className;

    Type(TypeKind kind, const std::string& className):
        kind(kind),
        className(className)
    { }

    Type(TypeKind kind):
        kind(kind),
        className("")
    { }
};

class MsgSendInvocation {
    const id receiver;
    const SEL selector;
    const std::vector<void *>& args;
    
    Method method;

    public:

    MsgSendInvocation(id receiver, SEL selector, const std::vector<void *>& args):
        receiver(receiver),
        selector(selector),
        args(args)
    {
        Class receiverClass = object_getClass(receiver);
        method = class_getInstanceMethod(receiverClass, selector);
    }

    void *invoke() {
        unsigned numArguments = method_getNumberOfArguments(method);

        std::vector<ffi_type *> argTypes;
        std::vector<void *> argValues;
        calculateArgumentTypesAndValues(numArguments, argTypes, argValues);

        char *returnTypeEncoding = method_copyReturnType(method);
        // TODO: also use Type instead of encodings
        ffi_type *methodReturnType = ffiTypeFromEncoding(returnTypeEncoding);
        free(returnTypeEncoding);

        void (*fun)();
        ffi_type *returnType;
        if (methodReturnType == &ffi_type_double || methodReturnType == &ffi_type_float) {
            // From Objective-C Runtime Reference:
            // "On the i386 platform you must use objc_msgSend_fpret for functions returning non-integral type"
            fun = (void (*)()) objc_msgSend_fpret;
            returnType = &ffi_type_double;
        } else {
            fun = (void (*)()) objc_msgSend;
            returnType = &ffi_type_pointer;
        }

        ffi_cif cif;
        // argTypes[0] won't fail, since there's at least two arguments (id receiver, SEL selector)
        ffi_status status = ffi_prep_cif(&cif, FFI_DEFAULT_ABI, numArguments, returnType, &argTypes[0]);
        if (status != FFI_OK) {
            // TODO: throw a JVM exception
            fprintf(stderr, "ffi_prep_cif failed: %d\n", status);
            exit(42);
        }

        void *result;
        ffi_call(&cif, fun, &result, &argValues[0]);

        return result;
    }

    private:

    void calculateArgumentTypesAndValues(unsigned size, std::vector<ffi_type *>& types, std::vector<void *>& values) {
        types.reserve(size);
        values.reserve(size);

        types.push_back(&ffi_type_pointer);
        values.push_back((void *) &receiver);

        types.push_back(&ffi_type_pointer);
        values.push_back((void *) &selector);

        for (unsigned i = 2; i < size; i++) {
            char *argTypeEncoding = method_copyArgumentType(method, i);
            ffi_type *type = ffiTypeFromEncoding(argTypeEncoding);

            types.push_back(type);
            values.push_back((void *) &args[i-2]);

            free(argTypeEncoding);
        }
    }
};

jobject coerceNativeToJVM(JNIEnv *env, void *value, const Type& type) {
    TypeKind kind = type.kind;

    if (kind == TYPE_VOID) {
        return NULL;
    } else if (kind == TYPE_INT) {
        return env->CallStaticObjectMethod(cache->integerClass, cache->integerValueOfMethod, *(int *) &value);
    } else if (kind == TYPE_LONG) {
        return env->CallStaticObjectMethod(cache->longClass, cache->longValueOfMethod, *(long *) &value);
    } else if (kind == TYPE_SHORT) {
        return env->CallStaticObjectMethod(cache->shortClass, cache->shortValueOfMethod, *(short *) &value);
    } else if (kind == TYPE_FLOAT) {
        return env->CallStaticObjectMethod(cache->floatClass, cache->floatValueOfMethod, *(float *) &value);
    } else if (kind == TYPE_DOUBLE) {
        return env->CallStaticObjectMethod(cache->doubleClass, cache->doubleValueOfMethod, *(double *) &value);
    } else if (kind == TYPE_CHAR) {
        return env->CallStaticObjectMethod(cache->characterClass, cache->characterValueOfMethod, *(char *) &value);
    } else if (kind == TYPE_BOOLEAN) {
        return env->CallStaticObjectMethod(cache->booleanClass, cache->booleanValueOfMethod, *(BOOL *) &value);
    } else if (kind == TYPE_SELECTOR) {
        return env->NewObject(cache->objcSelectorClass, cache->objcSelectorConstructor, value);
    } else if (kind == TYPE_CLASS) {
        if (!value) {
            return env->GetStaticObjectField(cache->nilClass, cache->nilInstanceField);
        }

        // TODO: what if there's no such class object?
        std::string className = OBJC_PACKAGE_PREFIX + object_getClassName((id) value);
        std::string classObjectDescriptor = "L" + className + "$object;";
        jclass clazz = env->FindClass(className.c_str());
        jfieldID classObjectField = env->GetStaticFieldID(clazz, "object$", classObjectDescriptor.c_str());
        return env->GetStaticObjectField(clazz, classObjectField);
    } else if (kind == TYPE_POINTER) {
        return env->NewObject(cache->pointerClass, cache->pointerConstructor, value);
    } else if (kind == TYPE_ID || kind == TYPE_OBJECT) {
        if (!value) {
            if (kind == TYPE_ID) {
                return env->GetStaticObjectField(cache->nilClass, cache->nilInstanceField);
            } else {
                jclass jvmClass = env->FindClass(type.className.c_str());
                return createMirrorObjectOfClass(env, nil, jvmClass);
            }
        }

        id object = (id) value;
        Class clazz = object_getClass(object);

        jclass jvmClass = NULL;
        while (clazz) {
            // TODO: free?
            const char *className = class_getName(clazz);
            std::string fqClassName = OBJC_PACKAGE_PREFIX + className;
            if ((jvmClass = env->FindClass(fqClassName.c_str()))) break;
            env->ExceptionClear();

            clazz = class_getSuperclass(clazz);
        }

        if (!jvmClass) {
            fprintf(stderr, "Class not found for object of class: %s\n", object_getClassName(object));
            // TODO: return new NotFoundObjCClass(className, value) or something
            exit(42);
        }

        return createMirrorObjectOfClass(env, object, jvmClass);
    } else {
        // TODO: throw a JVM exception
        fprintf(stderr, "Unsupported type kind: %d\n", kind);
        exit(42);
    }
}

// TODO: do not always instantiate Type
Type typeFromReflectString(const std::string& name) {
    if (name == "char") return Type(TYPE_CHAR);
    else if (name == "boolean") return Type(TYPE_BOOLEAN);
    else if (name == "int") return Type(TYPE_INT);
    else if (name == "short") return Type(TYPE_SHORT);
    else if (name == "long") return Type(TYPE_LONG);
    else if (name == "float") return Type(TYPE_FLOAT);
    else if (name == "double") return Type(TYPE_DOUBLE);
    else if (name == "void") return Type(TYPE_VOID);
    else if (name == "interface jet.objc.ObjCClass") return Type(TYPE_CLASS);
    else if (name.find("interface kotlin.Function") == 0) {
        // TODO: support returning closures
        fprintf(stderr, "Returning functions from native code is not supported\n");
        exit(42);
    } else {
        static const std::string CLASS_PREFIX = "class ";
        if (name.substr(0, CLASS_PREFIX.length()) != CLASS_PREFIX) {
            fprintf(stderr, "Unsupported JVM type: %s\n", name.c_str());
            exit(42);
        }
        std::string className = name.substr(CLASS_PREFIX.length());

        if (className == "jet.objc.Pointer") return Type(TYPE_POINTER);
        else if (className == "jet.objc.ObjCSelector") return Type(TYPE_SELECTOR);
        else if (className == "jet.objc.ObjCObject") return Type(TYPE_ID);
        else {
            // assert(env->CallBooleanMethod(cache->objcObjectClass, cache->classIsAssignableFromMethod, classObject));
            std::replace(className.begin(), className.end(), '.', '/');
            assert(className.substr(0, OBJC_PACKAGE_PREFIX.length()) == OBJC_PACKAGE_PREFIX);

            return Type(TYPE_OBJECT, className);
        }
    }
}

JNIEXPORT jobject JNICALL Java_jet_objc_Native_objc_1msgSend(
        JNIEnv *env,
        jclass,
        jstring returnTypeDescriptor,
        jobject receiverJObject,
        jstring selectorName,
        jobjectArray argArray
) {
    id receiver = (id) env->GetLongField(receiverJObject, cache->objcObjectPointerField);

    AutoJString selectorNameStr(env, selectorName);
    SEL selector = sel_registerName(selectorNameStr.str());

    std::vector<void *> args;
    coerceArrayOfJVMToNative(env, argArray, args);

    MsgSendInvocation invocation(receiver, selector, args);
    void *result = invocation.invoke();

    AutoJString descriptorJString(env, returnTypeDescriptor);
    Type returnType = typeFromReflectString(descriptorJString.str());

    return coerceNativeToJVM(env, result, returnType);
}

// --------------------------------------------------------
// Closures
// --------------------------------------------------------

struct ClosureData {
    ffi_cif cif;
    ffi_closure *closure;
    void *fun;
    JavaVM *vm;
    jobject function;
    jmethodID invokeMethodID;
    std::vector<Type> argTypes;
    std::vector<ffi_type *> ffiArgTypes;
};

Type typeFromJavaClass(JNIEnv *env, jobject classObject) {
    // TYPE_CLASS is never returned by this method, since every Objective-C class is also an object
    AutoJString name(env, (jstring) env->CallObjectMethod(classObject, cache->objectToStringMethod));
    return typeFromReflectString(name.str());
}

void coerceArrayOfNativeToJVM(JNIEnv *env, void *args[], const std::vector<Type>& argTypes, std::vector<jvalue>& jvmArgs) {
    int size = argTypes.size();
    jvmArgs.reserve(size);

    for (int i = 0; i < size; i++) {
        jvalue value;
        value.l = coerceNativeToJVM(env, *(void **) args[i], argTypes[i]);
        jvmArgs.push_back(value);
    }
}

void closureHandler(ffi_cif *cif, void *ret, void *args[], void *userData) {
    ClosureData *data = (ClosureData *) userData;
    JavaVM *vm = data->vm;
    JNIEnv *env;

    int attached = vm->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK;
    if (!attached) {
        // TODO: test native threads
        if (vm->AttachCurrentThread((void **) &env, 0) != JNI_OK) {
            fprintf(stderr, "Error attaching native thread to VM\n");
            return;
        }
    }

    env->PushLocalFrame(16);

    std::vector<jvalue> jvmArgs;
    coerceArrayOfNativeToJVM(env, args, data->argTypes, jvmArgs);
    jvalue *jvmArgsPtr = jvmArgs.empty() ? NULL : &jvmArgs[0];

    jobject result = env->CallObjectMethodA(data->function, data->invokeMethodID, jvmArgsPtr);
    result = env->PopLocalFrame(result);

    coerceJVMToNative(env, result, ret);

    if (!attached) {
        vm->DetachCurrentThread();
    }

    // TODO: deallocate closure, ClosureData, 'function' global reference, etc.
}

ffi_type *ffiTypeFromTypeKind(TypeKind kind) {
    switch (kind) {
        case TYPE_VOID: return &ffi_type_void;
        case TYPE_INT: return &ffi_type_sint;
        case TYPE_LONG: return &ffi_type_slong;
        case TYPE_SHORT: return &ffi_type_sshort;
        case TYPE_FLOAT: return &ffi_type_float;
        case TYPE_DOUBLE: return &ffi_type_double;
        case TYPE_CHAR: case TYPE_BOOLEAN: return &ffi_type_schar;
        default: return &ffi_type_pointer;
    }
}

jobject reflectMethodFromKotlinFunction(JNIEnv *env, jobject function, bool bridge) {
    jobject classObject = env->CallObjectMethod(function, cache->objectGetClassMethod);
    jobjectArray methods = (jobjectArray) env->CallObjectMethod(classObject, cache->classGetDeclaredMethodsMethod);
    for (jsize i = 0, size = env->GetArrayLength(methods); i < size; i++) {
        jobject method = env->GetObjectArrayElement(methods, i);
        AutoJString name(env, (jstring) env->GetObjectField(method, cache->methodNameField));
        if (!strcmp(name.str(), "invoke")) {
            bool isBridge = env->CallBooleanMethod(method, cache->methodIsBridgeMethod);
            if (isBridge == bridge) return method;
        }
    }

    // TODO: do something meaningful
    std::string className = getClassGetName(env, function);
    fprintf(stderr, "No %s invoke() method in a function literal class: %s\n", bridge ? "bridge" : "non-bridge", className.c_str());
    return NULL;
}

void calculateClosureArgumentTypes(JNIEnv *env, jobject method, std::vector<Type>& argTypes, std::vector<ffi_type *>& ffiArgTypes) {
    jobjectArray parameterTypes = (jobjectArray) env->CallObjectMethod(method, cache->methodGetParameterTypesMethod);
    jsize size = env->GetArrayLength(parameterTypes);
    argTypes.reserve(size);
    ffiArgTypes.reserve(size);

    for (jsize i = 0; i < size; i++) {
        jobject classObject = env->GetObjectArrayElement(parameterTypes, i);
        Type type = typeFromJavaClass(env, classObject);
        argTypes.push_back(type);
        ffi_type *paramType = ffiTypeFromTypeKind(type.kind);
        ffiArgTypes.push_back(paramType);
    }
}

void *createNativeClosureForFunction(JNIEnv *env, jobject function) {
    ClosureData *data = new ClosureData;

    if (jint vm = env->GetJavaVM(&data->vm)) {
        fprintf(stderr, "Error getting Java VM: %d\n", vm);
        return 0;
    }

    data->function = env->NewGlobalRef(function);
    env->DeleteLocalRef(function);

    jobject method = reflectMethodFromKotlinFunction(env, data->function, false);
    jobject returnTypeClassObject = env->CallObjectMethod(method, cache->methodGetReturnTypeMethod);
    Type returnType = typeFromJavaClass(env, returnTypeClassObject);
    ffi_type *ffiReturnType = ffiTypeFromTypeKind(returnType.kind);

    calculateClosureArgumentTypes(env, method, data->argTypes, data->ffiArgTypes);

    ffi_type **argTypes = data->ffiArgTypes.empty() ? NULL : &data->ffiArgTypes[0];
    if (ffi_prep_cif(&data->cif, FFI_DEFAULT_ABI, data->ffiArgTypes.size(), ffiReturnType, argTypes) != FFI_OK) {
        fprintf(stderr, "Error preparing CIF\n");
        return 0;
    }

    data->closure = (ffi_closure *) ffi_closure_alloc(sizeof(ffi_closure), &data->fun);
    if (!data->closure) {
        fprintf(stderr, "Error allocating closure\n");
        return 0;
    }
    
    if (ffi_prep_closure_loc(data->closure, &data->cif, &closureHandler, data, data->fun) != FFI_OK) {
        fprintf(stderr, "Error preparing closure\n");
        return 0;
    }

    jobject bridgeMethod = reflectMethodFromKotlinFunction(env, data->function, true);
    data->invokeMethodID = env->FromReflectedMethod(bridgeMethod);

    return data->fun;
}
