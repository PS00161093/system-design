# Circuit Breaker Pattern Implementation

## Overview
The Circuit Breaker pattern protects external API calls from cascading failures by monitoring failure rates and providing fail-fast behavior when the failure threshold is exceeded. This implementation provides automatic recovery testing and graceful degradation.

## Key Components

### CircuitBreakerState Enum
- **CLOSED**: Normal operation, all calls are allowed
- **OPEN**: All calls are blocked, service is considered unavailable
- **HALF_OPEN**: Limited test calls allowed to check service recovery

### Core Data Structures
- **AtomicInteger failureCount**: Thread-safe failure counter
- **AtomicReference<CircuitBreakerState> state**: Current circuit state
- **volatile Instant lastFailureTime**: Timestamp of last failure for cooldown calculation

## Configuration Parameters

### Constructor Parameters
- **failureThreshold**: Number of consecutive failures before opening circuit
- **cooldownPeriod**: Duration to wait before allowing test requests in HALF_OPEN state

## Core Operations

### execute(Callable<T> operation)
**Purpose**: Executes protected operation with circuit breaker logic
**Time Complexity**: O(1)
**Thread Safety**: Full thread safety with atomic operations

**Flow:**
1. Check current circuit state
2. If OPEN, throw CircuitBreakerOpenException
3. If CLOSED/HALF_OPEN, execute operation
4. On success: reset failure count, transition HALF_OPEN → CLOSED
5. On failure: increment failure count, check threshold

## State Transition Logic

### State Transition Flow Diagram
```
┌─────────────┐
│   CLOSED    │ ◄─── Initial State
│ (Normal)    │
└─────┬───────┘
      │ failure count >= threshold
      ▼
┌─────────────┐
│    OPEN     │
│ (Blocked)   │
└─────┬───────┘
      │ cooldown period elapsed
      ▼
┌─────────────┐
│ HALF_OPEN   │ ◄─── Test recovery
│ (Testing)   │
└─────┬───────┘
      │
      ├─── Success ───► CLOSED
      │
      └─── Failure ───► OPEN
```

### State Transitions

#### CLOSED → OPEN
- **Trigger**: `failureCount >= failureThreshold`
- **Action**: Block all subsequent calls
- **Duration**: Until cooldown period expires

#### OPEN → HALF_OPEN
- **Trigger**: `Instant.now().isAfter(lastFailureTime.plus(cooldownPeriod))`
- **Action**: Allow single test call
- **Synchronization**: Double-checked locking pattern

#### HALF_OPEN → CLOSED
- **Trigger**: Successful operation execution
- **Action**: Reset failure count, resume normal operation
- **Thread Safety**: Synchronized state update

#### HALF_OPEN → OPEN
- **Trigger**: Failed operation execution
- **Action**: Increment failure count, block calls again

## Implementation Details

### Thread Safety Strategy
```java
// Double-checked locking for state transitions
private CircuitBreakerState getCurrentState() {
    CircuitBreakerState currentState = state.get();

    if (currentState == CircuitBreakerState.OPEN) {
        synchronized (stateLock) {
            // Double-check pattern
            if (state.get() == CircuitBreakerState.OPEN) {
                if (shouldAttemptReset()) {
                    state.set(CircuitBreakerState.HALF_OPEN);
                    return CircuitBreakerState.HALF_OPEN;
                }
            }
        }
    }
    return state.get();
}
```

### Failure Handling
```java
private void onFailure() {
    lastFailureTime = Instant.now();
    int failures = failureCount.incrementAndGet();

    if (failures >= failureThreshold) {
        synchronized (stateLock) {
            state.set(CircuitBreakerState.OPEN);
        }
    }
}
```

### Success Handling
```java
private void onSuccess() {
    failureCount.set(0);
    if (state.get() == CircuitBreakerState.HALF_OPEN) {
        synchronized (stateLock) {
            if (state.get() == CircuitBreakerState.HALF_OPEN) {
                state.set(CircuitBreakerState.CLOSED);
            }
        }
    }
}
```

## Usage Examples

### Basic Usage
```java
// Create circuit breaker: 3 failures threshold, 30 second cooldown
CircuitBreaker circuitBreaker = new CircuitBreaker(3, Duration.ofSeconds(30));

// Protected API call
try {
    String result = circuitBreaker.execute(() -> {
        return externalApiService.getData();
    });
    System.out.println("API call successful: " + result);
} catch (CircuitBreakerOpenException e) {
    System.out.println("Circuit breaker is OPEN - using fallback");
    return getFallbackData();
} catch (Exception e) {
    System.out.println("API call failed: " + e.getMessage());
}
```

