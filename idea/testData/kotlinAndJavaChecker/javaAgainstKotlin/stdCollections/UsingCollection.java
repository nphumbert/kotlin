public class UsingCollection {
    void f(IStringCollection stringCollection) {
        stringCollection.add("asd");
        stringCollection.indexOf("");
        stringCollection.indexOf(3);
        stringCollection.indexOf(new Object());
        String s = stringCollection.get(3);
    }

    void f(CDoubleCollection doubleCollection) {
        doubleCollection.add<error>(3)</error>;
        doubleCollection.add(3.0);
        Double d = doubleCollection.get(0);

        doubleCollection.indexOf("");
        doubleCollection.indexOf(3);
        doubleCollection.indexOf(new Object());
    }

    public static class Extend extends CDoubleCollection {

    }

    public static class Extend2<E> extends MyCollection<E> {

    }

}