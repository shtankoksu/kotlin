open class Data(val x: Int)

class A<T: Data>(val t: T) {
    // NEXT_SIBLING:
    inner class B<U: Data>(val u: U) {
        fun foo<V: Data>(v: V): Int {
            return <selection>t.x + u.x + v.x</selection>
        }
    }
}