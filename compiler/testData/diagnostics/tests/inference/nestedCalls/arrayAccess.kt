package b

class A {
    fun <T> get(i: Int): List<T> = throw Exception("$i")
}

fun bar(l: List<Int>) = l

fun test(a: A) {
    bar(a[12])
}
