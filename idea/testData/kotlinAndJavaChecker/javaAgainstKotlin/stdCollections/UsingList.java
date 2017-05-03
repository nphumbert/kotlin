public class UsingList {
    void f(IStringList stringList) {
        stringList.add("asd");
        stringList.indexOf("");
        stringList.indexOf(3);
        stringList.indexOf(new Object());
        String s = stringList.get(3);
    }

    void f(CDoubleList doubleList) {
        doubleList.add<error>(3)</error>;
        doubleList.add(3.0);
        Double d = doubleList.get(0);

        doubleList.indexOf("");
        doubleList.indexOf(3);
        doubleList.indexOf(new Object());
    }

    public static class Extend extends CDoubleList {

    }

    public static class Extend2<E> extends MyList<E> {

    }

}