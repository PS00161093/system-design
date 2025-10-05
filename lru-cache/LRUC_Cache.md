# LRU Cache Implementation

## Overview
LRU (Least Recently Used) cache implementation that moves items to the head of the list after `get()` operations. When the cache exceeds its capacity, the least recently used item (at the tail of the list) is removed.

## Operations

### get(key)
- **Purpose**: Return the value if the key exists, else return null
- **Time Complexity**: O(1)
- **Generic**: Works with any key type K, returns value type V
- **Thread Safety**: Uses double-checked locking pattern

### put(key, value)
- **Purpose**: Update the value if the key exists. Otherwise, add the key-value pair to the cache
- **Behavior**: If the cache exceeds its capacity, remove the least recently used item
- **Time Complexity**: O(1)
- **Generic**: Accepts any key type K and value type V
- **Thread Safety**: Fully synchronized with ReentrantLock

## Implementation Details

### Manual Implementation (LRUCache.java)

#### Data Structures
- **ConcurrentHashMap<K, Node<K, V>>**: Thread-safe O(1) key lookup to cache nodes (generic)
- **Doubly Linked List**: Maintains insertion/access order with O(1) operations
- **Dummy Head & Tail Nodes**: Simplify edge cases for insertion/deletion
- **ReentrantLock**: Synchronizes linked list operations for thread safety

#### Node Structure
```java
class Node<K, V> {
    K key;
    V val;
    Node<K, V> next, prev;
}
```

#### Key Operations
- **moveToHead(node)**: Removes node from current position and adds to head
- **addToHead(node)**: Inserts node right after dummy head
- **removeNode(node)**: Removes node from its current position in the list
- **Capacity Check**: When cache.size() >= capacity, removes tail.prev (LRU item)

#### Constructor Setup
- Creates dummy head and tail nodes
- Links: head.next = tail, tail.prev = head
- Initializes HashMap with specified capacity

### LinkedHashMap Approach (LRUCacheEasy.java)
- **Key Feature**: LinkedHashMap checks `removeEldestEntry()` after every `put()` operation
- **Automatic Removal**: If `removeEldestEntry()` returns true, the eldest entry is removed automatically
- **Advantage**: Perfect for implementing simple LRU caches without needing manual removal logic
- **Constructor Parameters**: capacity, 0.75f load factor, true for access order

### Core Logic

#### Manual Implementation Algorithm (LRUCache.java)

**get(key) Flow:**
1. Check if key exists in HashMap
2. If exists: retrieve node, moveToHead(node), return node.val
3. If not exists: return null

**put(key, value) Flow:**
1. If key exists: update node.val, moveToHead(node)
2. If key doesn't exist:
   - Check capacity: if cache.size() >= capacity, remove tail.prev from both list and HashMap
   - Create new node, add to HashMap
   - addToHead(node)

**List Operations:**
- **Recently accessed items** → move to **head** (head.next)
- **LRU items** → removed from **tail** (tail.prev)
- **Dummy nodes** → simplify boundary conditions

#### LinkedHashMap Implementation (LRUCacheEasy.java)
- The `removeEldestEntry()` method is overridden to return `true` when `size() > capacity`, triggering automatic removal of the oldest entry
- Access-order mode automatically moves accessed items to tail position

## Flow Diagrams

### get(key) Operation Flow
```
┌─────────────┐
│ get(key)    │
└─────┬───────┘
      │
      ▼
┌─────────────────┐       ┌──────────────┐
│ key in cache?   │──No──►│ return null  │
└─────┬───────────┘       └──────────────┘
      │Yes
      ▼
┌─────────────────┐
│ Get node from   │
│ HashMap         │
└─────┬───────────┘
      │
      ▼
┌─────────────────┐
│ moveToHead(node)│
└─────┬───────────┘
      │
      ▼
┌─────────────────┐
│ return node.val │
└─────────────────┘
```

### put(key, value) Operation Flow
```
┌──────────────────┐
│ put(key, value)  │
└─────┬────────────┘
      │
      ▼
┌─────────────────────┐       ┌─────────────────────┐
│ key exists in cache?│──Yes──►│ Update node.val     │
└─────┬───────────────┘       │ moveToHead(node)    │
      │No                     └─────────────────────┘
      ▼
┌─────────────────────┐       ┌─────────────────────┐
│ cache.size() >=     │──Yes──►│ Remove tail.prev    │
│ capacity?           │       │ from cache & list   │
└─────┬───────────────┘       └─────────────────────┘
      │No
      ▼
┌─────────────────────┐
│ Create new node     │
│ Add to HashMap      │
│ addToHead(node)     │
└─────────────────────┘
```

