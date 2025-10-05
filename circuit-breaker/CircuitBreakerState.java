package pair.circuitbreaker;

/**
 * Represents the three states of a circuit breaker.
 */
public enum CircuitBreakerState {
    /**
     * Circuit is closed - requests flow through normally.
     * Failures are counted and circuit opens when threshold is reached.
     */
    CLOSED,

    /**
     * Circuit is open - all requests are blocked immediately.
     * Circuit remains open during cooldown period.
     */
    OPEN,

    /**
     * Circuit is half-open - allows a limited number of test requests.
     * If test requests succeed, circuit closes. If they fail, circuit opens again.
     */
    HALF_OPEN
}