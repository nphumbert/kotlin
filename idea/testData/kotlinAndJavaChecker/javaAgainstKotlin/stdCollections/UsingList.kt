interface IStringList : List<String>

abstract class CDoubleList : List<Double> {
    override val size: Int
        get() = TODO("not implemented")

    override fun contains(element: Double): Boolean {
        TODO("not implemented")
    }

    override fun containsAll(elements: Collection<Double>): Boolean {
        TODO("not implemented")
    }

    override fun get(index: Int): Double {
        TODO("not implemented")
    }

    override fun indexOf(element: Double): Int {
        TODO("not implemented")
    }

    override fun isEmpty(): Boolean {
        TODO("not implemented")
    }

    override fun iterator(): Iterator<Double> {
        TODO("not implemented")
    }

    override fun lastIndexOf(element: Double): Int {
        TODO("not implemented")
    }

    override fun listIterator(): ListIterator<Double> {
        TODO("not implemented")
    }

    override fun listIterator(index: Int): ListIterator<Double> {
        TODO("not implemented")
    }

    override fun subList(fromIndex: Int, toIndex: Int): List<Double> {
        TODO("not implemented")
    }
}

abstract class MyList<E> : List<E> {
    override val size: Int
        get() = TODO("not implemented")

    override fun contains(element: E): Boolean {
        TODO("not implemented")
    }

    override fun containsAll(elements: Collection<E>): Boolean {
        TODO("not implemented")
    }

    override fun get(index: Int): E {
        TODO("not implemented")
    }

    override fun indexOf(element: E): Int {
        TODO("not implemented")
    }

    override fun isEmpty(): Boolean {
        TODO("not implemented")
    }

    override fun iterator(): Iterator<E> {
        TODO("not implemented")
    }

    override fun lastIndexOf(element: E): Int {
        TODO("not implemented")
    }

    override fun listIterator(): ListIterator<E> {
        TODO("not implemented")
    }

    override fun listIterator(index: Int): ListIterator<E> {
        TODO("not implemented")
    }

    override fun subList(fromIndex: Int, toIndex: Int): List<E> {
        TODO("not implemented")
    }
}