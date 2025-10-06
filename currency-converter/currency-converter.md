# Currency Converter Implementation in Java

## Overview

A robust Java currency conversion system that handles exchange rate management, multi-path conversion routing, and comprehensive error handling. The implementation supports direct conversions, inverse rate calculations, USD-based triangular conversions, and multi-hop currency conversion paths with full thread safety.

## Core Architecture

### Key Components

- **CurrencyConverter**: Main conversion engine with caching and routing logic
- **CurrencyConverterService**: Service layer for external API integration with automatic updates
- **ExchangeRate**: Immutable rate objects with timestamp tracking
- **ConversionResult**: Detailed conversion results with path information
- **Exception Hierarchy**: Comprehensive exception classes for different error scenarios
- **MockExchangeRateProvider**: Test provider for demonstration and development

### Data Structures

#### Primary Data Structures

```java
// Thread-safe exchange rate cache with concurrent access
private final Map<String, ExchangeRate> rateCache = new ConcurrentHashMap<>();

// Conversion result cache for performance optimization
private final Map<String, ConversionResult> conversionCache = new ConcurrentHashMap<>();

// Supported currencies set for validation (thread-safe)
private final Set<String> supportedCurrencies = ConcurrentHashMap.newKeySet();
```

#### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Currency Converter Architecture                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│ ┌─────────────┐   convert()   ┌──────────────────┐   getRate()  ┌─────────┐  │
│ │   Client    │──────────────▶│ CurrencyConverter│─────────────▶│ Rate    │  │
│ │ Application │               │     (Core)       │              │ Cache   │  │
│ └─────────────┘               └──────────────────┘              └─────────┘  │
│                                        │                                    │
│                                        ▼                                    │
│ ┌─────────────────────────────────────────────────────────────────────────┐  │
│ │                    Conversion Path Resolution                           │  │
│ │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐ │  │
│ │  │   Direct    │  │   Inverse   │  │ Triangular  │  │   Multi-hop     │ │  │
│ │  │ USD->EUR    │  │ EUR->USD    │  │ EUR->JPY    │  │ EUR->GBP->JPY   │ │  │
│ │  │  (cached)   │  │ (1/rate)    │  │(via USD)    │  │(via GBP)        │ │  │
│ │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────┘ │  │
│ └─────────────────────────────────────────────────────────────────────────┘  │
│                                        │                                    │
│                                        ▼                                    │
│ ┌─────────────┐   fetchRates()  ┌──────────────────┐                       │
│ │   Service   │◄────────────────│ ExchangeRate     │                       │
│ │   Layer     │                 │ Provider         │                       │
│ │             │─────────────────▶│ (External API)   │                       │
│ └─────────────┘   updateRates() └──────────────────┘                       │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Core Operations

### Currency Conversion Algorithm

#### Main Conversion Flow

```
┌─────────────────┐
│ convert(from,   │
│ to, amount)     │
└─────┬───────────┘
      │
      ▼
┌─────────────────┐       ┌──────────────────┐
│ Same currency?  │──Yes──│ Return amount    │
└─────┬───────────┘       │ (rate = 1.0)     │
      │No                 └──────────────────┘
      ▼
┌─────────────────┐       ┌──────────────────┐
│ Check cache?    │──Hit──│ Return cached    │
└─────┬───────────┘       │ result           │
      │Miss               └──────────────────┘
      ▼
┌─────────────────┐
│ getExchangeRate │
│ (from, to)      │
└─────┬───────────┘
      │
      ▼
┌─────────────────┐
│ Calculate:      │
│ amount * rate   │
└─────┬───────────┘
      │
      ▼
┌─────────────────┐
│ Cache result &  │
│ return with     │
│ path info       │
└─────────────────┘
```

#### Enhanced Exchange Rate Resolution Strategy

```
┌─────────────────┐
│ getExchangeRate │
│ (from, to)      │
└─────┬───────────┘
      │
      ▼
┌─────────────────────┐       ┌──────────────────┐
│ Direct rate exists? │──Yes──│ Return direct    │
│ (FROM_TO) & fresh?  │       │ rate             │
└─────┬───────────────┘       └──────────────────┘
      │No
      ▼
┌─────────────────────┐       ┌──────────────────┐
│ Inverse rate exists?│──Yes──│ Return 1/rate    │
│ (TO_FROM) & fresh?  │       │ (inverse calc)   │
└─────┬───────────────┘       └──────────────────┘
      │No
      ▼
┌─────────────────────┐       ┌──────────────────┐
│ USD triangulation   │──Yes──│ Return           │
│ possible & fresh?   │       │ toUSD/fromUSD    │
└─────┬───────────────┘       └──────────────────┘
      │No
      ▼
┌─────────────────────┐       ┌──────────────────┐
│ Multi-hop via EUR,  │──Yes──│ Return combined  │
│ GBP, JPY possible?  │       │ rate (step1*step2│
└─────┬───────────────┘       └──────────────────┘
      │No
      ▼
┌─────────────────────┐
│ Throw               │
│ RateNotFoundException│
└─────────────────────┘
```

### Implementation Details

#### Currency Conversion Logic

```java
public ConversionResult convert(String fromCurrency, String toCurrency, double amount)
        throws CurrencyConverterException {

    validateCurrencyPair(fromCurrency, toCurrency);
    validateAmount(amount);

    fromCurrency = fromCurrency.toUpperCase();
    toCurrency = toCurrency.toUpperCase();

    totalConversions++;

    // Same currency conversion
    if (fromCurrency.equals(toCurrency)) {
        return createConversionResult(fromCurrency, toCurrency, amount, amount, 1.0, "same");
    }

    // Check conversion cache first
    if (cacheConversions) {
        String cacheKey = generateCacheKey(fromCurrency, toCurrency, amount);
        ConversionResult cachedResult = conversionCache.get(cacheKey);
        if (cachedResult != null && !isResultExpired(cachedResult)) {
            cacheHits++;
            return cachedResult;
        }
    }

    cacheMisses++;

    // Get exchange rate and perform conversion
    ExchangeRateResult rateResult = getExchangeRate(fromCurrency, toCurrency);
    double convertedAmount = amount * rateResult.getRate();

    ConversionResult result = createConversionResult(
            fromCurrency, toCurrency, amount, convertedAmount,
            rateResult.getRate(), rateResult.getPath()
    );

    // Cache the result
    if (cacheConversions) {
        String cacheKey = generateCacheKey(fromCurrency, toCurrency, amount);
        conversionCache.put(cacheKey, result);
    }

    return result;
}
```

