# Function Types in Kotlin on JVM

## Goals

* Get rid of 23 hardwired physical function classes. The problem with them is,
reflection introduces a few kinds of functions but each of them should be invokable as a normal function as well, and so
we get `{top-level, member, extension, member-extension, local, ...} * 23` = **a lot** of physical classes in the runtime.
* Make extension functions coercible to normal functions (with an extra parameter).
At the moment it's not possible to do `listOfStrings.map(String::length)`
* Allow functions with more than 23 parameters, theoretically any number of parameters (in practice 255 on JVM).
* At the same time, allow to implement Kotlin functions easily from Java: `new Function2() { ... }` and overriding `invoke` only would be the best.

## Brief solution overview

* Treat extension functions almost like non-extension functions with one extra parameter, allowing to use them almost interchangeably.
* Introduce a physical class `Function` and unlimited number of *syhthetic* classes `Function0`, `Function1`, ... in the compiler front-end
* On JVM, introduce `Function0`..`Function22`, which are optimized in a certain way,
and `FunctionLarge` for functions with many parameters.
When passing a lambda to Kotlin from Java, one will need to implement one of these interfaces.
* Also on JVM (under the hood) add abstract `FunctionImpl` which implements all of `Fun0`..`Fun22` and `FunLarge`
(throwing exceptions), and which knows its arity.
Kotlin lambdas are translated to subclasses of this abstract class, passing the correct arity to the super constructor.
* Provide a way to get arity of an arbitrary `Function` object (pretty straightforward).
* Hack `is/as FunctionN` in codegen (and probably `KClass.cast()` in reflection) to check against `Function` and its arity.

## Extension functions

Extension function type `T.(P) -> R` is now just a shorthand for `[kotlin.extension] Function2<T, P, R>`.
`kotlin.extension` is a **type annotation** defined in built-ins.
So effectively functions and extension functions now have the same type,
how can we make extension function expressions support extension function call syntax?

We introduce the following convention: expression `foo` of type `Foo` can be used as an extension function
(i.e. `object.foo(arguments)`) if and only if there is a function `invokeExtension`
with the corresponding parameters available on the type `Foo`.
This function may be declared in class `Foo` or somewhere as an extension to `Foo`.

> Note that at the moment a less convenient convention is used: there must be a **member extension**
> function `invoke` in the class which you want to be used as an extension function.
> This means you can't add "extension-function-ness" to a foreign class,
> since you'd need to declare a function with two receivers.
> The new approach will solve this problem.

We declare `invokeExtension` to be available on all extension functions:

``` kotlin
package kotlin

...
fun <T, R> (T.() -> R).invokeExtension(): R = this()
fun <T, P1, R> (T.(P1) -> R).invokeExtension(p1: P1): R = this(p1)
...
```

So now an expression type-checked to an "extension function type" can be used with the desired syntax.
But, since a function type and a corresponding extension function type effectively have the same classifier (`FunctionN`),
they are coercible to each other and therefore our `invokeExtension` will be applicable to the usual
functions as well, which is something we don't want to happen! Example:

``` kotlin
val lengthHacked: (String) -> Int = { it.length }

fun test() = "".lengthHacked()  // <-- bad! The declared function accepts a single non-receiver argument
                                // and is not designed to be invoked as an extension
```

And here we introduce the following **restriction**: given a call `object.foo(arguments)`,
if `foo` is resolved **exactly** to the built-in extension function `invokeExtension`,
then the call *will not compile* unless its receiver value is an object of the **exact** type `[extension] FunctionN`.
So `invokeExtension` will yield an error when used on `FunctionN` objects and objects of `FunctionN` subtypes.

To make your class invokable as an extension you only need to declare `invokeExtension`.
Declaring `invoke` (and maybe overriding it from `FunctionN`) will only make your class invokable *as a usual function*.
Inheriting from a function type thus makes sense if you want your class to behave like a simple function.
Inheriting from an extension function type however makes no sense and should be prohibited / frowned upon.
In a broad sense, providing type annotations on supertypes (which is what inheriting from an extension function is)
maybe should be diagnosed in the compiler (maybe not, more knowledge needed).

With this we'll get rid of classes `ExtensionFunction0`, `ExtensionFunction1`, ...
and the rest of this article will deal only with usual functions.

## FunctionN types

