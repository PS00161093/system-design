# Token Bucket Rate Limiter

## Overview
A thread-safe rate limiter implementation using the **Token Bucket Algorithm**. This algorithm controls the rate of requests by maintaining a bucket of tokens that are consumed by incoming requests and refilled at a constant rate.

## Algorithm Concept

### Token Bucket Mechanics
1. **Bucket**: Holds a limited number of tokens (capacity)
2. **Refill Rate**: Tokens are added at a constant rate (e.g., 10 tokens/second)
3. **Token Consumption**: Each request consumes one or more tokens
4. **Rate Limiting**: Requests are rejected when insufficient tokens are available
5. **Burst Handling**: Allows bursts up to bucket capacity, then throttles to refill rate

## Key Features

### Thread Safety
- **ReentrantLock**: Synchronizes all token operations
- **Atomic Operations**: Token acquisition and refill are atomic
- **Thread-Safe Methods**: All public methods are safe for concurrent use

### Flexibility
- **Configurable Capacity**: Set maximum burst size
- **Configurable Rate**: Set sustained request rate (tokens/second)
- **Multiple Token Acquisition**: Support for requests requiring multiple tokens

### Performance
- **Lazy Refill**: Tokens are refilled on-demand, not with background threads
- **Efficient Locking**: Minimal lock contention with fast operations
- **Precise Timing**: Uses nanosecond precision for accurate rate limiting

## API Methods

### tryAcquire()
- **Purpose**: Non-blocking token acquisition
- **Return**: `true` if tokens acquired, `false` if insufficient tokens
- **Use Case**: Best for scenarios where you want to reject excess requests immediately

### acquire()
- **Purpose**: Blocking token acquisition
- **Behavior**: Waits until tokens become available
- **Use Case**: Best for scenarios where you want to throttle requests rather than reject them

### getAvailableTokens()
- **Purpose**: Check current token count
- **Thread Safe**: Updates token count before returning
- **Use Case**: Monitoring and debugging

## Implementation Details

### Data Structures
```java
private final long capacity;           // Maximum tokens in bucket
private final long refillRate;         // Tokens added per second
private final Duration refillPeriod;   // Time between token additions
private long availableTokens;          // Current token count
private Instant lastRefillTime;        // Last refill timestamp
private final ReentrantLock lock;      // Synchronization lock
```

### Refill Algorithm
```java
private void refillTokens() {
    Instant now = Instant.now();
    Duration elapsed = Duration.between(lastRefillTime, now);

    if (elapsed >= refillPeriod) {
        long tokensToAdd = elapsed.toNanos() / refillPeriod.toNanos();
        availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
        lastRefillTime = now;
    }
}
```

### Concurrency Strategy
- **Lock-per-instance**: Each rate limiter has its own lock
- **Critical Section**: Lock only held during token operations
- **No Background Threads**: Avoids thread management overhead

## Usage Examples

### Basic Rate Limiting
```java
// Allow 10 requests per second, burst up to 20
TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(20, 10);

// Non-blocking check
if (limiter.tryAcquire()) {
    // Process request
    processRequest();
} else {
    // Reject request
    return "Rate limit exceeded";
}
```

### API Rate Limiting
```java
public class APIController {
    // 100 requests per second, burst up to 500
    private final TokenBucketRateLimiter rateLimiter =
        new TokenBucketRateLimiter(500, 100);

    public Response handleRequest() {
        if (!rateLimiter.tryAcquire()) {
            return Response.status(429)
                          .entity("Too Many Requests")
                          .build();
        }

        return processAPIRequest();
    }
}
```

### Batch Processing with Token Costs
```java
// Different operations consume different amounts of tokens
TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 50);

// Lightweight operation (1 token)
if (limiter.tryAcquire(1)) {
    performLightOperation();
}

// Heavy operation (10 tokens)
if (limiter.tryAcquire(10)) {
    performHeavyOperation();
}
```

### Blocking Rate Limiter (Throttling)
```java
public void processWithThrottling() {
    try {
        // Wait for token to become available
        limiter.acquire();
        processRequest();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted while waiting for rate limit", e);
    }
}
```

