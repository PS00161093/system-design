# Kth Largest Element Implementation

## Overview

A stream-based implementation for efficiently finding the Kth largest element in a dynamic dataset. This solution uses a
min-heap data structure to maintain the K largest elements seen so far, providing optimal time complexity for both
insertion and retrieval operations.

## Algorithm Design

### Core Data Structure

**Min-Heap of Size K**: Maintains exactly K elements where the root is the Kth largest element

```java
public class KthLargest {
    private final PriorityQueue<Integer> minHeap;
    private final int k;

    public KthLargest(int k, int[] nums) {
        this.k = k;
        this.minHeap = new PriorityQueue<>(k);

        // Initialize heap with existing numbers
        for (int num : nums) {
            add(num);
        }
    }
}
```

### Key Insight

By maintaining a min-heap of size K:

- The root element is always the Kth largest element
- Elements smaller than the Kth largest are automatically excluded
- Adding new elements requires only O(log K) time

## Core Operations

### Add Operation

**Purpose**: Insert a new value and return the current Kth largest
**Time Complexity**: O(log K)
**Space Complexity**: O(K)

```java
public int add(int val) {
    minHeap.offer(val);

    // Maintain heap size of exactly k
    if (minHeap.size() > k) {
        minHeap.poll(); // Remove smallest element
    }

    return minHeap.peek(); // Return kth largest
}
```

### Algorithm Flow

```
┌─────────────────┐
│ add(val)        │
└─────┬───────────┘
      │
      ▼
┌─────────────────┐
│ minHeap.offer   │
│ (val)           │
└─────┬───────────┘
      │
      ▼
┌─────────────────────┐       ┌──────────────────┐
│ heap.size() > k?    │──Yes──│ minHeap.poll()   │
└─────┬───────────────┘       │ (remove smallest)│
      │No                     └──────────────────┘
      ▼
┌─────────────────────┐
│ return minHeap.peek │
│ (kth largest)       │
└─────────────────────┘
```

## Visual Example Walkthrough

### Initialization: KthLargest(3, [4, 5, 8, 2])

```
Goal: Find 3rd largest element

Step 1: Add 4
MinHeap: [4]
3rd largest: 4

Step 2: Add 5
MinHeap: [4, 5]
3rd largest: 4

Step 3: Add 8
MinHeap: [4, 5, 8]
3rd largest: 4 ← This is our answer

Step 4: Add 2
MinHeap: [4, 5, 8] → offer(2) → [2, 5, 8, 4] → poll() → [4, 5, 8]
3rd largest: 4
```

**Heap State Visualization:**

```
After initialization [4, 5, 8, 2]:
         4
       /   \
      5     8

Elements: [4, 5, 8] (sorted: 4 ≤ 5 ≤ 8)
3rd largest = min(heap) = 4
```

### Stream Operations: add(3), add(5), add(10), add(9), add(4)

#### add(3)

```
Before:  [4, 5, 8]
Process: offer(3) → [3, 5, 8, 4] → poll() → [4, 5, 8]
After:   [4, 5, 8]
Result:  4 (3rd largest)

Reasoning: 3 < 4 (current 3rd largest), so 3 is discarded
```

#### add(5)

```
Before:  [4, 5, 8]
Process: offer(5) → [4, 5, 8, 5] → poll() → [5, 5, 8]
After:   [5, 5, 8]
Result:  5 (3rd largest)

Reasoning: 5 replaces 4 as the new 3rd largest
```

#### add(10)

```
Before:  [5, 5, 8]
Process: offer(10) → [5, 5, 8, 10] → poll() → [5, 8, 10]
After:   [5, 8, 10]
Result:  5 (3rd largest)

Reasoning: 10 becomes largest, 5 becomes 3rd largest
```

#### add(9)

```
Before:  [5, 8, 10]
Process: offer(9) → [5, 8, 10, 9] → poll() → [8, 9, 10]
After:   [8, 9, 10]
Result:  8 (3rd largest)

Reasoning: Top 3 elements are now [8, 9, 10]
```

#### add(4)

```
Before:  [8, 9, 10]
Process: offer(4) → [4, 9, 10, 8] → poll() → [8, 9, 10]
After:   [8, 9, 10]
Result:  8 (3rd largest)

Reasoning: 4 < 8 (current 3rd largest), so 4 is discarded
```

## Implementation Analysis

### Why Min-Heap?

```java
// Min-heap ensures the smallest of the K largest elements is at the root
// This makes it easy to:
// 1. Identify elements to remove (smaller than kth largest)
// 2. Quickly access the kth largest element (heap root)

PriorityQueue<Integer> minHeap = new PriorityQueue<>(); // Natural ordering (min-heap)

// Alternative max-heap approach would be inefficient:
// PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());
// Would require storing ALL elements and extracting top K each time
```

### Space Optimization

```java
// Efficient: Only store K elements
public KthLargest(int k, int[] nums) {
    this.minHeap = new PriorityQueue<>(k); // Initial capacity = k
    // ... implementation
}

// Inefficient alternative: Store all elements
// List<Integer> allElements = new ArrayList<>(); // Unbounded growth
```

