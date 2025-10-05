# Leaky Bucket Rate Limiter

## Overview
A thread-safe rate limiter implementation using the **Leaky Bucket Algorithm**. This algorithm controls the rate of requests by maintaining a queue (bucket) that processes requests at a constant rate, smoothing out traffic bursts.

## Algorithm Concept

### Leaky Bucket Mechanics
1. **Bucket (Queue)**: Holds incoming requests up to a maximum capacity
2. **Leak Rate**: Requests are processed at a constant rate (e.g., 10 requests/second)
3. **Overflow Handling**: When bucket is full, new requests are rejected or delayed
4. **Smooth Output**: Provides consistent processing rate regardless of input burst
5. **Buffering**: Queues requests during bursts, processes them steadily

## Key Features

### Thread Safety
- **ReentrantLock**: Synchronizes bucket operations and leak processing
- **BlockingQueue**: Thread-safe queue implementation (ArrayBlockingQueue)
- **Atomic Operations**: Request queuing and leak processing are atomic

### Request Management
- **Multiple Submit Methods**: Non-blocking, blocking, and timeout-based submission
- **Request Tracking**: Each request has ID and timestamp for monitoring
- **Graceful Degradation**: Configurable behavior when bucket is full

### Performance Monitoring
- **Queue Utilization**: Track bucket usage percentage
- **Wait Time Tracking**: Monitor how long requests spend in queue
- **Processing Statistics**: Observe leak rate effectiveness

## API Methods

### trySubmit()
- **Purpose**: Non-blocking request submission
- **Return**: `true` if queued, `false` if bucket full
- **Use Case**: Fail-fast scenarios where you want to reject excess requests

### submit()
- **Purpose**: Blocking request submission
- **Behavior**: Waits until space becomes available in bucket
- **Use Case**: When you want to buffer all requests (within capacity limits)

### trySubmit(timeout)
- **Purpose**: Timeout-based request submission
- **Return**: `true` if queued within timeout, `false` otherwise
- **Use Case**: Balanced approach between rejecting and blocking indefinitely

### processLeaks()
- **Purpose**: Force processing of requests that should have leaked
- **Use Case**: Manual leak processing for testing or fine-grained control

## Implementation Details

### Data Structures
```java
private final int capacity;                    // Maximum queued requests
private final long leakRate;                   // Requests processed per second
private final Duration leakInterval;           // Time between processing
private final BlockingQueue<Request> bucket;   // Thread-safe request queue
private Instant lastLeakTime;                  // Last leak processing time
private final ReentrantLock lock;              // Synchronization
```

### Leak Processing Algorithm
```java
private void leak() {
    Instant now = Instant.now();
    Duration elapsed = Duration.between(lastLeakTime, now);

    // Calculate requests to process based on elapsed time
    long requestsToLeak = elapsed.toNanos() / leakInterval.toNanos();

    // Process requests up to the calculated amount
    for (int i = 0; i < requestsToLeak && !bucket.isEmpty(); i++) {
        Request request = bucket.poll();
        processRequest(request);
    }

    lastLeakTime = now;
}
```

### Concurrency Strategy
- **Lock-protected operations**: All bucket modifications synchronized
- **Thread-safe queue**: BlockingQueue handles concurrent access
- **Lazy leak processing**: Requests processed on-demand, no background threads

## Usage Examples

### Basic Rate Limiting
```java
// Queue up to 50 requests, process 10 per second
LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(50, 10);

// Non-blocking submission
if (limiter.trySubmit("api-request-1")) {
    System.out.println("Request queued for processing");
} else {
    System.out.println("Bucket full - request rejected");
}
```

### API Gateway Usage
```java
public class APIGateway {
    // Buffer up to 1000 requests, process 100/second
    private final LeakyBucketRateLimiter limiter =
        new LeakyBucketRateLimiter(1000, 100);

    public Response handleRequest(String requestId) {
        if (!limiter.trySubmit(requestId)) {
            return Response.status(503)
                          .entity("Service temporarily unavailable")
                          .build();
        }

        // Request is queued and will be processed at steady rate
        return Response.accepted()
                      .entity("Request queued for processing")
                      .build();
    }
}
```

### Batch Processing with Timeout
```java
public void submitBatchWithTimeout(List<String> requests) {
    for (String request : requests) {
        try {
            // Wait up to 5 seconds for bucket space
            boolean queued = limiter.trySubmit(request, 5, TimeUnit.SECONDS);
            if (!queued) {
                System.out.println("Timeout waiting for bucket space: " + request);
                // Handle timeout (retry, log, etc.)
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
        }
    }
}
```

