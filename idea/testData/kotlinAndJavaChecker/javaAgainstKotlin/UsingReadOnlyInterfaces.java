public class UsingReadOnlyInterfaces {
    public static class Collections {
        public static <E> void useICollection(ICollection<E> iCollection, E elem, java.util.Collection<E> other) {
            java.util.Iterator<E> iter = iCollection.iterator();
            iCollection.addAll(other);
            iCollection.add(elem);
            iCollection.isEmpty();
            iCollection.clear();
            iCollection.<error>getSize</error>();
        }

        public static <E> void useCCollection(CCollection<E> cCollection, E elem, java.util.Collection<E> other) {
            java.util.Iterator<E> iter = cCollection.iterator();
            cCollection.addAll(other);
            cCollection.add(elem);
            cCollection.isEmpty();
            cCollection.clear();
            cCollection.getSize();
            cCollection.contains(elem);
            cCollection.contains("sasd");
        }
    }
}