## Example Walkthrough

### Initial State (capacity = 3)
```
HashMap: {}
List:    [HEAD] ⟷ [TAIL]
```

### Step 1: put(1, "A")
```
HashMap: {1 → Node(1,"A")}
List:    [HEAD] ⟷ [1,"A"] ⟷ [TAIL]
```

### Step 2: put(2, "B")
```
HashMap: {1 → Node(1,"A"), 2 → Node(2,"B")}
List:    [HEAD] ⟷ [2,"B"] ⟷ [1,"A"] ⟷ [TAIL]
```

### Step 3: put(3, "C")
```
HashMap: {1 → Node(1,"A"), 2 → Node(2,"B"), 3 → Node(3,"C")}
List:    [HEAD] ⟷ [3,"C"] ⟷ [2,"B"] ⟷ [1,"A"] ⟷ [TAIL]
```

### Step 4: get(1) - Move to head
```
HashMap: {1 → Node(1,"A"), 2 → Node(2,"B"), 3 → Node(3,"C")}
List:    [HEAD] ⟷ [1,"A"] ⟷ [3,"C"] ⟷ [2,"B"] ⟷ [TAIL]
Return: "A"
```

### Step 5: put(4, "D") - Capacity exceeded, remove LRU
```
HashMap: {1 → Node(1,"A"), 3 → Node(3,"C"), 4 → Node(4,"D")}
List:    [HEAD] ⟷ [4,"D"] ⟷ [1,"A"] ⟷ [3,"C"] ⟷ [TAIL]
Removed: Node(2,"B") from tail position
```

### Key Observations
- **Most Recent**: Always at head.next
- **Least Recent**: Always at tail.prev
- **Access Pattern**: get() or put() moves item to head
- **Eviction**: Removes from tail when capacity exceeded

## Generic Usage Examples

### String Keys with Integer Values
```java
LRUCache<String, Integer> cache = new LRUCache<>(3);
cache.put("user1", 100);
cache.put("user2", 200);
cache.put("user3", 300);

Integer score = cache.get("user1"); // Returns 100, moves to head
cache.put("user4", 400); // Evicts "user2" (LRU)
```

### Integer Keys with Custom Objects
```java
class User {
    String name;
    int age;
    // constructor, getters, setters...
}

LRUCache<Integer, User> userCache = new LRUCache<>(5);
userCache.put(1, new User("Alice", 25));
userCache.put(2, new User("Bob", 30));

User user = userCache.get(1); // Returns Alice's User object
```

### String Keys with String Values (Dictionary Cache)
```java
LRUCache<String, String> dictionary = new LRUCache<>(1000);
dictionary.put("hello", "a greeting");
dictionary.put("world", "the earth");

String definition = dictionary.get("hello"); // Returns "a greeting"
```

## Thread Safety

### Concurrency Strategy
- **ConcurrentHashMap**: Handles concurrent read/write operations on the cache map
- **ReentrantLock**: Synchronizes all linked list modifications to prevent corruption
- **Double-Checked Locking**: Optimizes get() operations by checking cache existence before acquiring lock

### Thread Safety Guarantees
- **get() operations**: Thread-safe with minimal lock contention
- **put() operations**: Fully synchronized to maintain consistency
- **Capacity management**: Atomic eviction prevents over-capacity states
- **Node ordering**: Linked list integrity maintained under concurrent access

### Performance Characteristics
- **Read-heavy workloads**: Excellent performance due to ConcurrentHashMap and minimal locking
- **Write-heavy workloads**: Good performance with ReentrantLock (better than synchronized methods)
- **Mixed workloads**: Balanced approach with optimized get() path

### Usage in Multithreaded Environment
```java
// Safe to use across multiple threads
LRUCache<String, User> cache = new LRUCache<>(1000);

// Thread 1
CompletableFuture.runAsync(() -> {
    cache.put("user1", new User("Alice"));
    User user = cache.get("user1");
});

// Thread 2
CompletableFuture.runAsync(() -> {
    cache.put("user2", new User("Bob"));
    User user = cache.get("user2");
});
```