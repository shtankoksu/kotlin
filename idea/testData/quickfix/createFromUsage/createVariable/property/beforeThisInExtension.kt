// "Create property 'foo' from usage" "true"
// ERROR: Property must be initialized or be abstract

class A<T>(val n: T)

fun <U> A<U>.test(): A<Int> {
    return this.<caret>foo
}