#### Exchange Rate Resolution

```java
public ExchangeRateResult getExchangeRate(String fromCurrency, String toCurrency)
        throws CurrencyConverterException {

    validateCurrencyPair(fromCurrency, toCurrency);

    fromCurrency = fromCurrency.toUpperCase();
    toCurrency = toCurrency.toUpperCase();

    if (fromCurrency.equals(toCurrency)) {
        return new ExchangeRateResult(1.0, "same");
    }

    // Try direct rate
    String directKey = generateRateKey(fromCurrency, toCurrency);
    ExchangeRate directRate = rateCache.get(directKey);
    if (directRate != null && !directRate.isExpired(rateExpirationMinutes)) {
        return new ExchangeRateResult(directRate.getRate(), "direct");
    }

    // Try inverse rate
    String inverseKey = generateRateKey(toCurrency, fromCurrency);
    ExchangeRate inverseRate = rateCache.get(inverseKey);
    if (inverseRate != null && !inverseRate.isExpired(rateExpirationMinutes)) {
        return new ExchangeRateResult(1.0 / inverseRate.getRate(), "inverse");
    }

    // Try triangular conversion through USD
    if (!fromCurrency.equals("USD") && !toCurrency.equals("USD")) {
        ExchangeRate fromToUsd = getDirectRate("USD", fromCurrency);
        ExchangeRate toToUsd = getDirectRate("USD", toCurrency);

        if (fromToUsd != null && toToUsd != null &&
                !fromToUsd.isExpired(rateExpirationMinutes) &&
                !toToUsd.isExpired(rateExpirationMinutes)) {

            double rate = toToUsd.getRate() / fromToUsd.getRate();
            return new ExchangeRateResult(rate, String.format("triangular_via_USD(%s->USD->%s)", fromCurrency, toCurrency));
        }
    }

    // Try multi-hop conversion through other major currencies
    String[] intermediateCurrencies = {"EUR", "GBP", "JPY"};
    for (String intermediate : intermediateCurrencies) {
        if (!intermediate.equals(fromCurrency) && !intermediate.equals(toCurrency)) {
            try {
                ExchangeRateResult step1 = getDirectExchangeRate(fromCurrency, intermediate);
                ExchangeRateResult step2 = getDirectExchangeRate(intermediate, toCurrency);

                if (step1 != null && step2 != null) {
                    double combinedRate = step1.getRate() * step2.getRate();
                    String path = String.format("multi_hop_via_%s(%s->%s->%s)",
                            intermediate, fromCurrency, intermediate, toCurrency);
                    return new ExchangeRateResult(combinedRate, path);
                }
            } catch (Exception e) {
                // Continue to next intermediate currency
            }
        }
    }

    // No rate found
    throw new RateNotFoundException(fromCurrency, toCurrency);
}
```

#### Triangular Conversion Logic

For currency pairs without direct rates, the system uses USD as an intermediary:

```
EUR → JPY conversion without direct EUR/JPY rate:
1. Get EUR to USD rate: 1.10
2. Get JPY to USD rate: 0.0075
3. Calculate: JPY/USD ÷ EUR/USD = 0.0075 ÷ 1.10 = 0.0068
4. Result: 1 EUR = 147.06 JPY (1 ÷ 0.0068)
```

## Type System

### Core Classes

```java
// Exchange rate with metadata
public class ExchangeRate {
    private final String fromCurrency;
    private final String toCurrency;
    private final double rate;
    private final LocalDateTime timestamp;

    public ExchangeRate(String fromCurrency, String toCurrency, double rate, LocalDateTime timestamp) {
        if (fromCurrency == null || fromCurrency.trim().isEmpty()) {
            throw new IllegalArgumentException("From currency cannot be null or empty");
        }
        if (toCurrency == null || toCurrency.trim().isEmpty()) {
            throw new IllegalArgumentException("To currency cannot be null or empty");
        }
        if (rate <= 0) {
            throw new IllegalArgumentException("Exchange rate must be positive");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("Timestamp cannot be null");
        }

        this.fromCurrency = fromCurrency.toUpperCase();
        this.toCurrency = toCurrency.toUpperCase();
        this.rate = rate;
        this.timestamp = timestamp;
    }

    // Getters and utility methods
    public String getFromCurrency() { return fromCurrency; }
    public String getToCurrency() { return toCurrency; }
    public double getRate() { return rate; }
    public LocalDateTime getTimestamp() { return timestamp; }

    public String getKey() { return fromCurrency + "_" + toCurrency; }
    public boolean isExpired(long maxAgeMinutes) {
        return timestamp.plusMinutes(maxAgeMinutes).isBefore(LocalDateTime.now());
    }
}

// Conversion result with full context
public class ConversionResult {
    private final String fromCurrency;
    private final String toCurrency;
    private final double originalAmount;
    private final double convertedAmount;
    private final double exchangeRate;
    private final LocalDateTime timestamp;
    private final String conversionPath;

    public ConversionResult(String fromCurrency, String toCurrency, double originalAmount,
                            double convertedAmount, double exchangeRate, LocalDateTime timestamp,
                            String conversionPath) {
        this.fromCurrency = fromCurrency.toUpperCase();
        this.toCurrency = toCurrency.toUpperCase();
        this.originalAmount = originalAmount;
        this.convertedAmount = Math.round(convertedAmount * 100.0) / 100.0;
        this.exchangeRate = exchangeRate;
        this.timestamp = timestamp;
        this.conversionPath = conversionPath != null ? conversionPath : "direct";
    }

    // Getters and utility methods
    public String getFromCurrency() { return fromCurrency; }
    public String getToCurrency() { return toCurrency; }
    public double getOriginalAmount() { return originalAmount; }
    public double getConvertedAmount() { return convertedAmount; }
    public double getExchangeRate() { return exchangeRate; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getConversionPath() { return conversionPath; }

    public boolean isDirect() { return "direct".equals(conversionPath); }
    public String getFormattedResult() {
        return String.format("%.2f %s = %.2f %s (rate: %.6f)",
                originalAmount, fromCurrency, convertedAmount, toCurrency, exchangeRate);
    }
}
```

### Exception Hierarchy

