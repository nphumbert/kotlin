package kt

interface IStringCollection : Collection<String>

abstract class CDoubleCollection : Collection<Double> {
    override val size: Int
        get() = TODO("not implemented")

    override fun contains(element: Double): Boolean {
        TODO("not implemented")
    }

    override fun containsAll(elements: Collection<Double>): Boolean {
        TODO("not implemented")
    }

    override fun isEmpty(): Boolean {
        TODO("not implemented")
    }

    override fun iterator(): Iterator<Double> {
        TODO("not implemented")
    }
}

abstract class MyCollection<E> : Collection<E> {
    override val size: Int
        get() = TODO("not implemented")

    override fun contains(element: E): Boolean {
        TODO("not implemented")
    }

    override fun containsAll(elements: Collection<E>): Boolean {
        TODO("not implemented")
    }

    override fun isEmpty(): Boolean {
        TODO("not implemented")
    }

    override fun iterator(): Iterator<E> {
        TODO("not implemented")
    }
}

class A

abstract class ACollection : Collection<A> {
    override val size: Int
        get() = TODO("not implemented")

    override fun contains(element: A): Boolean {
        TODO("not implemented")
    }

    override fun containsAll(elements: Collection<A>): Boolean {
        TODO("not implemented")
    }
    override fun isEmpty(): Boolean {
        TODO("not implemented")
    }

    override fun iterator(): Iterator<A> {
        TODO("not implemented")
    }
}