### Monitoring and Health Checks
```java
public class LeakyBucketMonitor {
    private final LeakyBucketRateLimiter limiter;

    public void logStatus() {
        System.out.printf("Bucket Status: %d/%d requests queued (%.1f%% full)%n",
            limiter.getQueuedRequests(),
            limiter.getCapacity(),
            limiter.getUtilization() * 100);

        if (limiter.isFull()) {
            System.out.println("WARNING: Bucket is full - rejecting new requests");
        }
    }

    public boolean isHealthy() {
        return limiter.getUtilization() < 0.8; // Less than 80% full
    }
}
```

### Background Processing Simulation
```java
// Simulate steady background processing
ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

executor.scheduleAtFixedRate(() -> {
    limiter.processLeaks();
}, 0, 100, TimeUnit.MILLISECONDS); // Process leaks every 100ms
```

## Configuration Guidelines

### Capacity (Bucket Size)
- **Small Capacity**: Less buffering, quicker rejection of excess load
- **Large Capacity**: More buffering, better handling of traffic spikes
- **Rule of Thumb**: Set to handle expected burst duration × leak rate

### Leak Rate
- **High Rate**: Faster processing, less queuing delay
- **Low Rate**: Slower processing, more buffering needed
- **Consider**: Downstream system capacity, SLA requirements

### Example Configurations
```java
// Strict processing: small buffer, slow rate
new LeakyBucketRateLimiter(10, 5);

// Web API: moderate buffer, moderate rate
new LeakyBucketRateLimiter(200, 50);

// Batch processing: large buffer, high rate
new LeakyBucketRateLimiter(10000, 1000);

// Real-time system: small buffer, very high rate
new LeakyBucketRateLimiter(50, 500);
```

## Thread Safety Guarantees

### Concurrent Operations
- **Multiple Producers**: Safe concurrent calls to `trySubmit()`, `submit()`
- **Leak Processing**: Thread-safe leak processing with proper synchronization
- **Queue Operations**: BlockingQueue ensures thread-safe queue access

### Consistency
- **Request Ordering**: FIFO processing maintains request order
- **Leak Timing**: Accurate leak processing prevents over/under processing
- **State Management**: Consistent internal state under concurrent access

## Performance Characteristics

### Throughput
- **Sustained Rate**: Matches configured leak rate over time
- **Burst Handling**: Buffers bursts up to bucket capacity
- **Scalability**: Good performance under concurrent load

### Latency
- **Queue Delay**: Requests may wait in queue before processing
- **Processing Latency**: Predictable based on leak rate and queue position
- **Blocking Behavior**: Configurable blocking vs. rejection

## Comparison: Leaky Bucket vs Token Bucket

| Aspect | Leaky Bucket | Token Bucket |
|--------|--------------|--------------|
| **Burst Handling** | Smooths bursts by queuing | Allows immediate bursts |
| **Output Rate** | Constant, predictable | Variable, up to refill rate |
| **Memory Usage** | Higher (stores requests) | Lower (stores tokens) |
| **Latency** | Variable (queuing delay) | Low (immediate processing) |
| **Use Case** | Smooth, steady output | Flexible burst handling |
| **Buffering** | Built-in request buffering | No request buffering |
| **Complexity** | Higher (queue management) | Lower (token counting) |

### When to Use Leaky Bucket
- **Steady Output Required**: When downstream systems need consistent load
- **Traffic Smoothing**: When you want to smooth out bursty traffic
- **Request Buffering**: When temporary queuing of requests is acceptable
- **Protecting Resources**: When protecting systems that can't handle bursts

### When to Use Token Bucket
- **Burst Tolerance**: When you can handle occasional traffic bursts
- **Low Latency**: When immediate processing is important
- **Simple Rate Limiting**: When you just need basic rate control
- **Memory Efficiency**: When memory usage is a concern

## Best Practices

1. **Monitor Queue Utilization**: Track bucket fullness for capacity planning
2. **Set Appropriate Timeouts**: Use timeout-based submission for better UX
3. **Handle Interruptions**: Properly handle `InterruptedException`
4. **Graceful Shutdown**: Call `shutdown()` to process remaining requests
5. **Leak Processing**: Call `processLeaks()` periodically for accurate timing
6. **Health Monitoring**: Implement health checks based on queue utilization
7. **Capacity Planning**: Size bucket based on expected burst patterns