```java
// Base exception class for currency converter operations
public class CurrencyConverterException extends Exception {
    public CurrencyConverterException(String message) {
        super(message);
    }

    public CurrencyConverterException(String message, Throwable cause) {
        super(message, cause);
    }
}

// Exception thrown when an invalid currency pair is used
class InvalidCurrencyPairException extends CurrencyConverterException {
    private final String fromCurrency;
    private final String toCurrency;

    public InvalidCurrencyPairException(String fromCurrency, String toCurrency) {
        super(String.format("Invalid currency pair: %s -> %s", fromCurrency, toCurrency));
        this.fromCurrency = fromCurrency;
        this.toCurrency = toCurrency;
    }

    public String getFromCurrency() { return fromCurrency; }
    public String getToCurrency() { return toCurrency; }
}

// Exception thrown when an exchange rate is not found
class RateNotFoundException extends CurrencyConverterException {
    private final String fromCurrency;
    private final String toCurrency;

    public RateNotFoundException(String fromCurrency, String toCurrency) {
        super(String.format("Exchange rate not found for %s -> %s", fromCurrency, toCurrency));
        this.fromCurrency = fromCurrency;
        this.toCurrency = toCurrency;
    }

    public String getFromCurrency() { return fromCurrency; }
    public String getToCurrency() { return toCurrency; }
}

// Exception thrown when currency conversion fails
class ConversionFailedException extends CurrencyConverterException {
    private final String fromCurrency;
    private final String toCurrency;
    private final double amount;

    public ConversionFailedException(String fromCurrency, String toCurrency, double amount, String reason) {
        super(String.format("Failed to convert %.2f %s to %s: %s", amount, fromCurrency, toCurrency, reason));
        this.fromCurrency = fromCurrency;
        this.toCurrency = toCurrency;
        this.amount = amount;
    }

    public String getFromCurrency() { return fromCurrency; }
    public String getToCurrency() { return toCurrency; }
    public double getAmount() { return amount; }
}
```

## Service Integration

### CurrencyConverterService Architecture

```java
import java.util.concurrent.locks.ReentrantLock;

public class CurrencyConverterService {
    private final CurrencyConverter converter;
    private final ExchangeRateProvider rateProvider;
    private final ScheduledExecutorService scheduler;
    private final boolean autoUpdate;
    private final long updateIntervalMinutes;

    private volatile boolean running = false;
    private ScheduledFuture<?> updateTask;
    private final ReentrantLock serviceLock = new ReentrantLock();

    // Service statistics
    private volatile long totalServiceConversions = 0;
    private volatile long rateUpdateCount = 0;
    private volatile long failedUpdateCount = 0;
    private volatile LocalDateTime lastUpdateTime;

    public CurrencyConverterService(ExchangeRateProvider rateProvider, boolean autoUpdate, long updateIntervalMinutes) {
        this.converter = new CurrencyConverter();
        this.rateProvider = rateProvider;
        this.autoUpdate = autoUpdate;
        this.updateIntervalMinutes = updateIntervalMinutes;
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    // Start the service with automatic rate updates
    public void start() throws RateServiceUnavailableException {
        try {
            // Try to acquire lock with timeout to avoid indefinite blocking
            if (!serviceLock.tryLock(10, TimeUnit.SECONDS)) {
                throw new RateServiceUnavailableException("Service start",
                    "Unable to acquire service lock - another thread may be starting/stopping the service");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RateServiceUnavailableException("Service start", "Interrupted while waiting for service lock");
        }

        try {
            if (running) {
                return;
            }

            // Initial rate fetch
            try {
                fetchAndUpdateRates();
            } catch (Exception e) {
                throw new RateServiceUnavailableException("Initial rate fetch", e);
            }

            // Start automatic updates if enabled
            if (autoUpdate) {
                startAutomaticUpdates();
            }

            running = true;
            System.out.println("CurrencyConverterService started with " +
                    converter.getCachedRates().size() + " exchange rates");
        } finally {
            serviceLock.unlock();
        }
    }

    // Periodic rate updates with scheduling
    private void startAutomaticUpdates() {
        updateTask = scheduler.scheduleWithFixedDelay(
                this::performScheduledRateUpdate,
                updateIntervalMinutes,
                updateIntervalMinutes,
                TimeUnit.MINUTES
        );
    }

    // Rate fetching with comprehensive error handling
    private void fetchAndUpdateRates() throws RateServiceUnavailableException {
        try {
            List<ExchangeRate> rates = rateProvider.fetchLatestRates();
            converter.updateRates(rates);

            rateUpdateCount++;
            lastUpdateTime = LocalDateTime.now();

            System.out.println("Updated " + rates.size() + " exchange rates at " + lastUpdateTime);

        } catch (Exception e) {
            failedUpdateCount++;
            throw new RateServiceUnavailableException(
                    rateProvider.getClass().getSimpleName(), e);
        }
    }

    // Convert currency with service-level features
    public ConversionResult convert(String fromCurrency, String toCurrency, double amount)
            throws CurrencyConverterException {

        if (!running) {
            throw new ConversionFailedException(fromCurrency, toCurrency, amount,
                    "Service is not running");
        }

        totalServiceConversions++;
        return converter.convert(fromCurrency, toCurrency, amount);
    }

    // Batch conversion support
    public List<ConversionResult> convertBatch(List<ConversionRequest> requests)
            throws CurrencyConverterException {

        if (!running) {
            throw new ConversionFailedException("", "", 0,
                    "Service is not running");
        }

        return requests.parallelStream()
                .map(request -> {
                    try {
                        return convert(request.getFromCurrency(),
                                request.getToCurrency(),
                                request.getAmount());
                    } catch (CurrencyConverterException e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
    }
}

// Interface for external exchange rate providers
interface ExchangeRateProvider {
    List<ExchangeRate> fetchLatestRates() throws Exception;
    String getProviderName();
}
```

### Concurrent Control Strategy

The service uses **ReentrantLock with tryLock()** instead of `synchronized` for better control over concurrent access:

