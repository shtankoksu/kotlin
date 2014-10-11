fun box(): String {

    val objectInLambda = {
        object : Any () {}
    }()

    val enclosingMethod = objectInLambda.javaClass.getEnclosingMethod()
    if (enclosingMethod?.getName() != "invoke") return "method: $enclosingMethod"

    val enclosingClass = objectInLambda.javaClass.getEnclosingClass()!!.getName()
    if (!enclosingClass.startsWith("_DefaultPackage\$") || !enclosingClass.endsWith("\$box\$objectInLambda\$1")) return "enclosing class: $enclosingClass"

    //KT-5092
    //val declaringClass = objectInLambda.javaClass.getDeclaringClass()
    //if (declaringClass == null) return "anonymous object hasn't a declaring class"

    return "OK"
}