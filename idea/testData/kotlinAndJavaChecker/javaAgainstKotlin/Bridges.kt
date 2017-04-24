package test

interface I<T> {
    fun foo(i: T)
}

interface II {
    fun foo(s: String)
}

open class C: I<String>, II {
    override fun foo(i: String) {
    }
}