### Monitoring Rate Limiter
```java
public class RateLimiterMonitor {
    private final TokenBucketRateLimiter limiter;

    public void logStatus() {
        System.out.printf("Available tokens: %d/%d (%.1f%% full)%n",
            limiter.getAvailableTokens(),
            limiter.getCapacity(),
            (double) limiter.getAvailableTokens() / limiter.getCapacity() * 100);
    }
}
```

## Configuration Guidelines

### Capacity (Bucket Size)
- **Small Capacity**: Strict rate limiting, less bursty traffic allowed
- **Large Capacity**: Allows larger bursts, more flexible for varying loads
- **Rule of Thumb**: Set to 2-5x your expected burst size

### Refill Rate
- **High Rate**: More permissive, allows more requests per second
- **Low Rate**: More restrictive, fewer requests per second
- **Consider**: Peak load, system capacity, SLA requirements

### Example Configurations
```java
// Strict API rate limiting: 1 request/second, no bursts
new TokenBucketRateLimiter(1, 1);

// Web service: 100 req/sec sustained, 500 req burst
new TokenBucketRateLimiter(500, 100);

// Background processing: 10 req/sec, small bursts
new TokenBucketRateLimiter(20, 10);

// High-throughput service: 1000 req/sec, large bursts
new TokenBucketRateLimiter(5000, 1000);
```

## Thread Safety Guarantees

### Concurrent Operations
- **Multiple Readers**: Safe concurrent calls to `getAvailableTokens()`
- **Multiple Writers**: Safe concurrent calls to `tryAcquire()` and `acquire()`
- **Mixed Operations**: Safe mixing of all method calls across threads

### Atomicity
- **Token Acquisition**: Either gets all requested tokens or none
- **Refill Operations**: Token refill is atomic with acquisition
- **State Consistency**: Internal state always remains consistent

### Performance Under Concurrency
- **Lock Contention**: Minimal due to fast operations
- **Throughput**: Scales well with multiple threads
- **Latency**: Consistent low latency for token operations

## Comparison with Other Algorithms

### vs Fixed Window
- **Advantage**: Smoother traffic distribution, allows bursts
- **Disadvantage**: More complex implementation

### vs Sliding Window
- **Advantage**: Simpler implementation, better burst handling
- **Disadvantage**: Less precise for exact request counting

### vs Leaky Bucket
- **Advantage**: Allows controlled bursts, better for varying loads, immediate processing
- **Disadvantage**: No request buffering, variable output rate

## Token Bucket vs Leaky Bucket Detailed Comparison

| Aspect | Token Bucket | Leaky Bucket |
|--------|--------------|--------------|
| **Algorithm** | Accumulates tokens, consumes on request | Queues requests, processes at fixed rate |
| **Burst Handling** | Immediate burst up to capacity | Buffers burst, processes steadily |
| **Output Rate** | Variable (up to refill rate + capacity) | Constant (leak rate) |
| **Memory Usage** | Low (stores token count) | Higher (stores actual requests) |
| **Request Latency** | Low (immediate for available tokens) | Variable (queuing delay) |
| **Traffic Smoothing** | Minimal | Excellent |
| **Implementation** | Simpler | More complex |
| **Overflow Behavior** | Reject immediately | Queue until full, then reject |
| **Best For** | APIs, microservices, low-latency | Traffic shaping, steady processing |

### When to Choose Token Bucket
- ✅ Need immediate processing for allowed requests
- ✅ Can handle variable output rates
- ✅ Want simple implementation
- ✅ Memory efficiency is important
- ✅ Serving user-facing APIs

### When to Choose Leaky Bucket
- ✅ Need steady, predictable output rate
- ✅ Want to smooth out traffic bursts
- ✅ Can accept queuing delays
- ✅ Protecting downstream systems from bursts
- ✅ Batch processing scenarios

## Best Practices

1. **Choose Appropriate Capacity**: Balance between flexibility and control
2. **Monitor Token Usage**: Track available tokens for capacity planning
3. **Handle Interruptions**: Properly handle `InterruptedException` in blocking calls
4. **Consider Token Costs**: Use multi-token acquisition for expensive operations
5. **Combine with Circuit Breakers**: Use together for comprehensive resilience
6. **Test Under Load**: Verify behavior under expected concurrent load