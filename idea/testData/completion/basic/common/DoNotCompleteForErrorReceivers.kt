/// KT-1187 Wrong unnecessary completion

fun anyfun() {
    a.b.c.d.e.f.<caret>
}

// TIME: 1
// NUMBER: 0