public class Node<K, V> {
    K key;
    V val;
    Node<K, V> next;
    Node<K, V> prev;

    public Node(K key, V val) {
        this.key = key;
        this.val = val;
    }

    @Override
    public String toString() {
        return "Node[key=" + key + ", val=" + val + "]";
    }
}
