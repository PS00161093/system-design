package pair.ratelimiter;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe Leaky Bucket Rate Limiter implementation.
 * <p>
 * The leaky bucket algorithm works by:
 * 1. Maintaining a bucket with current water level (requests)
 * 2. Incoming requests add water to the bucket
 * 3. Water leaks out at a constant rate (leak rate)
 * 4. If bucket overflows (exceeds capacity), requests are rejected
 * 5. The leak rate determines the sustained output rate
 * <p>
 * Key characteristics:
 * - Smooths traffic by maintaining a steady output rate
 * - Rejects requests when bucket is full (no buffering like queuing)
 * - Water level represents accumulated requests waiting to be processed
 */
public class LeakyBucketRateLimiter {

    private final double capacity;              // Maximum water level (requests)
    private final double leakRate;              // Requests leaked per second
    private final Duration leakInterval;        // Time to leak one request

    private double currentLevel;                // Current water level in bucket
    private Instant lastLeakTime;              // Last time water leaked

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Creates a new LeakyBucket rate limiter.
     *
     * @param capacity Maximum water level (number of requests) the bucket can hold
     * @param leakRate Number of requests that leak out per second
     */
    public LeakyBucketRateLimiter(double capacity, double leakRate) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        if (leakRate <= 0) {
            throw new IllegalArgumentException("Leak rate must be positive");
        }

        this.capacity = capacity;
        this.leakRate = leakRate;
        this.leakInterval = Duration.ofNanos((long) (1_000_000_000L / leakRate));
        this.currentLevel = 0.0;  // Start with empty bucket
        this.lastLeakTime = Instant.now();
    }

    /**
     * Attempts to add a request to the bucket.
     *
     * @return true if request was accepted, false if bucket would overflow
     */
    public boolean allowRequest() {
        return allowRequest(1.0);
    }

    /**
     * Attempts to add a request with specific weight to the bucket.
     *
     * @param requestWeight Weight of the request (e.g., 1.0 for normal, 2.0 for heavy)
     * @return true if request was accepted, false if bucket would overflow
     */
    public boolean allowRequest(double requestWeight) {
        if (requestWeight <= 0) {
            throw new IllegalArgumentException("Request weight must be positive");
        }

        lock.lock();
        try {
            // First, leak water based on elapsed time
            leak();

            // Check if adding this request would overflow the bucket
            if (currentLevel + requestWeight <= capacity) {
                // Add the request (water) to the bucket
                currentLevel += requestWeight;
                System.out.printf("[ACCEPTED] Request weight %.1f added. Current level: %.2f/%.0f%n",
                                requestWeight, currentLevel, capacity);
                return true;
            } else {
                // Bucket would overflow - reject the request
                System.out.printf("[REJECTED] Request weight %.1f rejected. Would exceed capacity: %.2f + %.1f > %.0f%n",
                                requestWeight, currentLevel, requestWeight, capacity);
                return false;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Leaks water from the bucket based on elapsed time.
     * Must be called while holding the lock.
     */
    private void leak() {
        Instant now = Instant.now();
        Duration elapsed = Duration.between(lastLeakTime, now);

        if (elapsed.toNanos() > 0 && currentLevel > 0) {
            // Calculate how much water should have leaked out
            double leakAmount = (elapsed.toNanos() / (double) leakInterval.toNanos());

            // Leak water, but don't go below 0
            double previousLevel = currentLevel;
            currentLevel = Math.max(0.0, currentLevel - leakAmount);

            if (previousLevel != currentLevel) {
                System.out.printf("[LEAKED] %.3f requests leaked out. Level: %.3f → %.3f%n",
                                previousLevel - currentLevel, previousLevel, currentLevel);
            }

            // Update last leak time
            lastLeakTime = now;
        }
    }

    /**
     * Forces leaking of water based on elapsed time.
     * Useful for testing or manual leak processing.
     */
    public void processLeaks() {
        lock.lock();
        try {
            leak();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current water level in the bucket.
     *
     * @return Current level (0.0 to capacity)
     */
    public double getCurrentLevel() {
        lock.lock();
        try {
            leak(); // Update level before returning
            return currentLevel;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the maximum capacity of the bucket.
     *
     * @return Maximum water level the bucket can hold
     */
    public double getCapacity() {
        return capacity;
    }

    /**
     * Returns the leak rate (requests per second).
     *
     * @return Number of requests that leak out per second
     */
    public double getLeakRate() {
        return leakRate;
    }

    /**
     * Returns the current utilization percentage of the bucket.
     *
     * @return Utilization percentage (0.0 to 1.0)
     */
    public double getUtilization() {
        return getCurrentLevel() / capacity;
    }

    /**
     * Checks if the bucket is currently full.
     *
     * @return true if bucket is at capacity
     */
    public boolean isFull() {
        return getCurrentLevel() >= capacity;
    }

    /**
     * Checks if the bucket is currently empty.
     *
     * @return true if bucket has no water
     */
    public boolean isEmpty() {
        return getCurrentLevel() == 0.0;
    }

    /**
     * Returns the time it would take for the bucket to empty completely.
     *
     * @return Duration to empty the bucket at current leak rate
     */
    public Duration getTimeToEmpty() {
        double level = getCurrentLevel();
        if (level == 0.0) {
            return Duration.ZERO;
        }
        long nanosToEmpty = (long) (level * leakInterval.toNanos());
        return Duration.ofNanos(nanosToEmpty);
    }

    @Override
    public String toString() {
        return String.format("LeakyBucketRateLimiter[capacity=%.0f, leakRate=%.1f/sec, level=%.2f, utilization=%.1f%%]",
                           capacity, leakRate, getCurrentLevel(), getUtilization() * 100);
    }
}