package foo

import java.util.StringBuilder

fun box(): String {
    val s = StringBuilder()
    s.append("a")
    s.append("b")?.append("c")
    s.append("d")!!.append("e")

    if (s.toString() != "abcde") return s.toString()
    return "OK"
}