class Data<T>(val t: Int)

// NEXT_SIBLING:
class A<T> {
    fun foo(d: Data<T>): Int {
        return <selection>d.t + 1</selection>
    }
}