The arity of the functional trait that the type checker can create in theory **is not limited** to any number,
but in practice should be limited to 255 on JVM.

These traits are named `kotlin.Function0<R>`, `kotlin.Function1<P0, R>`, ..., `kotlin.Function42<P0, P1, ..., P41, R>`, ...
They are *virtual* in that they have no sources and no runtime representation. Type checker creates the corresponding descriptors on demand,
IDE creates corresponding sources on demand as well. Each of them inherits from `kotlin.Function` (described below) and contains a single
`fun invoke()` with the corresponding number of parameters and return type.

> TODO: investigate exactly what changes in IDE should be done and if they are possible at all.

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

There are 23 function traits in `kotlin.platform.jvm`: `Function0`, `Function1`, ..., `Function22`.
Here's `Function1` declaration, for example:

``` kotlin
package kotlin.platform.jvm

trait Function1<in P1, out R> : kotlin.Function<R> {
    fun invoke(p1: P1): R
}
```

These traits are supposed to be inherited from Java when passing lambdas to Kotlin.

Package `kotlin.platform.jvm` is supposed to contain interfaces which help use Kotlin from Java.
(And not from Kotlin, because normally you would use `kotlin.FunctionN` there.)

## Translation of Kotlin lambdas

There's also `FunctionImpl` abstract class at runtime which helps in implementing `arity` and vararg-invocation.
It inherits from all the functions, unfortunately (more on that later).

``` kotlin
package kotlin.jvm.internal

abstract class FunctionImpl(override val arity: Int) :
    Function<Any?>,
    Function0<Any?>, Function1<Any?, Any?>, ..., ..., Function22<...>,
    FunctionLarge   // See the next section on FunctionLarge
{
    override fun invoke(): Any? {
        // Default implementations of all "invoke"s invoke "apply"
        // This is needed for KFunctionImpl (see below)
        assert(arity == 0)
        return apply()
    }
    
    override fun invoke(p1: Any?): Any? {
        assert(arity == 1)
        return apply(p1)
    }
    
    ...
    override fun invoke(p1: Any?, ..., p22: Any?) { ... }
    
    override fun apply(vararg p: Any?): Any? = throw UnsupportedOperationException()
    
    override fun toString() = ... // Some calculation involving generic runtime signatures
}
```

Each lambda is compiled to an anonymous class which inherits from `FunctionImpl` and implements the corresponding `invoke`:

``` kotlin
object : FunctionImpl(2) {
    override fun invoke(p1: Any?, p2: Any?): Any? { ... /* code */ }
}
```

## Functions with more than 22 parameters at runtime

To support functions with many parameters there's a special trait in JVM runtime:

``` kotlin
package kotlin.platform.jvm

trait FunctionLarge<out R> : kotlin.Function<R> {
    val arity: Int
    fun apply(vararg p: Any?): R
}
```

> TODO: naming

> TODO: usual hierarchy problems: there are no such members in `kotlin.Function42` (it only has `invoke()`),
> so inheritance from `FunctionN` for big `N` will need to be hacked somehow

And another type annotation:

``` kotlin
package kotlin.platform.jvm

annotation class arity(val value: Int)
```

A lambda type with 42 parameters on JVM is translated to `[arity(42)] FunctionLarge`.
A lambda is compiled to an anonymous class which overrides `apply()` instead of `invoke()`:

``` kotlin
object : FunctionImpl(42) {
    override fun apply(vararg p: Any?): Any? { ... /* code */ }
    // TODO: maybe assert that `p`'s size is 42 in the beginning of `apply`?
}
```

> Note that `Function0`..`Function22` are provided primarily for **Java interoperability** and as an **optimization** for frequently used functions.
> We can change it easily to something else if we want to.
> For example, for `KFunction`, `KMemberFunction`, ... this number will be zero,
> since there's no point in implementing a hypothetical `KFunction5` from Java.

So when a large function is passed from Java to Kotlin, the object will need to inherit from `FunctionLarge`:

``` kotlin
    // Kotlin
    fun fooBar(f: Function42<*,*,...,*>) = f(...)
```

``` java
    // Java
    fooBar(new FunctionLarge<String>() {
        @Override
        public int getArity() { return 42; }
        
        @Override
        public String apply(Object... p) { return "42"; }
    }
```

