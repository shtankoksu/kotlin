// FILE: C.kt

open class C : <!CYCLIC_INHERITANCE_HIERARCHY!>D<!>() {
    open class CC
}

// FILE: D.java

class D extends C.CC {
    void foo() {}
}