```java
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;

public class CurrencyConverterService {
    private final ReentrantLock serviceLock = new ReentrantLock();

    // Benefits of ReentrantLock with tryLock() over synchronized:
    // 1. Explicit lock/unlock with try-finally blocks
    // 2. Timeout control prevents indefinite blocking
    // 3. Interruption handling for responsive shutdown
    // 4. More readable and maintainable code
    // 5. Better performance characteristics under contention
    // 6. Fail-fast behavior when lock cannot be acquired

    public void responsiveOperation() throws ServiceException {
        try {
            // Try to acquire lock with timeout
            if (!serviceLock.tryLock(10, TimeUnit.SECONDS)) {
                throw new ServiceException("Unable to acquire lock - operation timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Interrupted while waiting for lock");
        }

        try {
            // Critical section code here
            // Exception-safe with guaranteed unlock
        } finally {
            serviceLock.unlock(); // Always executed
        }
    }

    // Different timeout strategies for different operations
    public void quickOperation() throws ServiceException {
        // Short timeout for fast operations
        if (!serviceLock.tryLock(100, TimeUnit.MILLISECONDS)) {
            throw new ServiceException("Quick operation timeout");
        }
        try {
            // Fast critical section
        } finally {
            serviceLock.unlock();
        }
    }

    public void emergencyShutdown() {
        // Non-blocking attempt for emergency scenarios
        if (serviceLock.tryLock()) {
            try {
                // Clean shutdown if possible
                performCleanShutdown();
            } finally {
                serviceLock.unlock();
            }
        } else {
            // Force shutdown if lock cannot be acquired immediately
            performForceShutdown();
        }
    }
}
```

## Usage Examples

### Basic Currency Conversion

```java
import pair.currencyconverter.*;

public class BasicConversionExample {
    public static void main(String[] args) throws CurrencyConverterException {
        CurrencyConverter converter = new CurrencyConverter();

        // Add exchange rates
        converter.addRate("USD", "EUR", 0.85);
        converter.addRate("USD", "GBP", 0.73);
        converter.addRate("USD", "JPY", 110.0);

        // Direct conversion
        ConversionResult result = converter.convert("USD", "EUR", 100);
        System.out.println(result.getFormattedResult());

        // Output: 100.00 USD = 85.00 EUR (rate: 0.850000)

        // Access individual fields
        System.out.printf("From: %s%n", result.getFromCurrency());      // USD
        System.out.printf("To: %s%n", result.getToCurrency());          // EUR
        System.out.printf("Original: %.2f%n", result.getOriginalAmount()); // 100.00
        System.out.printf("Converted: %.2f%n", result.getConvertedAmount()); // 85.00
        System.out.printf("Rate: %.6f%n", result.getExchangeRate());    // 0.850000
        System.out.printf("Timestamp: %s%n", result.getTimestamp());    // 2024-01-15T10:30:00
        System.out.printf("Path: %s%n", result.getConversionPath());    // direct
    }
}
```

### Inverse Rate Calculation

```java
public class InverseRateExample {
    public static void main(String[] args) throws CurrencyConverterException {
        CurrencyConverter converter = new CurrencyConverter();

        // Only have USD -> EUR rate (0.85)
        converter.addRate("USD", "EUR", 0.85);

        // Convert EUR -> USD (uses inverse calculation)
        ConversionResult reverseResult = converter.convert("EUR", "USD", 100);
        System.out.printf("Exchange rate: %.3f (1 / 0.85)%n", reverseResult.getExchangeRate()); // 1.176
        System.out.printf("Converted amount: %.2f%n", reverseResult.getConvertedAmount()); // 117.65
        System.out.println("Path: " + reverseResult.getConversionPath()); // inverse
    }
}
```

### Triangular Conversion via USD

```java
public class TriangularConversionExample {
    public static void main(String[] args) throws CurrencyConverterException {
        CurrencyConverter converter = new CurrencyConverter();

        // Add USD-based rates
        converter.addRate("USD", "EUR", 0.85);
        converter.addRate("USD", "JPY", 110.0);

        // Convert EUR -> JPY (no direct rate)
        // Uses triangular conversion: EUR -> USD -> JPY
        ConversionResult triangularResult = converter.convert("EUR", "JPY", 100);
        System.out.printf("Exchange rate: %.2f (110 / 0.85)%n", triangularResult.getExchangeRate()); // 129.41
        System.out.printf("Converted amount: %.0f%n", triangularResult.getConvertedAmount()); // 12,941
        System.out.println("Detailed result: " + triangularResult.getDetailedResult());
    }
}
```

### Bulk Rate Updates

```java
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

public class BulkRateUpdateExample {
    public static void main(String[] args) throws CurrencyConverterException {
        CurrencyConverter converter = new CurrencyConverter();

        // Create list of exchange rates
        List<ExchangeRate> exchangeRates = Arrays.asList(
            new ExchangeRate("USD", "EUR", 0.85, LocalDateTime.now()),
            new ExchangeRate("USD", "GBP", 0.73, LocalDateTime.now()),
            new ExchangeRate("USD", "JPY", 110.0, LocalDateTime.now()),
            new ExchangeRate("EUR", "GBP", 0.86, LocalDateTime.now())
        );

        converter.updateRates(exchangeRates);

        // All conversion paths are now available
        ConversionResult[] results = {
            converter.convert("USD", "EUR", 1000),    // Direct
            converter.convert("EUR", "USD", 1000),    // Inverse
            converter.convert("GBP", "JPY", 1000),    // Triangular
            converter.convert("EUR", "GBP", 1000)     // Direct
        };

        for (ConversionResult result : results) {
            System.out.println(result.getDetailedResult());
        }

        // Display statistics
        System.out.println("Converter stats: " + converter.getStats());
    }
}
```

### Service Layer Integration

```java
import java.util.concurrent.TimeUnit;

public class ServiceLayerExample {
    public static void main(String[] args) throws Exception {
        // Create service with mock provider
        MockExchangeRateProvider provider = new MockExchangeRateProvider();
        CurrencyConverterService service = new CurrencyConverterService(provider, true, 60);

        // Start automatic rate updates
        service.start();

        try {
            // Perform conversions with always up-to-date rates
            ConversionResult result = convertWithLiveRates(service, "USD", "EUR", 1000);
            if (result != null) {
                System.out.println("Live conversion: " + result.getFormattedResult());
            }

            // Get service statistics
            CurrencyConverterService.ServiceStats stats = service.getServiceStats();
            System.out.println("Service stats: " + stats);

        } finally {
            service.stop();
        }
    }

    private static ConversionResult convertWithLiveRates(CurrencyConverterService service,
                                                       String from, String to, double amount) {
        try {
            return service.convert(from, to, amount);
        } catch (RateNotFoundException e) {
            System.out.printf("No rate available for %s -> %s%n", from, to);
            return null;
        } catch (CurrencyConverterException e) {
            System.err.println("Conversion failed: " + e.getMessage());
            return null;
        }
    }
}
```

