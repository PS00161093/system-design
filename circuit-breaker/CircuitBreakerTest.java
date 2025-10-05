package pair.circuitbreaker;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive test suite for CircuitBreaker functionality.
 * Note: This is a simple test class. In production, use JUnit or TestNG.
 */
public class CircuitBreakerTest {

    public static void main(String[] args) {
        System.out.println("=== Circuit Breaker Test Suite ===\n");

        runAllTests();

        System.out.println("\n=== All Tests Completed ===");
    }

    private static void runAllTests() {
        testInitialState();
        testFailureCountingAndCircuitOpening();
        testCircuitOpenBlocksRequests();
        testCircuitRecoveryAfterCooldown();
        testHalfOpenStateTransitions();
        testConcurrentAccess();
        testEdgeCases();
    }

    private static void testInitialState() {
        System.out.println("Test: Initial State");
        CircuitBreaker cb = new CircuitBreaker(3, Duration.ofSeconds(1));

        assert cb.getState() == CircuitBreakerState.CLOSED : "Initial state should be CLOSED";
        assert cb.getFailureCount() == 0 : "Initial failure count should be 0";
        assert cb.getFailureThreshold() == 3 : "Failure threshold should be 3";
        assert cb.getCooldownPeriod().equals(Duration.ofSeconds(1)) : "Cooldown period should be 1 second";

        System.out.println("✓ Initial state test passed\n");
    }

    private static void testFailureCountingAndCircuitOpening() {
        System.out.println("Test: Failure Counting and Circuit Opening");
        CircuitBreaker cb = new CircuitBreaker(2, Duration.ofSeconds(1));

        // First failure
        try {
            cb.execute(() -> { throw new RuntimeException("Test failure"); });
        } catch (Exception e) {
            // Expected
        }
        assert cb.getState() == CircuitBreakerState.CLOSED : "Should remain CLOSED after 1 failure";
        assert cb.getFailureCount() == 1 : "Failure count should be 1";

        // Second failure - should open circuit
        try {
            cb.execute(() -> { throw new RuntimeException("Test failure"); });
        } catch (Exception e) {
            // Expected
        }
        assert cb.getState() == CircuitBreakerState.OPEN : "Should be OPEN after reaching threshold";
        assert cb.getFailureCount() == 2 : "Failure count should be 2";

        System.out.println("✓ Failure counting and circuit opening test passed\n");
    }

    private static void testCircuitOpenBlocksRequests() {
        System.out.println("Test: Circuit Open Blocks Requests");
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofSeconds(10)); // Long cooldown

        // Trigger circuit opening
        try {
            cb.execute(() -> { throw new RuntimeException("Test failure"); });
        } catch (Exception e) {
            // Expected
        }

        assert cb.getState() == CircuitBreakerState.OPEN : "Circuit should be OPEN";

        // Next request should be blocked
        boolean blocked = false;
        try {
            cb.execute(() -> "Success");
        } catch (CircuitBreakerOpenException e) {
            blocked = true;
        } catch (Exception e) {
            // Should not reach here
        }

        assert blocked : "Request should be blocked when circuit is OPEN";
        System.out.println("✓ Circuit open blocks requests test passed\n");
    }

    private static void testCircuitRecoveryAfterCooldown() {
        System.out.println("Test: Circuit Recovery After Cooldown");
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofMillis(100)); // Short cooldown

        // Open circuit
        try {
            cb.execute(() -> { throw new RuntimeException("Test failure"); });
        } catch (Exception e) {
            // Expected
        }
        assert cb.getState() == CircuitBreakerState.OPEN : "Circuit should be OPEN";

        // Wait for cooldown
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Next request should transition to HALF_OPEN
        try {
            String result = cb.execute(() -> "Success");
            assert "Success".equals(result) : "Request should succeed";
            assert cb.getState() == CircuitBreakerState.CLOSED : "Circuit should be CLOSED after success";
            assert cb.getFailureCount() == 0 : "Failure count should reset to 0";
        } catch (Exception e) {
            throw new AssertionError("Request should not fail: " + e.getMessage());
        }

        System.out.println("✓ Circuit recovery after cooldown test passed\n");
    }

    private static void testHalfOpenStateTransitions() {
        System.out.println("Test: Half-Open State Transitions");
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofMillis(100));

        // Open circuit
        try {
            cb.execute(() -> { throw new RuntimeException("Test failure"); });
        } catch (Exception e) {
            // Expected
        }

        // Wait for cooldown
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Test failure in HALF_OPEN state should reopen circuit
        try {
            cb.execute(() -> { throw new RuntimeException("Test failure in half-open"); });
        } catch (CircuitBreakerOpenException e) {
            // Should not happen - first call after cooldown should be allowed
            throw new AssertionError("First call after cooldown should be allowed");
        } catch (Exception e) {
            // Expected failure
        }

        assert cb.getState() == CircuitBreakerState.OPEN : "Circuit should reopen after failure in HALF_OPEN";

        System.out.println("✓ Half-open state transitions test passed\n");
    }

    private static void testConcurrentAccess() {
        System.out.println("Test: Concurrent Access");
        CircuitBreaker cb = new CircuitBreaker(5, Duration.ofSeconds(1));
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger blockedCount = new AtomicInteger(0);

        // Create multiple threads to test concurrent access
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10; j++) {
                    try {
                        cb.execute(() -> {
                            if (Math.random() < 0.3) { // 30% failure rate
                                throw new RuntimeException("Random failure");
                            }
                            return "Success";
                        });
                        successCount.incrementAndGet();
                    } catch (CircuitBreakerOpenException e) {
                        blockedCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }

                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int total = successCount.get() + failureCount.get() + blockedCount.get();
        System.out.printf("Concurrent test results: Success=%d, Failed=%d, Blocked=%d, Total=%d%n",
                successCount.get(), failureCount.get(), blockedCount.get(), total);

        assert total == 100 : "Total operations should be 100";
        System.out.println("✓ Concurrent access test passed\n");
    }

    private static void testEdgeCases() {
        System.out.println("Test: Edge Cases");

        // Test invalid parameters
        try {
            new CircuitBreaker(0, Duration.ofSeconds(1));
            assert false : "Should throw exception for zero failure threshold";
        } catch (IllegalArgumentException e) {
            // Expected
        }

        try {
            new CircuitBreaker(1, Duration.ofSeconds(-1));
            assert false : "Should throw exception for negative cooldown";
        } catch (IllegalArgumentException e) {
            // Expected
        }

        try {
            new CircuitBreaker(1, null);
            assert false : "Should throw exception for null cooldown";
        } catch (IllegalArgumentException e) {
            // Expected
        }

        // Test reset functionality
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofSeconds(1));
        try {
            cb.execute(() -> { throw new RuntimeException("Test failure"); });
        } catch (Exception e) {
            // Expected
        }
        assert cb.getState() == CircuitBreakerState.OPEN : "Circuit should be OPEN";

        cb.reset();
        assert cb.getState() == CircuitBreakerState.CLOSED : "Circuit should be CLOSED after reset";
        assert cb.getFailureCount() == 0 : "Failure count should be 0 after reset";

        System.out.println("✓ Edge cases test passed\n");
    }
}