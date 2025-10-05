package pair.circuitbreaker;

import java.time.Duration;
import java.util.Random;

/**
 * Example external API service that simulates network calls with potential failures.
 */
public class ExternalApiService {
    private final Random random = new Random();
    private final double failureRate;

    public ExternalApiService(double failureRate) {
        this.failureRate = failureRate;
    }

    /**
     * Simulates an external API call that may fail.
     */
    public String callExternalApi(String request) throws Exception {
        // Simulate network delay
        Thread.sleep(100 + random.nextInt(200));

        // Simulate failure based on failure rate
        if (random.nextDouble() < failureRate) {
            throw new RuntimeException("External API call failed: " + request);
        }

        return "Success response for: " + request;
    }
}

/**
 * Demonstration of Circuit Breaker pattern usage with external API calls.
 */
class CircuitBreakerExample {
    public static void main(String[] args) {
        // Create external API service with 70% failure rate (high failure for demo)
        ExternalApiService apiService = new ExternalApiService(0.7);

        // Create circuit breaker: 3 failures trigger open state, 2 second cooldown
        CircuitBreaker circuitBreaker = new CircuitBreaker(3, Duration.ofSeconds(2));

        System.out.println("=== Circuit Breaker Demo ===");
        System.out.println("Failure threshold: " + circuitBreaker.getFailureThreshold());
        System.out.println("Cooldown period: " + circuitBreaker.getCooldownPeriod().getSeconds() + " seconds");
        System.out.println();

        // Simulate multiple API calls
        for (int i = 1; i <= 20; i++) {
            try {
                String request = "Request-" + i;

                String result = circuitBreaker.execute(() -> apiService.callExternalApi(request));

                System.out.printf("Call %d: SUCCESS - %s (State: %s, Failures: %d)%n",
                    i, result, circuitBreaker.getState(), circuitBreaker.getFailureCount());

            } catch (CircuitBreakerOpenException e) {
                System.out.printf("Call %d: BLOCKED - %s (State: %s, Failures: %d)%n",
                    i, e.getMessage(), circuitBreaker.getState(), circuitBreaker.getFailureCount());

            } catch (Exception e) {
                System.out.printf("Call %d: FAILED - %s (State: %s, Failures: %d)%n",
                    i, e.getMessage(), circuitBreaker.getState(), circuitBreaker.getFailureCount());
            }

            // Add delay between calls to observe state transitions
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("\n=== Final State ===");
        System.out.println("Circuit Breaker State: " + circuitBreaker.getState());
        System.out.println("Failure Count: " + circuitBreaker.getFailureCount());
    }
}

/**
 * Example of using Circuit Breaker in a more realistic scenario.
 */
class RealisticApiExample {
    private final ExternalApiService apiService;
    private final CircuitBreaker circuitBreaker;

    public RealisticApiExample() {
        this.apiService = new ExternalApiService(0.3); // 30% failure rate
        this.circuitBreaker = new CircuitBreaker(5, Duration.ofSeconds(10));
    }

    /**
     * Makes a protected API call with fallback behavior.
     */
    public String makeProtectedApiCall(String request) {
        try {
            return circuitBreaker.execute(() -> apiService.callExternalApi(request));
        } catch (CircuitBreakerOpenException e) {
            return handleCircuitOpen(request);
        } catch (Exception e) {
            return handleApiFailure(request, e);
        }
    }

    private String handleCircuitOpen(String request) {
        System.out.println("Circuit is OPEN - using fallback for: " + request);
        return "FALLBACK: Cached or default response for " + request;
    }

    private String handleApiFailure(String request, Exception e) {
        System.out.println("API call failed: " + e.getMessage());
        return "ERROR: Failed to process " + request;
    }

    public static void demonstrateRealisticUsage() {
        System.out.println("\n=== Realistic Usage Example ===");
        RealisticApiExample example = new RealisticApiExample();

        for (int i = 1; i <= 15; i++) {
            String result = example.makeProtectedApiCall("Data-" + i);
            System.out.printf("Request %d: %s%n", i, result);

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}