### Portfolio Value Calculation

```java
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PortfolioConverter {
    private final CurrencyConverter converter;

    public PortfolioConverter(CurrencyConverter converter) {
        this.converter = converter;
    }

    public static class Holding {
        private final String currency;
        private final double amount;

        public Holding(String currency, double amount) {
            this.currency = currency;
            this.amount = amount;
        }

        public String getCurrency() { return currency; }
        public double getAmount() { return amount; }
    }

    public static class PortfolioResult {
        private final double totalValue;
        private final List<ConversionResult> breakdown;

        public PortfolioResult(double totalValue, List<ConversionResult> breakdown) {
            this.totalValue = totalValue;
            this.breakdown = breakdown;
        }

        public double getTotalValue() { return totalValue; }
        public List<ConversionResult> getBreakdown() { return breakdown; }
    }

    public PortfolioResult calculatePortfolioValue(List<Holding> holdings, String targetCurrency)
            throws CurrencyConverterException {
        List<ConversionResult> breakdown = new ArrayList<>();
        double totalValue = 0;

        for (Holding holding : holdings) {
            ConversionResult result = converter.convert(
                    holding.getCurrency(),
                    targetCurrency,
                    holding.getAmount()
            );
            breakdown.add(result);
            totalValue += result.getConvertedAmount();
        }

        return new PortfolioResult(totalValue, breakdown);
    }

    // Usage example
    public static void main(String[] args) throws CurrencyConverterException {
        CurrencyConverter converter = new CurrencyConverter();

        // Add rates
        converter.addRate("USD", "EUR", 0.85);
        converter.addRate("USD", "GBP", 0.73);
        converter.addRate("USD", "JPY", 110.0);

        PortfolioConverter portfolio = new PortfolioConverter(converter);
        List<Holding> holdings = Arrays.asList(
            new Holding("USD", 10000),
            new Holding("EUR", 5000),
            new Holding("GBP", 3000),
            new Holding("JPY", 500000)
        );

        PortfolioResult portfolioValue = portfolio.calculatePortfolioValue(holdings, "USD");
        System.out.printf("Total portfolio value: $%.2f%n", portfolioValue.getTotalValue());

        // Display breakdown
        System.out.println("Portfolio breakdown:");
        for (ConversionResult result : portfolioValue.getBreakdown()) {
            System.out.println("  " + result.getFormattedResult());
        }
    }
}
```

## Caching Strategy

### Rate Caching

```java
// Thread-safe rate cache with concurrent access
private final Map<String, ExchangeRate> rateCache = new ConcurrentHashMap<>();

// Update multiple exchange rates in batch
public void updateRates(List<ExchangeRate> rates) {
    if (rates == null || rates.isEmpty()) {
        return;
    }

    for (ExchangeRate rate : rates) {
        try {
            validateCurrencyPair(rate.getFromCurrency(), rate.getToCurrency());
            rateCache.put(rate.getKey(), rate);
        } catch (InvalidCurrencyPairException e) {
            System.err.println("Skipping invalid rate: " + e.getMessage());
        }
    }
}

// Clear expired rates from cache
public void clearExpiredRates() {
    rateCache.entrySet().removeIf(entry ->
            entry.getValue().isExpired(rateExpirationMinutes));
}

// Get all cached rates
public List<ExchangeRate> getCachedRates() {
    return new ArrayList<>(rateCache.values());
}
```

### Conversion Result Caching

```java
// Conversion result cache for performance optimization
private final Map<String, ConversionResult> conversionCache = new ConcurrentHashMap<>();

// Cache conversion result if caching is enabled
private void cacheConversionResult(String cacheKey, ConversionResult result) {
    if (cacheConversions) {
        conversionCache.put(cacheKey, result);
    }
}

// Check if cached result is still valid
private boolean isResultExpired(ConversionResult result) {
    return result.getTimestamp().plusMinutes(rateExpirationMinutes).isBefore(LocalDateTime.now());
}

// Clear conversion cache
public void clearConversionCache() {
    conversionCache.clear();
}

// Get conversion statistics including cache performance
public ConversionStats getStats() {
    return new ConversionStats(
            totalConversions,
            cacheHits,
            cacheMisses,
            rateCache.size(),
            conversionCache.size(),
            supportedCurrencies.size()
    );
}

// Helper method to generate cache keys
private String generateCacheKey(String fromCurrency, String toCurrency, double amount) {
    return String.format("%s_%s_%.2f", fromCurrency, toCurrency, amount);
}
```

## Error Handling Strategy

### Validation and Error Recovery