### Time Complexity Analysis

- **Constructor**: O(N log K) where N is initial array size
- **add()**: O(log K) per operation
- **peek()**: O(1) to get Kth largest

## Advanced Usage Examples

### Real-Time Analytics

```java
public class TopPerformanceTracker {
    private final KthLargest topScores;
    private final int threshold;

    public TopPerformanceTracker(int topK) {
        this.topScores = new KthLargest(topK, new int[0]);
        this.threshold = topK;
    }

    public boolean isTopPerformer(int score) {
        int kthLargest = topScores.add(score);

        // Check if this score qualifies as top performance
        return score >= kthLargest;
    }

    public int getMinimumTopScore() {
        return topScores.peek(); // Current Kth largest
    }
}

// Usage
TopPerformanceTracker tracker = new TopPerformanceTracker(10); // Top 10
for(
int score :testScores){
        if(tracker.

isTopPerformer(score)){
        System.out.

println("Top performer with score: "+score);
    }
            }
```

### Stock Price Monitoring

```java
public class StockPriceMonitor {
    private final KthLargest highestPrices;
    private final String symbol;

    public StockPriceMonitor(String symbol, int trackTopK) {
        this.symbol = symbol;
        this.highestPrices = new KthLargest(trackTopK, new int[0]);
    }

    public void updatePrice(int priceInCents) {
        int kthHighest = highestPrices.add(priceInCents);

        // Alert if current price is in top K
        if (priceInCents >= kthHighest) {
            alertHighPrice(symbol, priceInCents, kthHighest);
        }
    }

    public int getTopKThreshold() {
        return highestPrices.peek();
    }

    private void alertHighPrice(String symbol, int current, int threshold) {
        System.out.printf("ALERT: %s price %d is in top prices (threshold: %d)%n",
                symbol, current, threshold);
    }
}
```

### Gaming Leaderboard

```java
public class GameLeaderboard {
    private final KthLargest topScores;
    private final int leaderboardSize;

    public GameLeaderboard(int size, int[] initialScores) {
        this.leaderboardSize = size;
        this.topScores = new KthLargest(size, initialScores);
    }

    public LeaderboardResult submitScore(String playerName, int score) {
        int previousThreshold = topScores.peek();
        int newThreshold = topScores.add(score);

        boolean madeLeaderboard = score >= newThreshold;
        boolean improvedThreshold = newThreshold > previousThreshold;

        return new LeaderboardResult(playerName, score, madeLeaderboard,
                newThreshold, improvedThreshold);
    }

    public int getMinimumLeaderboardScore() {
        return topScores.peek();
    }
}

class LeaderboardResult {
    final String playerName;
    final int score;
    final boolean madeLeaderboard;
    final int currentThreshold;
    final boolean raisedBar;

    // Constructor and getters...
}
```

### System Resource Monitoring

```java
public class ResourceUsageMonitor {
    private final KthLargest cpuUsageTracker;
    private final KthLargest memoryUsageTracker;
    private final int alertThreshold;

    public ResourceUsageMonitor(int trackTopK) {
        this.cpuUsageTracker = new KthLargest(trackTopK, new int[0]);
        this.memoryUsageTracker = new KthLargest(trackTopK, new int[0]);
        this.alertThreshold = trackTopK;
    }

    public void recordCpuUsage(int percentage) {
        int kthHighestCpu = cpuUsageTracker.add(percentage);

        if (percentage >= kthHighestCpu && percentage > 80) {
            triggerAlert("High CPU usage detected: " + percentage + "%");
        }
    }

    public void recordMemoryUsage(int percentage) {
        int kthHighestMemory = memoryUsageTracker.add(percentage);

        if (percentage >= kthHighestMemory && percentage > 85) {
            triggerAlert("High memory usage detected: " + percentage + "%");
        }
    }

    public SystemHealthReport getHealthReport() {
        return new SystemHealthReport(
                cpuUsageTracker.peek(),    // Minimum of top K CPU usage
                memoryUsageTracker.peek()  // Minimum of top K memory usage
        );
    }
}
```

## Comparison with Alternative Approaches

### Approach 1: Sorted List (Inefficient)

```java
public class KthLargestSorted {
    private final List<Integer> numbers = new ArrayList<>();
    private final int k;

    public int add(int val) {
        numbers.add(val);
        Collections.sort(numbers, Collections.reverseOrder()); // O(N log N)
        return numbers.get(k - 1);
    }
}

// Problems:
// - Time: O(N log N) per add operation
// - Space: O(N) grows without bound
// - Inefficient for streaming data
```

### Approach 2: Fixed Array with Linear Search (Inefficient)

```java
public class KthLargestArray {
    private final int[] topK;
    private int size = 0;
    private final int k;

    public int add(int val) {
        if (size < k) {
            topK[size++] = val;
        } else {
            // Find minimum and replace if val is larger
            int minIndex = findMinIndex(); // O(K)
            if (val > topK[minIndex]) {
                topK[minIndex] = val;
            }
        }
        return findMin(); // O(K)
    }
}

// Problems:
// - Time: O(K) per add operation
// - Less efficient than heap's O(log K)
// - More complex implementation
```