### HTTP Service Protection
```java
CircuitBreaker httpCircuitBreaker = new CircuitBreaker(5, Duration.ofMinutes(2));

public String callExternalService(String endpoint) {
    try {
        return httpCircuitBreaker.execute(() -> {
            HttpResponse response = httpClient.get(endpoint);
            if (response.getStatusCode() >= 500) {
                throw new ServiceException("Server error: " + response.getStatusCode());
            }
            return response.getBody();
        });
    } catch (CircuitBreakerOpenException e) {
        // Circuit is open - return cached data or default response
        return getCachedResponse(endpoint);
    } catch (Exception e) {
        // Handle other exceptions
        throw new ServiceUnavailableException("Service call failed", e);
    }
}
```

### Database Connection Protection
```java
CircuitBreaker dbCircuitBreaker = new CircuitBreaker(3, Duration.ofMinutes(1));

public User getUserById(Long id) {
    try {
        return dbCircuitBreaker.execute(() -> {
            Connection conn = dataSource.getConnection();
            // Database query logic
            return userRepository.findById(id);
        });
    } catch (CircuitBreakerOpenException e) {
        // Database is unavailable - check cache
        return userCache.get(id);
    }
}
```

## Configuration Strategies

### Conservative Settings (High Availability)
```java
// Low threshold, long cooldown - prevents frequent state changes
CircuitBreaker conservative = new CircuitBreaker(2, Duration.ofMinutes(5));
```

### Aggressive Settings (Quick Recovery)
```java
// High threshold, short cooldown - allows more failures, faster recovery
CircuitBreaker aggressive = new CircuitBreaker(10, Duration.ofSeconds(15));
```

### Adaptive Settings Based on Service SLA
```java
// Critical service: Quick failure detection
CircuitBreaker criticalService = new CircuitBreaker(3, Duration.ofSeconds(30));

// Non-critical service: More tolerance
CircuitBreaker nonCriticalService = new CircuitBreaker(10, Duration.ofMinutes(2));
```

## Monitoring and Metrics

### State Monitoring
```java
CircuitBreakerState currentState = circuitBreaker.getState();
int currentFailures = circuitBreaker.getFailureCount();
int threshold = circuitBreaker.getFailureThreshold();
Duration cooldown = circuitBreaker.getCooldownPeriod();

System.out.printf("Circuit Breaker Status: %s (%d/%d failures)%n",
                  currentState, currentFailures, threshold);
```

### Integration with Monitoring Systems
```java
// Metrics collection example
public class CircuitBreakerMetrics {
    private final MeterRegistry meterRegistry;
    private final CircuitBreaker circuitBreaker;

    public CircuitBreakerMetrics(CircuitBreaker cb, MeterRegistry registry) {
        this.circuitBreaker = cb;
        this.meterRegistry = registry;

        // Register gauges
        Gauge.builder("circuit.breaker.state")
             .register(registry, cb, this::getStateAsNumber);

        Gauge.builder("circuit.breaker.failure.count")
             .register(registry, cb, CircuitBreaker::getFailureCount);
    }

    private double getStateAsNumber(CircuitBreaker cb) {
        switch (cb.getState()) {
            case CLOSED: return 0;
            case HALF_OPEN: return 1;
            case OPEN: return 2;
            default: return -1;
        }
    }
}
```

## Error Handling

### Custom Exceptions
```java
// Circuit breaker specific exception
public class CircuitBreakerOpenException extends RuntimeException {
    public CircuitBreakerOpenException(String message) {
        super(message);
    }
}
```

### Exception Handling Strategy
```java
try {
    return circuitBreaker.execute(() -> riskyOperation());
} catch (CircuitBreakerOpenException e) {
    // Circuit is open - handle gracefully
    metrics.incrementCounter("circuit.breaker.open.calls");
    return handleCircuitOpen();
} catch (ServiceException e) {
    // Business logic exception - don't retry immediately
    metrics.incrementCounter("service.errors");
    throw e;
} catch (Exception e) {
    // Unexpected exception - log and handle
    logger.error("Unexpected error in circuit breaker", e);
    throw new ServiceException("Operation failed", e);
}
```

## Testing Considerations