```java
// Comprehensive error handling with specific exception types
public ConversionResult convert(String fromCurrency, String toCurrency, double amount)
        throws CurrencyConverterException {
    try {
        validateCurrencyPair(fromCurrency, toCurrency);
        validateAmount(amount);

        // Conversion logic...
        return performConversion(fromCurrency, toCurrency, amount);

    } catch (InvalidCurrencyPairException e) {
        // Log and suggest alternatives
        System.err.printf("Invalid pair %s-%s. Supported currencies: %s%n",
                fromCurrency, toCurrency, getSupportedCurrencies());
        throw e;
    } catch (RateNotFoundException e) {
        // Try alternative conversion paths if available
        try {
            return tryAlternativeConversion(fromCurrency, toCurrency, amount);
        } catch (CurrencyConverterException alternativeError) {
            // If alternative also fails, throw original error
            throw e;
        }
    } catch (ConversionFailedException e) {
        System.err.println("Conversion failed: " + e.getMessage());
        throw e;
    }
}

// Validation methods
private void validateCurrencyPair(String fromCurrency, String toCurrency) throws InvalidCurrencyPairException {
    if (fromCurrency == null || fromCurrency.trim().isEmpty()) {
        throw new InvalidCurrencyPairException(fromCurrency, toCurrency);
    }
    if (toCurrency == null || toCurrency.trim().isEmpty()) {
        throw new InvalidCurrencyPairException(fromCurrency, toCurrency);
    }

    String fromUpper = fromCurrency.toUpperCase();
    String toUpper = toCurrency.toUpperCase();

    if (!supportedCurrencies.contains(fromUpper)) {
        throw new InvalidCurrencyPairException(fromCurrency, toCurrency);
    }
    if (!supportedCurrencies.contains(toUpper)) {
        throw new InvalidCurrencyPairException(fromCurrency, toCurrency);
    }
}

private void validateAmount(double amount) throws ConversionFailedException {
    if (amount < 0) {
        throw new ConversionFailedException("", "", amount, "Amount cannot be negative");
    }
    if (Double.isNaN(amount) || Double.isInfinite(amount)) {
        throw new ConversionFailedException("", "", amount, "Amount must be a valid number");
    }
}

// Alternative conversion path finder
private ConversionResult tryAlternativeConversion(String fromCurrency, String toCurrency, double amount)
        throws CurrencyConverterException {
    // Attempt conversion through multiple intermediate currencies
    String[] intermediates = {"USD", "EUR", "GBP", "JPY"};

    for (String intermediate : intermediates) {
        if (!intermediate.equals(fromCurrency) && !intermediate.equals(toCurrency)) {
            try {
                // Try two-step conversion
                ExchangeRateResult step1 = getDirectExchangeRate(fromCurrency, intermediate);
                ExchangeRateResult step2 = getDirectExchangeRate(intermediate, toCurrency);

                if (step1 != null && step2 != null) {
                    double combinedRate = step1.getRate() * step2.getRate();
                    double convertedAmount = amount * combinedRate;
                    String path = String.format("alternative_via_%s(%s->%s->%s)",
                            intermediate, fromCurrency, intermediate, toCurrency);

                    return createConversionResult(fromCurrency, toCurrency, amount,
                            convertedAmount, combinedRate, path);
                }
            } catch (Exception e) {
                // Continue to next intermediate currency
                continue;
            }
        }
    }

    throw new RateNotFoundException(fromCurrency, toCurrency);
}

// Example error handling in service layer
public class ErrorHandlingExample {
    public static void main(String[] args) {
        CurrencyConverter converter = new CurrencyConverter();

        try {
            converter.addRate("USD", "EUR", 0.85);
        } catch (InvalidCurrencyPairException e) {
            System.err.println("Failed to add rate: " + e.getMessage());
            return;
        }

        // Test various error scenarios
        testErrorScenario("Invalid currency pair", () -> {
            try {
                converter.convert("USD", "XYZ", 100.0);
            } catch (CurrencyConverterException e) {
                throw new RuntimeException(e);
            }
        });

        testErrorScenario("Rate not found", () -> {
            try {
                converter.convert("GBP", "JPY", 100.0);
            } catch (CurrencyConverterException e) {
                throw new RuntimeException(e);
            }
        });

        testErrorScenario("Negative amount", () -> {
            try {
                converter.convert("USD", "EUR", -100.0);
            } catch (CurrencyConverterException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void testErrorScenario(String scenarioName, Runnable test) {
        try {
            test.run();
            System.out.println("  " + scenarioName + ": ERROR - Expected exception but none was thrown");
        } catch (Exception e) {
            System.out.println("  " + scenarioName + ": OK - " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
```

## Testing Strategy

### Unit Tests Example

```java
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

public class CurrencyConverterTest {
    private CurrencyConverter converter;

    @BeforeEach
    void setUp() throws InvalidCurrencyPairException {
        converter = new CurrencyConverter();
        converter.addRate("USD", "EUR", 0.85);
        converter.addRate("USD", "GBP", 0.73);
    }

    @Test
    @DisplayName("Should perform direct conversion")
    void testDirectConversion() throws CurrencyConverterException {
        ConversionResult result = converter.convert("USD", "EUR", 100);

        assertEquals("USD", result.getFromCurrency());
        assertEquals("EUR", result.getToCurrency());
        assertEquals(100.0, result.getOriginalAmount(), 0.001);
        assertEquals(85.0, result.getConvertedAmount(), 0.001);
        assertEquals(0.85, result.getExchangeRate(), 0.001);
        assertEquals("direct", result.getConversionPath());
        assertTrue(result.isDirect());
    }

    @Test
    @DisplayName("Should perform inverse conversion")
    void testInverseConversion() throws CurrencyConverterException {
        ConversionResult result = converter.convert("EUR", "USD", 85);

        assertEquals(1.176, result.getExchangeRate(), 0.001); // 1 / 0.85
        assertEquals(100.0, result.getConvertedAmount(), 0.01);
        assertEquals("inverse", result.getConversionPath());
        assertFalse(result.isDirect());
    }

    @Test
    @DisplayName("Should handle triangular conversion via USD")
    void testTriangularConversion() throws CurrencyConverterException {
        ConversionResult result = converter.convert("EUR", "GBP", 100);

        // EUR -> USD -> GBP: (1/0.85) * 0.73 = 0.8588
        assertEquals(0.8588, result.getExchangeRate(), 0.0001);
        assertEquals(85.88, result.getConvertedAmount(), 0.01);
        assertTrue(result.getConversionPath().contains("triangular"));
    }

    @Test
    @DisplayName("Should handle same currency conversion")
    void testSameCurrencyConversion() throws CurrencyConverterException {
        ConversionResult result = converter.convert("USD", "USD", 100);

        assertEquals(100.0, result.getConvertedAmount(), 0.001);
        assertEquals(1.0, result.getExchangeRate(), 0.001);
        assertEquals("same", result.getConversionPath());
    }

    @Test
    @DisplayName("Should throw InvalidCurrencyPairException for unsupported currency")
    void testUnsupportedCurrency() {
        assertThrows(InvalidCurrencyPairException.class, () -> {
            converter.convert("USD", "XYZ", 100);
        });
    }

    @Test
    @DisplayName("Should throw RateNotFoundException when no rate path exists")
    void testNoRatePath() {
        CurrencyConverter isolatedConverter = new CurrencyConverter();

        assertThrows(RateNotFoundException.class, () -> {
            isolatedConverter.convert("EUR", "GBP", 100);
        });
    }

    @Test
    @DisplayName("Should throw ConversionFailedException for negative amount")
    void testNegativeAmount() {
        assertThrows(ConversionFailedException.class, () -> {
            converter.convert("USD", "EUR", -100);
        });
    }

    @Test
    @DisplayName("Should throw ConversionFailedException for NaN amount")
    void testNaNAmount() {
        assertThrows(ConversionFailedException.class, () -> {
            converter.convert("USD", "EUR", Double.NaN);
        });
    }

    @Test
    @DisplayName("Should maintain statistics correctly")
    void testStatistics() throws CurrencyConverterException {
        // Perform several conversions
        converter.convert("USD", "EUR", 100);
        converter.convert("EUR", "USD", 85);
        converter.convert("USD", "GBP", 100);

        CurrencyConverter.ConversionStats stats = converter.getStats();
        assertEquals(3, stats.getTotalConversions());
        assertTrue(stats.getCachedRates() >= 2); // At least USD->EUR and USD->GBP
    }

    @Test
    @DisplayName("Should cache conversion results when enabled")
    void testConversionCaching() throws CurrencyConverterException {
        CurrencyConverter cachingConverter = new CurrencyConverter(60, true);
        cachingConverter.addRate("USD", "EUR", 0.85);

        // First conversion
        ConversionResult result1 = cachingConverter.convert("USD", "EUR", 100);

        // Second conversion with same parameters should hit cache
        ConversionResult result2 = cachingConverter.convert("USD", "EUR", 100);

        CurrencyConverter.ConversionStats stats = cachingConverter.getStats();
        assertTrue(stats.getCacheHits() > 0);
    }

    @Test
    @DisplayName("Should clear expired rates")
    void testRateExpiration() throws InterruptedException, InvalidCurrencyPairException {
        CurrencyConverter shortLivedConverter = new CurrencyConverter(0, false); // 0 minute expiration
        shortLivedConverter.addRate("USD", "EUR", 0.85);

        // Wait a moment for rate to expire
        Thread.sleep(1000);

        // Clear expired rates
        shortLivedConverter.clearExpiredRates();

        // Should now throw RateNotFoundException
        assertThrows(RateNotFoundException.class, () -> {
            shortLivedConverter.convert("USD", "EUR", 100);
        });
    }
}

// Integration test example
public class CurrencyConverterServiceTest {
    private CurrencyConverterService service;
    private MockExchangeRateProvider mockProvider;

    @BeforeEach
    void setUp() {
        mockProvider = new MockExchangeRateProvider();
        service = new CurrencyConverterService(mockProvider, false, 60);
    }

    @Test
    @DisplayName("Should start service and fetch initial rates")
    void testServiceStart() throws Exception {
        service.start();

        assertTrue(service.isRunning());

        CurrencyConverterService.ServiceStats stats = service.getServiceStats();
        assertTrue(stats.getRateUpdateCount() > 0);

        service.stop();
        assertFalse(service.isRunning());
    }

    @Test
    @DisplayName("Should handle service conversion")
    void testServiceConversion() throws Exception {
        service.start();

        try {
            ConversionResult result = service.convert("USD", "EUR", 1000);
            assertNotNull(result);
            assertEquals("USD", result.getFromCurrency());
            assertEquals("EUR", result.getToCurrency());
            assertEquals(1000.0, result.getOriginalAmount(), 0.001);
        } finally {
            service.stop();
        }
    }

    @Test
    @DisplayName("Should handle batch conversions")
    void testBatchConversion() throws Exception {
        service.start();

        try {
            List<CurrencyConverterService.ConversionRequest> requests = Arrays.asList(
                new CurrencyConverterService.ConversionRequest("USD", "EUR", 1000),
                new CurrencyConverterService.ConversionRequest("EUR", "GBP", 850),
                new CurrencyConverterService.ConversionRequest("GBP", "JPY", 620)
            );

            List<ConversionResult> results = service.convertBatch(requests);
            assertEquals(3, results.size());

            for (ConversionResult result : results) {
                assertNotNull(result);
                assertTrue(result.getConvertedAmount() > 0);
            }
        } finally {
            service.stop();
        }
    }
}
```