> Note that when we analyze Kotlin sources we have type arguments for `Function42`, but they are lost after compilation
> since `FunctionLarge` doesn't and can't have types of its parameters.
> So we should serialize this information (probably to some type annotation as well) and load it for at least Kotlin large lambdas to work.
> `FunctionLarge` without such annotation (coming for example from Java) will be treated as `(Any?, Any?, ...) -> Any?`.

## Arity and invocation with vararg

There's an ability to get an arity of a function object and call it with variable number of arguments.
These two extensions are located in **platform-agnostic** built-ins.

``` kotlin
package kotlin

intrinsic val Function<*>.arity: Int
intrinsic fun <R> Function<R>.apply(vararg p: Any?): R
```

But they don't have any implementation there.
The reason is, they need **platform-specific** function implementation to work efficiently.
This is the JVM implementation of the `arity` intrinsic (`apply` is essentially the same):

``` kotlin
fun Function<*>.calculateArity(): Int {
    return if (function is FunctionImpl) {  // This handles the case of lambdas created from Kotlin
        (function as FunctionImpl).arity
    }
    else when (function) {  // This handles all other lambdas, i.e. created from Java
        is Function0 -> 0
        is Function1 -> 1
        ...
        is Function22 -> 22
        is FunctionLarge -> (function as FunctionLarge).arity
        else -> throw UnsupportedOperationException()  // TODO: maybe do something funny here,
                                                       // e.g. find 'invoke' reflectively
    }
}
```

## `is`/`as` hack

The newly introduced `FunctionImpl` class inherits from all the `Function0`, `Function1`, ..., `FunctionLarge`.
This means that `anyLambda is Function2<*, *, *>` will be true for any Kotlin lambda.
To fix this, we need to hack `is` so that it would reach out to the `FunctionImpl` instance and get its arity.

``` kotlin
package kotlin.jvm.internal

// This is the intrinsic implementation
// Calls to this function are generated by codegen on 'is FunctionN'
fun isFunctionN(x: Any?, n: Int): Boolean = (x as? Function).arity == n
```

`as FunctionN` should check if `isFunctionN(instance, N)`, and checkcast if it is or throw exception if not.

A downside is that `instanceof Function5` obviously won't work correctly from Java.

## How this will help reflection

The saddest part of this story is that all `K*FunctionN` interfaces should be hacked identically to `FunctionN`.
The compiler should resolve `KFunctionN` for any `N`, IDEs should synthesize sources when needed,
`is`/`as` should be handled similarly etc.

However, we **won't introduce multitudes of `KFunction`s at runtime**.
The two reasons we did it for `Function`s were Java interop and lambda performance, and they both are not so relevant here.
A great aid was that the contents of each `Function` were trivial and easy to duplicate (23-plicate?),
which is not a case at all for `KFunction`s: they also contain code related to reflection.

So for reflection there will be:
* **synthetic** classes `KFunction0`, `KFunction1`, ..., `KMemberFunction0`, ..., `KMemberFunction42`, ... (defined in `kotlin`)
* **physical** (interface) classes `KFunction`, `KMemberFunction`, ... (defined in `kotlin.reflect`)
* **physical** JVM runtime implementation classes `KFunctionImpl`, `KMemberFunctionImpl`, ... (defined in `kotlin.reflect.jvm.internal`)

``` kotlin
package kotlin.reflect

trait KFunction<out R> : Function<R> {
    fun apply(vararg p: Any?): R
    ... // Reflection-specific declarations
}
```

``` kotlin
package kotlin.reflect.jvm.internal

open class KFunctionImpl(name, owner, arity, ...) : KFunction<Any?>, FunctionImpl(arity) {
    ... // Reflection-specific code
    
    // Remember that each "invoke" delegates to "apply" with assertion by default.
    // We're overriding only "apply" here and magically a callable reference
    // will start to work as the needed FunctionN
    override fun apply(vararg p: Any?): Any? {
        owner.getMethod(name, ...).invoke(p)  // Java reflection
    }
}
```

> TODO: a performance problem: we pass arity to `FunctionImpl`'s constructor,
> which may involve a lot of the eager computation (finding the method in a Class).
> Maybe make `arity` an abstract property in `FunctionImpl`, create a subclass `Lambda` with a concrete field for lambdas,
> and for `KFunction`s just implement it lazily
