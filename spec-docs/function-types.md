# How Function Types Work in Kotlin on JVM

## Extension functions

Extension function type `T.(P) -> R` is treated as `[kotlin.extension] Function2<T, P, R>`.
`kotlin.extension` is a built-in annotation only applicable to types.
This means that an extension function will be applicable where the usual function is expected but not vice versa
(as long as, of course, the notion of subtype involves checking for type annotations).

The problem of representing functions therefore is fully reduced to the usual function types,
with additional `extension` annotations supplied where needed.

## Types for type checker and for JVM runtime

The arity of the functional trait that the type checker can created in theory **is not limited** to any number,
but in practice should be limited to 255 on JVM.

These traits are named `kotlin.Function0<R>`, `kotlin.Function1<P0, R>`, ..., `kotlin.Function42<P0, P1, ..., P41, R>`, ...
They are *virtual* in that they have no sources and no runtime representation. Type checker creates the corresponding descriptors on demand,
IDE creates corresponding sources on demand as well. Each of them inherits from `kotlin.Function` (described below) and contains a single
`fun invoke()` with the corresponding number of parameters and return type.

On JVM such `FunctionN` types correspond to the physical classes `kotlin.jvm.internal.FunctionN` for `0 <= N <= 22`,
and to the special `FunctionLarge` class for all other `N`.

## Function trait

There's also a parameter-less trait `kotlin.Function<R>` for cases when e.g. you're storing functions with different/unknown arity
in a collection to invoke them reflectively somewhere else.

``` kotlin
package kotlin

trait Function<out R>
```

Its declaration is **empty** because every `Function0`, `Function1`, ... inherits from it (and adds `invoke()`),
and we don't want to override anything besides `invoke()` when doing it from Java.

## Functions with 0..22 parameters at runtime

There are 23 function traits in `kotlin.jvm.internal`: `Function0`, `Function1`, ..., `Function22`.
Here's `Function1` declaration, for example:

``` kotlin
package kotlin.jvm.internal

trait Function1<in P0, out R> : kotlin.Function<R> {
    fun invoke(p0: P0): R
}
```

These traits are supposed to be inherited from Java when passing lambdas to Kotlin.

TODO: they shouldn't be in `kotlin.jvm.internal` then. But they can't be in `kotlin.jvm` as well -- one should use a platform-agnostic
`kotlin.FunctionN` instead. Will there be some kind of a Java-interface-like package in Kotlin JVM runtime?

## Translation of Kotlin lambdas

There's also `FunctionImpl` abstract class at runtime which helps in implementing `arity` and vararg-invocation.

``` kotlin
package kotlin.jvm.internal

abstract class FunctionImpl(val arity: Int) : Function<Any?> {
    fun invoke(): Any? = throw UnsupportedOperationException()
    fun invoke(p0: Any?) = throw UnsupportedOperationException()
    ...
    fun invoke(p0: Any?, ..., p22: Any?) = throw UnsupportedOperationException()
    
    fun apply(vararg p: Any?): Any? = when (arity) {
        0 -> invoke()
        1 -> invoke(p[0])
        2 -> invoke(p[0], p[1])
        ...
        23 -> invoke(p[0], ..., p[22])
        else -> throw UnsupportedOperationException("This is impossible in fact")
    }
    
    fun toString() = ... (some calculation involving generic runtime signatures)
}
```

Each lambda is compiled to an anonymous class which inherits from `FunctionImpl` and the corresponding `FunctionN`:

``` kotlin
object : FunctionImpl(2), Function2 {
    override fun invoke(p0: Any?, p1: Any?): Any? { ... /* code */ }
}
```

## Functions with more than 22 parameters at runtime

To support functions with many parameters there's a special trait in JVM runtime:

``` kotlin
package kotlin.jvm.internal

trait FunctionLarge<out R> : kotlin.Function<R> {
    val arity: Int
    fun apply(vararg p: Any?): R
}
```

TODO: naming

And another type annotation:

``` kotlin
package kotlin.jvm.internal

annotation class arity(val value: Int)
```

A lambda type with many parameters on JVM is translated to `FunctionLarge` annotated with the actual arity.
Each lambda value is compiled to an anonymous class which overrides `arity` and `apply()`:

``` kotlin
object : FunctionLarge {
    override fun apply(vararg p: Any?): Any? { ... /* code */ }
    override val arity: Int get() = 42
}
```

TODO: should we also provide `invoke` with 42 parameters here? Will be fine even without it if we only call `apply` statically on functions
with many parameters.

Note that when we analyze Kotlin sources we have type arguments for `FunctionLarge`, but they are lost after compilation.
So we should serialize this information (probably to some type annotation as well) and load it for at least Kotlin large lambdas to work.
`FunctionLarge` without such annotation (coming for example from Java) will be treated as `(Any?, Any?, ...) -> Any?`.

## Arity and invocation with vararg

There's an ability to get an arity of a function object and call it with variable number of arguments.
These two extensions are located in **platform-agnostic** built-ins.

``` kotlin
package kotlin

intrinsic val Function<*>.arity: Int
intrinsic fun Function<R>.apply(vararg p: Any?): R
```

But they don't have any implementation there. The reason is, they need platform-specific function implementation to work efficiently.
This is the (intrinsic) implementation of `arity` (`apply` is essentially the same):

``` kotlin
fun Function<*>.calculateArity() {
    return if (function is FunctionImpl) {  // This handles the case of small lambdas created from Kotlin
        (function as FunctionImpl).arity
    }
    else when (function) {  // This handles all other lambdas, e.g. created from Java
        is Function0 -> 0
        is Function1 -> 1
        ...
        is Function22 -> 22
        is FunctionLarge -> (function as FunctionLarge).arity
        else -> throw UnsupportedOperationException()  // TODO: maybe do something funny here, e.g. find 'invoke' reflectively
    }
}
```