## Performance Considerations

### Time Complexity

- **convert()**: O(1) for direct rates, O(1) for inverse rates, O(1) for triangular conversion
- **updateRates()**: O(n) where n is the number of rates to update
- **getExchangeRate()**: O(1) average case with hash map lookups

### Space Complexity

- **Rate storage**: O(n) where n is the number of unique currency pairs
- **Conversion cache**: O(m) where m is the number of cached conversions
- **Supported currencies**: O(k) where k is the number of supported currencies

### Optimization Strategies

#### Concurrent Utilities Over Synchronized

```java
// Better concurrent control with explicit locks
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class OptimizedCurrencyConverter {
    // Use ReentrantLock for exclusive operations
    private final ReentrantLock serviceLock = new ReentrantLock();

    // Use ReadWriteLock for frequent reads with occasional writes
    private final ReadWriteLock rateCacheLock = new ReentrantReadWriteLock();

    // Read operations (high frequency) - use tryLock for responsiveness
    public ConversionResult convert(String from, String to, double amount) throws ConversionException {
        try {
            // Short timeout for read operations to maintain responsiveness
            if (!rateCacheLock.readLock().tryLock(50, TimeUnit.MILLISECONDS)) {
                throw new ConversionException("Rate cache temporarily unavailable - try again");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConversionException("Conversion interrupted");
        }

        try {
            // Multiple threads can read concurrently
            return performConversion(from, to, amount);
        } finally {
            rateCacheLock.readLock().unlock();
        }
    }

    // Write operations (less frequent) - longer timeout acceptable
    public void updateRates(List<ExchangeRate> rates) throws RateUpdateException {
        try {
            // Longer timeout for write operations (rate updates are less frequent)
            if (!rateCacheLock.writeLock().tryLock(5, TimeUnit.SECONDS)) {
                throw new RateUpdateException("Unable to update rates - cache is busy");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RateUpdateException("Rate update interrupted");
        }

        try {
            // Exclusive access for rate updates
            batchUpdateRates(rates);
        } finally {
            rateCacheLock.writeLock().unlock();
        }
    }

    // Emergency read with immediate fallback
    public ConversionResult convertWithFallback(String from, String to, double amount) {
        // Try non-blocking read first
        if (rateCacheLock.readLock().tryLock()) {
            try {
                return performConversion(from, to, amount);
            } finally {
                rateCacheLock.readLock().unlock();
            }
        } else {
            // Fallback to cached or default rate if lock not available
            return performFallbackConversion(from, to, amount);
        }
    }
}

// Rate key generation optimization with string interning
private String generateRateKey(String from, String to) {
    // Normalize to uppercase once and use efficient string concatenation
    return (from.toUpperCase() + "_" + to.toUpperCase()).intern();
}

// Batch rate updates for better performance
public void updateRatesBatch(List<ExchangeRate> rates) {
    if (rates == null || rates.isEmpty()) {
        return;
    }

    // Use temporary map to batch all updates
    Map<String, ExchangeRate> updates = new HashMap<>(rates.size());

    for (ExchangeRate rate : rates) {
        try {
            validateCurrencyPair(rate.getFromCurrency(), rate.getToCurrency());
            String key = rate.getKey();
            updates.put(key, rate);
        } catch (InvalidCurrencyPairException e) {
            System.err.println("Skipping invalid rate: " + e.getMessage());
        }
    }

    // Single batch update to minimize synchronization overhead
    rateCache.putAll(updates);
}

// Optimized conversion with minimal object creation
public ConversionResult convertOptimized(String fromCurrency, String toCurrency, double amount)
        throws CurrencyConverterException {

    // Pre-normalize currencies to avoid repeated operations
    final String fromUpper = fromCurrency.toUpperCase();
    final String toUpper = toCurrency.toUpperCase();

    validateCurrencyPair(fromUpper, toUpper);
    validateAmount(amount);

    totalConversions++;

    // Same currency conversion - fast path
    if (fromUpper.equals(toUpper)) {
        return new ConversionResult(fromUpper, toUpper, amount, amount, 1.0,
                LocalDateTime.now(), "same");
    }

    // Try direct rate first - most common case
    final String directKey = fromUpper + "_" + toUpper;
    ExchangeRate directRate = rateCache.get(directKey);
    if (directRate != null && !directRate.isExpired(rateExpirationMinutes)) {
        double convertedAmount = amount * directRate.getRate();
        return createConversionResult(fromUpper, toUpper, amount, convertedAmount,
                directRate.getRate(), "direct");
    }

    // Continue with other resolution strategies...
    return resolveRateAndConvert(fromUpper, toUpper, amount);
}

// Performance monitoring and metrics
public static class PerformanceMetrics {
    private final long startTime;
    private final AtomicLong totalConversions = new AtomicLong(0);
    private final AtomicLong totalTime = new AtomicLong(0);
    private final AtomicLong maxTime = new AtomicLong(0);
    private final AtomicLong minTime = new AtomicLong(Long.MAX_VALUE);

    public PerformanceMetrics() {
        this.startTime = System.nanoTime();
    }

    public void recordConversion(long conversionTimeNanos) {
        totalConversions.incrementAndGet();
        totalTime.addAndGet(conversionTimeNanos);

        // Update max time
        long currentMax = maxTime.get();
        while (conversionTimeNanos > currentMax &&
               !maxTime.compareAndSet(currentMax, conversionTimeNanos)) {
            currentMax = maxTime.get();
        }

        // Update min time
        long currentMin = minTime.get();
        while (conversionTimeNanos < currentMin &&
               !minTime.compareAndSet(currentMin, conversionTimeNanos)) {
            currentMin = minTime.get();
        }
    }

    public double getAverageTimeMillis() {
        long total = totalConversions.get();
        return total > 0 ? (totalTime.get() / (double) total) / 1_000_000.0 : 0.0;
    }

    public double getConversionsPerSecond() {
        long elapsed = System.nanoTime() - startTime;
        return totalConversions.get() / (elapsed / 1_000_000_000.0);
    }

    public long getMaxTimeMillis() {
        return maxTime.get() / 1_000_000;
    }

    public long getMinTimeMillis() {
        long min = minTime.get();
        return min == Long.MAX_VALUE ? 0 : min / 1_000_000;
    }

    @Override
    public String toString() {
        return String.format(
            "PerformanceMetrics{conversions=%d, avgTime=%.3fms, rate=%.0f ops/sec, max=%dms, min=%dms}",
            totalConversions.get(), getAverageTimeMillis(), getConversionsPerSecond(),
            getMaxTimeMillis(), getMinTimeMillis()
        );
    }
}

// Memory-efficient rate storage for high-frequency operations
public class CompactRateStorage {
    // Use primitive collections for better memory efficiency
    private final TObjectDoubleMap<String> rates = new TObjectDoubleHashMap<>();
    private final TObjectLongMap<String> timestamps = new TObjectLongHashMap<>();

    public void putRate(String key, double rate, long timestamp) {
        rates.put(key, rate);
        timestamps.put(key, timestamp);
    }

    public OptionalDouble getRate(String key, long maxAgeMillis) {
        if (!rates.containsKey(key)) {
            return OptionalDouble.empty();
        }

        long timestamp = timestamps.get(key);
        if (System.currentTimeMillis() - timestamp > maxAgeMillis) {
            return OptionalDouble.empty(); // Expired
        }

        return OptionalDouble.of(rates.get(key));
    }

    public int size() {
        return rates.size();
    }

    public void clear() {
        rates.clear();
        timestamps.clear();
    }
}

// Benchmark example
public class PerformanceBenchmark {
    public static void main(String[] args) throws Exception {
        // Setup
        CurrencyConverter converter = new CurrencyConverter();
        MockExchangeRateProvider provider = new MockExchangeRateProvider();
        List<ExchangeRate> rates = provider.fetchLatestRates();
        converter.updateRates(rates);

        PerformanceMetrics metrics = new PerformanceMetrics();

        // Warm-up phase
        System.out.println("Warming up...");
        for (int i = 0; i < 10000; i++) {
            converter.convert("USD", "EUR", 100.0 + i % 100);
        }

        // Benchmark phase
        System.out.println("Running benchmark...");
        int iterations = 100000;
        long startTime = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            long conversionStart = System.nanoTime();
            converter.convert("USD", "EUR", 100.0 + i % 1000);
            long conversionTime = System.nanoTime() - conversionStart;
            metrics.recordConversion(conversionTime);
        }

        long totalTime = System.nanoTime() - startTime;
        double totalSeconds = totalTime / 1_000_000_000.0;
        double operationsPerSecond = iterations / totalSeconds;

        // Results
        System.out.printf("Completed %d conversions in %.2f seconds%n", iterations, totalSeconds);
        System.out.printf("Performance: %.0f operations per second%n", operationsPerSecond);
        System.out.println("Detailed metrics: " + metrics);

        // Memory usage
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        System.out.printf("Memory usage: %.2f MB%n", usedMemory / (1024.0 * 1024.0));

        // Cache statistics
        CurrencyConverter.ConversionStats stats = converter.getStats();
        System.out.printf("Cache hit ratio: %.1f%%%n", stats.getCacheHitRatio() * 100);
    }
}
```

This currency converter implementation provides a robust, flexible foundation for handling multi-currency applications
with comprehensive error handling, caching strategies, and support for complex conversion scenarios.
