package pair.circuitbreaker;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit Breaker implementation for protecting external API calls.
 *
 * The circuit breaker has three states:
 * - CLOSED: Normal operation, failures are counted
 * - OPEN: All calls are blocked, waiting for cooldown period
 * - HALF_OPEN: Limited test calls allowed to check if service recovered
 */
public class CircuitBreaker {
    private final int failureThreshold;
    private final Duration cooldownPeriod;
    private final AtomicInteger failureCount;
    private final AtomicReference<CircuitBreakerState> state;
    private volatile Instant lastFailureTime;
    private final Object stateLock = new Object();

    /**
     * Creates a new CircuitBreaker with specified parameters.
     *
     * @param failureThreshold Number of consecutive failures before opening circuit
     * @param cooldownPeriod Duration to wait before allowing test requests
     */
    public CircuitBreaker(int failureThreshold, Duration cooldownPeriod) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("Failure threshold must be positive");
        }
        if (cooldownPeriod == null || cooldownPeriod.isNegative()) {
            throw new IllegalArgumentException("Cooldown period must be positive");
        }

        this.failureThreshold = failureThreshold;
        this.cooldownPeriod = cooldownPeriod;
        this.failureCount = new AtomicInteger(0);
        this.state = new AtomicReference<>(CircuitBreakerState.CLOSED);
        this.lastFailureTime = Instant.now();
    }

    /**
     * Executes the given operation with circuit breaker protection.
     *
     * @param operation The operation to execute
     * @param <T> Return type of the operation
     * @return Result of the operation
     * @throws CircuitBreakerOpenException if circuit is open
     * @throws Exception if operation fails
     */
    public <T> T execute(Callable<T> operation) throws Exception {
        CircuitBreakerState currentState = getCurrentState();

        if (currentState == CircuitBreakerState.OPEN) {
            throw new CircuitBreakerOpenException("Circuit breaker is OPEN");
        }

        try {
            T result = operation.call();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            throw e;
        }
    }

    /**
     * Gets the current state of the circuit breaker, updating if necessary.
     */
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

    /**
     * Determines if enough time has passed to attempt resetting the circuit.
     */
    private boolean shouldAttemptReset() {
        return Instant.now().isAfter(lastFailureTime.plus(cooldownPeriod));
    }

    /**
     * Handles successful operation execution.
     */
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

    /**
     * Handles failed operation execution.
     */
    private void onFailure() {
        lastFailureTime = Instant.now();
        int failures = failureCount.incrementAndGet();

        if (failures >= failureThreshold) {
            synchronized (stateLock) {
                state.set(CircuitBreakerState.OPEN);
            }
        }
    }

    /**
     * Gets the current state of the circuit breaker.
     */
    public CircuitBreakerState getState() {
        return getCurrentState();
    }

    /**
     * Gets the current failure count.
     */
    public int getFailureCount() {
        return failureCount.get();
    }

    /**
     * Gets the failure threshold.
     */
    public int getFailureThreshold() {
        return failureThreshold;
    }

    /**
     * Gets the cooldown period.
     */
    public Duration getCooldownPeriod() {
        return cooldownPeriod;
    }

    /**
     * Resets the circuit breaker to CLOSED state (for testing purposes).
     */
    public void reset() {
        synchronized (stateLock) {
            state.set(CircuitBreakerState.CLOSED);
            failureCount.set(0);
            lastFailureTime = Instant.now();
        }
    }
}