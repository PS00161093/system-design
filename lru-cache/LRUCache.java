import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class LRUCache<K, V> {
    private final int capacity;
    private final ConcurrentHashMap<K, Node<K, V>> cache;
    private final ReentrantLock lock = new ReentrantLock();
    private final Node<K, V> head;
    private final Node<K, V> tail;

    public LRUCache(int capacity) {
        this.capacity = capacity;
        this.cache = new ConcurrentHashMap<>();
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        System.out.println("Get " + key);
        Node<K, V> node = cache.get(key);
        if (node != null) {
            lock.lock();
            try {
                // Double-check node is still in cache after acquiring lock
                if (cache.containsKey(key)) {
                    moveToHead(node);
                    return node.val;
                }
            } finally {
                lock.unlock();
            }
        }
        return null;
    }

    public void put(K key, V value) {
        System.out.println("Put " + key + " : " + value);
        lock.lock();
        try {
            Node<K, V> existingNode = cache.get(key);
            if (existingNode != null) {
                // Update existing node
                existingNode.val = value;
                moveToHead(existingNode);
            } else {
                // Add new node
                if (cache.size() >= capacity) {
                    // Remove LRU node
                    Node<K, V> lru = tail.prev;
                    removeNode(lru);
                    cache.remove(lru.key);
                }
                Node<K, V> newNode = new Node<>(key, value);
                cache.put(key, newNode);
                addToHead(newNode);
            }
        } finally {
            lock.unlock();
        }
    }

    private void moveToHead(Node<K, V> node) {
        System.out.println("Move to head " + node);
        if (node == head.next) {
            System.out.println("Node is already at head " + node);
            return;
        }
        removeNode(node);
        addToHead(node);
    }

    private void addToHead(Node<K, V> node) {
        System.out.println("Add to head " + node);
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        System.out.println("Remove node " + node);
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    public int size() {
        return cache.size();
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return "LRUCache[head = " + head.next + ", tail = " + tail.prev + "]. Cache Data = " + cache;
        } finally {
            lock.unlock();
        }
    }
}