### Our Min-Heap Approach (Optimal)

```java
public class KthLargest {
    private final PriorityQueue<Integer> minHeap;

    public int add(int val) {
        minHeap.offer(val);        // O(log K)
        if (minHeap.size() > k) {
            minHeap.poll();        // O(log K)
        }
        return minHeap.peek();     // O(1)
    }
}

// Advantages:
// - Time: O(log K) per add operation
// - Space: O(K) bounded
// - Simple and clean implementation
// - Optimal for streaming scenarios
```

## Performance Benchmarking

### Theoretical Analysis

```
Operations on N elements:
- Min-heap approach: O(N log K)
- Sorted list approach: O(N² log N)
- Linear search approach: O(N × K)

For K = 10, N = 100,000:
- Min-heap: ~1.3M operations
- Sorted list: ~166B operations
- Linear search: ~1M operations

Min-heap is clearly optimal for large K and N.
```

### Practical Performance Test

```java

@Test
public void benchmarkKthLargest() {
    int k = 10;
    int[] testData = generateRandomData(100_000);

    // Benchmark min-heap approach
    long startTime = System.nanoTime();
    KthLargest kthLargest = new KthLargest(k, new int[0]);
    for (int val : testData) {
        kthLargest.add(val);
    }
    long heapTime = System.nanoTime() - startTime;

    // Benchmark sorted list approach
    startTime = System.nanoTime();
    KthLargestSorted sorted = new KthLargestSorted(k);
    for (int val : testData) {
        sorted.add(val);
    }
    long sortedTime = System.nanoTime() - startTime;

    System.out.printf("Min-heap: %d ms%n", heapTime / 1_000_000);
    System.out.printf("Sorted list: %d ms%n", sortedTime / 1_000_000);
    System.out.printf("Speedup: %.2fx%n", (double) sortedTime / heapTime);
}
```

## Edge Cases and Error Handling

### Input Validation

```java
public class KthLargestSafe {
    private final PriorityQueue<Integer> minHeap;
    private final int k;

    public KthLargestSafe(int k, int[] nums) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }

        this.k = k;
        this.minHeap = new PriorityQueue<>(k);

        if (nums != null) {
            for (int num : nums) {
                add(num);
            }
        }
    }

    public int add(int val) {
        minHeap.offer(val);

        if (minHeap.size() > k) {
            minHeap.poll();
        }

        // Handle case where we don't have k elements yet
        if (minHeap.size() < k) {
            return Integer.MIN_VALUE; // or throw exception
        }

        return minHeap.peek();
    }
}
```

### Edge Case Testing

```java

@Test
public void testEdgeCases() {
    // Test with empty initial array
    KthLargest kth1 = new KthLargest(1, new int[0]);
    assertEquals(5, kth1.add(5));

    // Test with k = 1
    KthLargest kth2 = new KthLargest(1, new int[]{1, 2, 3});
    assertEquals(3, kth2.add(4)); // Should return max element

    // Test with duplicate elements
    KthLargest kth3 = new KthLargest(2, new int[]{5, 5, 5});
    assertEquals(5, kth3.add(5)); // All elements are the same

    // Test with negative numbers
    KthLargest kth4 = new KthLargest(2, new int[]{-1, -2, -3});
    assertEquals(-2, kth4.add(-1)); // 2nd largest of [-3, -2, -1, -1]
}
```

## Best Practices

### 1. Initialization Strategy

```java
// Efficient: Pre-populate heap if initial data is available
KthLargest tracker = new KthLargest(k, initialArray);

// Less efficient: Add elements one by one
KthLargest tracker = new KthLargest(k, new int[0]);
for(
int val :initialArray){
        tracker.

add(val);
}
```

### 2. Memory Management

```java
// For very large K, consider using a different approach
if (k > 100_000) {
    // Consider using external sorting or database-based solution
    // Min-heap might consume too much memory
}

// For streaming with memory constraints
public class MemoryEfficientKthLargest {
    private final int k;
    private int[] topK;
    private int size;

    // Use array instead of heap for very small K
    public MemoryEfficientKthLargest(int k) {
        if (k <= 10) {
            this.topK = new int[k];
        } else {
            // Use heap for larger K
            // ... heap implementation
        }
    }
}
```

### 3. Thread Safety (if needed)

```java
public class ThreadSafeKthLargest {
    private final PriorityQueue<Integer> minHeap;
    private final int k;
    private final Object lock = new Object();

    public int add(int val) {
        synchronized (lock) {
            minHeap.offer(val);
            if (minHeap.size() > k) {
                minHeap.poll();
            }
            return minHeap.peek();
        }
    }
}
```

This Kth Largest Element implementation provides an optimal solution for streaming scenarios where efficiency and
bounded memory usage are crucial requirements.