### Unit Testing
```java
@Test
public void testCircuitBreakerOpensAfterThresholdFailures() {
    CircuitBreaker cb = new CircuitBreaker(2, Duration.ofSeconds(1));

    // First failure
    assertThrows(Exception.class, () ->
        cb.execute(() -> { throw new RuntimeException("Error"); }));
    assertEquals(CircuitBreakerState.CLOSED, cb.getState());

    // Second failure - should open circuit
    assertThrows(Exception.class, () ->
        cb.execute(() -> { throw new RuntimeException("Error"); }));
    assertEquals(CircuitBreakerState.OPEN, cb.getState());

    // Subsequent calls should fail fast
    assertThrows(CircuitBreakerOpenException.class, () ->
        cb.execute(() -> "success"));
}
```

### Integration Testing
```java
@Test
public void testCircuitBreakerWithRealService() throws InterruptedException {
    CircuitBreaker cb = new CircuitBreaker(3, Duration.ofSeconds(2));
    ExternalService service = new ExternalService();

    // Simulate service failures
    service.setFailureMode(true);

    // Trip the circuit breaker
    for (int i = 0; i < 3; i++) {
        try {
            cb.execute(service::call);
        } catch (Exception ignored) {}
    }

    assertEquals(CircuitBreakerState.OPEN, cb.getState());

    // Wait for cooldown
    Thread.sleep(2100);

    // Service recovers
    service.setFailureMode(false);

    // Should transition to HALF_OPEN, then CLOSED
    String result = cb.execute(service::call);
    assertEquals("success", result);
    assertEquals(CircuitBreakerState.CLOSED, cb.getState());
}
```

## Performance Characteristics

### Time Complexity
- **execute()**: O(1) - Constant time state checks and updates
- **State transitions**: O(1) - Atomic operations with minimal synchronization

### Memory Usage
- **Minimal overhead**: Few instance variables per circuit breaker
- **Thread-safe**: Uses atomic references and volatile fields
- **No memory leaks**: No accumulating data structures

### Throughput
- **CLOSED state**: Near-native call performance
- **OPEN state**: Extremely fast rejection (no external calls)
- **HALF_OPEN state**: Single synchronization point during transition

## Best Practices

### 1. Appropriate Threshold Selection
```java
// Consider service SLA and expected failure patterns
// Too low: Frequent false positives
// Too high: Slow failure detection
CircuitBreaker cb = new CircuitBreaker(
    calculateOptimalThreshold(serviceSLA),
    Duration.ofSeconds(30)
);
```

### 2. Fallback Strategy Implementation
```java
public String getDataWithFallback() {
    try {
        return circuitBreaker.execute(() -> primaryService.getData());
    } catch (CircuitBreakerOpenException e) {
        // Fallback options in order of preference:
        // 1. Cached data
        String cached = cache.get("data");
        if (cached != null) return cached;

        // 2. Secondary service
        try {
            return secondaryService.getData();
        } catch (Exception secondary) {
            // 3. Default/static response
            return getDefaultData();
        }
    }
}
```

### 3. Monitoring and Alerting
```java
// Alert when circuit opens
if (circuitBreaker.getState() == CircuitBreakerState.OPEN) {
    alertingService.sendAlert(
        "Circuit breaker opened for service: " + serviceName
    );
}

// Monitor failure rates
double failureRate = (double) circuitBreaker.getFailureCount() /
                    circuitBreaker.getFailureThreshold();
if (failureRate > 0.7) {
    logger.warn("Circuit breaker approaching threshold: {}%",
                failureRate * 100);
}
```

## Integration Patterns

### Spring Boot Integration
```java
@Configuration
public class CircuitBreakerConfig {

    @Bean
    @Qualifier("userService")
    public CircuitBreaker userServiceCircuitBreaker() {
        return new CircuitBreaker(3, Duration.ofSeconds(30));
    }

    @Bean
    @Qualifier("paymentService")
    public CircuitBreaker paymentServiceCircuitBreaker() {
        return new CircuitBreaker(2, Duration.ofMinutes(1));
    }
}

@Service
public class UserService {

    @Autowired
    @Qualifier("userService")
    private CircuitBreaker circuitBreaker;

    public User getUser(Long id) {
        return circuitBreaker.execute(() ->
            userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id))
        );
    }
}
```

This circuit breaker implementation provides robust protection against cascading failures while maintaining high performance and offering flexible configuration options for different service reliability requirements.
