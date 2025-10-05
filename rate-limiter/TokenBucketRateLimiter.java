
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe Token Bucket Rate Limiter implementation.
 * <p>
 * The token bucket algorithm works by:
 * 1. Tokens are added to a bucket at a fixed rate (refill rate)
 * 2. Each request consumes one or more tokens
 * 3. If insufficient tokens are available, the request is rejected or delayed
 * 4. The bucket has a maximum capacity to prevent token accumulation
 */
public class TokenBucketRateLimiter {

    private final long capacity;           // Maximum number of tokens in bucket
    private final long refillRate;         // Tokens added per second
    private final Duration refillPeriod;   // Time between refills (1 second / refillRate)

    private long availableTokens;          // Current number of tokens
    private Instant lastRefillTime;        // Last time tokens were added

    private final ReentrantLock lock = new ReentrantLock();

    public TokenBucketRateLimiter(long capacity, long refillRate) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        if (refillRate <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }

        this.capacity = capacity;
        this.refillRate = refillRate;
        this.refillPeriod = Duration.ofNanos(1_000_000_000L / refillRate); // 1 second / rate
        this.availableTokens = capacity; // Start with full bucket
        this.lastRefillTime = Instant.now();
    }

    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    public boolean tryAcquire(long tokens) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("Token count must be positive");
        }

        lock.lock();
        try {
            refillTokens();

            if (availableTokens >= tokens) {
                availableTokens -= tokens;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquires a single token, blocking until one becomes available.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        acquire(1);
    }

    /**
     * Acquires the specified number of tokens, blocking until they become available.
     *
     * @param tokens Number of tokens to acquire
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire(long tokens) throws InterruptedException {
        if (tokens <= 0) {
            throw new IllegalArgumentException("Token count must be positive");
        }

        while (!tryAcquire(tokens)) {
            // Calculate how long to wait for next token
            long waitTimeNanos = refillPeriod.toNanos();
            Thread.sleep(waitTimeNanos / 1_000_000, (int) (waitTimeNanos % 1_000_000));
        }
    }

    /**
     * Refills the bucket based on time elapsed since last refill.
     * Must be called while holding the lock.
     */
    private void refillTokens() {
        Instant now = Instant.now();
        Duration elapsed = Duration.between(lastRefillTime, now);

        if (elapsed.compareTo(refillPeriod) >= 0) {
            // Calculate how many tokens to add based on elapsed time
            long tokensToAdd = elapsed.toNanos() / refillPeriod.toNanos();

            // Add tokens but don't exceed capacity
            availableTokens = Math.min(capacity, availableTokens + tokensToAdd);

            // Update last refill time
            lastRefillTime = now;
        }
    }

    public long getAvailableTokens() {
        lock.lock();
        try {
            refillTokens();
            return availableTokens;
        } finally {
            lock.unlock();
        }
    }

    public long getCapacity() {
        return capacity;
    }

    public long getRefillRate() {
        return refillRate;
    }

    @Override
    public String toString() {
        return String.format("TokenBucketRateLimiter[capacity=%d, refillRate=%d/sec, available=%d]",
                capacity, refillRate, getAvailableTokens());
    }
}
