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

```typescript
public convert(from: string, to: string, amount: number): ConversionResult {
    this.validateCurrencyPair(from, to);

    if (from === to) {
        return this.createConversionResult(from, to, amount, amount, 1);
    }

    const exchangeRate = this.getExchangeRate(from, to);
    const convertedAmount = amount * exchangeRate;

    const result = this.createConversionResult(from, to, amount, convertedAmount, exchangeRate);
    this.cacheConversionResult(result);

    return result;
}
```

#### Exchange Rate Resolution

```typescript
public getExchangeRate(from: string, to: string): number {
    this.validateCurrencyPair(from, to);

    if (from === to) return 1;

    // Try direct rate
    const directKey = this.generateRateKey(from, to);
    if (this.rates[directKey]) {
        return this.rates[directKey].rate;
    }

    // Try inverse rate
    const inverseKey = this.generateRateKey(to, from);
    if (this.rates[inverseKey]) {
        return 1 / this.rates[inverseKey].rate;
    }

    // Try triangular conversion through USD
    if (from !== 'USD' && to !== 'USD') {
        const fromToUsd = this.getDirectRate('USD', from);
        const toToUsd = this.getDirectRate('USD', to);

        if (fromToUsd && toToUsd) {
            return toToUsd / fromToUsd;
        }
    }

    throw new RateNotFoundError(from, to);
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

### Core Types

```typescript
// Exchange rate with metadata
interface ExchangeRate {
    from: string;
    to: string;
    rate: number;
    timestamp: Date;
}

// Conversion result with full context
interface ConversionResult {
    fromCurrency: string;
    toCurrency: string;
    originalAmount: number;
    convertedAmount: number;
    exchangeRate: number;
    timestamp: Date;
}

// Currency pair for validation
interface CurrencyPair {
    from: string;
    to: string;
}
```

### Error Types

```typescript
class InvalidCurrencyPairError extends Error {
    constructor(from: string, to: string) {
        super(`Invalid currency pair: ${from} -> ${to}`);
        this.name = 'InvalidCurrencyPairError';
    }
}

class RateNotFoundError extends Error {
    constructor(from: string, to: string) {
        super(`Exchange rate not found for ${from} -> ${to}`);
        this.name = 'RateNotFoundError';
    }
}
```

## Service Integration

### CurrencyConverterService Architecture

```typescript
class CurrencyConverterService {
    private converter: CurrencyConverter;
    private rateFetcher: ExchangeRateFetcher;

    // Periodic rate updates
    public startRateUpdates(intervalMs: number = 300000): void {
        setInterval(async () => {
            try {
                const latestRates = await this.rateFetcher.fetchLatestRates();
                this.converter.updateRates(latestRates);
            } catch (error) {
                console.error('Failed to update exchange rates:', error);
            }
        }, intervalMs);
    }

    // Rate fetching with error handling
    private async fetchRatesWithRetry(retries: number = 3): Promise<ExchangeRate[]> {
        for (let i = 0; i < retries; i++) {
            try {
                return await this.rateFetcher.fetchLatestRates();
            } catch (error) {
                if (i === retries - 1) throw error;
                await this.delay(1000 * Math.pow(2, i)); // Exponential backoff
            }
        }
        throw new Error('Max retries exceeded');
    }
}
```

## Usage Examples

### Basic Currency Conversion

```typescript
import { CurrencyConverter } from './CurrencyConverter';

const converter = new CurrencyConverter();

// Add exchange rates
converter.addRate('USD', 'EUR', 0.85);
converter.addRate('USD', 'GBP', 0.73);
converter.addRate('USD', 'JPY', 110.0);

// Direct conversion
const result = converter.convert('USD', 'EUR', 100);
console.log(result);
// Output: {
//   fromCurrency: 'USD',
//   toCurrency: 'EUR',
//   originalAmount: 100,
//   convertedAmount: 85,
//   exchangeRate: 0.85,
//   timestamp: 2024-01-15T10:30:00.000Z
// }
```

### Inverse Rate Calculation

```typescript
// Only have USD -> EUR rate (0.85)
converter.addRate('USD', 'EUR', 0.85);

// Convert EUR -> USD (uses inverse calculation)
const reverseResult = converter.convert('EUR', 'USD', 100);
console.log(reverseResult.exchangeRate); // 1.176 (1 / 0.85)
console.log(reverseResult.convertedAmount); // 117.65
```

### Triangular Conversion via USD

```typescript
// Add USD-based rates
converter.addRate('USD', 'EUR', 0.85);
converter.addRate('USD', 'JPY', 110.0);

// Convert EUR -> JPY (no direct rate)
// Uses triangular conversion: EUR -> USD -> JPY
const triangularResult = converter.convert('EUR', 'JPY', 100);
console.log(triangularResult.exchangeRate); // 129.41 (110 / 0.85)
console.log(triangularResult.convertedAmount); // 12,941
```

### Bulk Rate Updates

```typescript
const exchangeRates: ExchangeRate[] = [
    { from: 'USD', to: 'EUR', rate: 0.85, timestamp: new Date() },
    { from: 'USD', to: 'GBP', rate: 0.73, timestamp: new Date() },
    { from: 'USD', to: 'JPY', rate: 110.0, timestamp: new Date() },
    { from: 'EUR', to: 'GBP', rate: 0.86, timestamp: new Date() }
];

converter.updateRates(exchangeRates);

// All conversion paths are now available
const results = [
    converter.convert('USD', 'EUR', 1000),    // Direct
    converter.convert('EUR', 'USD', 1000),    // Inverse
    converter.convert('GBP', 'JPY', 1000),    // Triangular
    converter.convert('EUR', 'GBP', 1000)     // Direct
];
```

### Service Layer Integration

```typescript
import { CurrencyConverterService } from './CurrencyConverterService';

const service = new CurrencyConverterService({
    apiKey: 'your-api-key',
    baseUrl: 'https://api.exchangerate.com',
    updateInterval: 300000 // 5 minutes
});

// Start automatic rate updates
service.startRateUpdates();

// Perform conversions with always up-to-date rates
async function convertWithLiveRates(from: string, to: string, amount: number) {
    try {
        const result = await service.convert(from, to, amount);
        return result;
    } catch (error) {
        if (error instanceof RateNotFoundError) {
            console.log(`No rate available for ${from} -> ${to}`);
            return null;
        }
        throw error;
    }
}
```

### Portfolio Value Calculation

```typescript
class PortfolioConverter {
    private converter: CurrencyConverter;

    constructor(converter: CurrencyConverter) {
        this.converter = converter;
    }

    public calculatePortfolioValue(
        holdings: Array<{ currency: string; amount: number }>,
        targetCurrency: string
    ): { totalValue: number; breakdown: ConversionResult[] } {
        const breakdown: ConversionResult[] = [];
        let totalValue = 0;

        for (const holding of holdings) {
            const result = this.converter.convert(
                holding.currency,
                targetCurrency,
                holding.amount
            );
            breakdown.push(result);
            totalValue += result.convertedAmount;
        }

        return { totalValue, breakdown };
    }
}

// Usage
const portfolio = new PortfolioConverter(converter);
const holdings = [
    { currency: 'USD', amount: 10000 },
    { currency: 'EUR', amount: 5000 },
    { currency: 'GBP', amount: 3000 },
    { currency: 'JPY', amount: 500000 }
];

const portfolioValue = portfolio.calculatePortfolioValue(holdings, 'USD');
console.log(`Total portfolio value: $${portfolioValue.totalValue.toFixed(2)}`);
```

## Caching Strategy

### Rate Caching

```typescript
// Rates are cached with timestamps for freshness validation
private rates: RateCache = {};

public updateRates(rates: ExchangeRate[]): void {
    rates.forEach(rate => {
        this.validateCurrencyPair(rate.from, rate.to);
        const key = this.generateRateKey(rate.from, rate.to);
        this.rates[key] = {
            ...rate,
            timestamp: new Date() // Update timestamp on cache
        };
    });
}
```

### Conversion Result Caching

```typescript
// Cache recent conversion results for repeated queries
private conversionHistory: ConversionCache = {};

private cacheConversionResult(result: ConversionResult): void {
    const key = this.generateRateKey(result.fromCurrency, result.toCurrency);
    this.conversionHistory[key] = result;
}

public getLastConversion(from: string, to: string): ConversionResult | null {
    const key = this.generateRateKey(from, to);
    return this.conversionHistory[key] || null;
}
```

## Error Handling Strategy

### Validation and Error Recovery

```typescript
// Comprehensive error handling with specific exception types
public convert(from: string, to: string, amount: number): ConversionResult {
    try {
        this.validateCurrencyPair(from, to);
        this.validateAmount(amount);

        // Conversion logic...
        return result;

    } catch (error) {
        if (error instanceof InvalidCurrencyPairError) {
            // Log and suggest alternatives
            console.warn(`Invalid pair ${from}-${to}. Supported:`,
                        this.getSupportedCurrencies());
        } else if (error instanceof RateNotFoundError) {
            // Try alternative conversion paths
            return this.tryAlternativeConversion(from, to, amount);
        }
        throw error;
    }
}

private tryAlternativeConversion(from: string, to: string, amount: number): ConversionResult {
    // Attempt conversion through multiple intermediate currencies
    const intermediates = ['USD', 'EUR', 'GBP'];

    for (const intermediate of intermediates) {
        try {
            if (intermediate !== from && intermediate !== to) {
                const step1 = this.convert(from, intermediate, amount);
                const step2 = this.convert(intermediate, to, step1.convertedAmount);

                // Combine the two-step conversion into a single result
                return this.combineConversions(step1, step2, amount);
            }
        } catch (error) {
            continue; // Try next intermediate currency
        }
    }

    throw new RateNotFoundError(from, to);
}
```

## Testing Strategy

### Unit Tests Example

```typescript
describe('CurrencyConverter', () => {
    let converter: CurrencyConverter;

    beforeEach(() => {
        converter = new CurrencyConverter();
        converter.addRate('USD', 'EUR', 0.85);
        converter.addRate('USD', 'GBP', 0.73);
    });

    it('should perform direct conversion', () => {
        const result = converter.convert('USD', 'EUR', 100);

        expect(result.fromCurrency).toBe('USD');
        expect(result.toCurrency).toBe('EUR');
        expect(result.originalAmount).toBe(100);
        expect(result.convertedAmount).toBe(85);
        expect(result.exchangeRate).toBe(0.85);
    });

    it('should perform inverse conversion', () => {
        const result = converter.convert('EUR', 'USD', 85);

        expect(result.exchangeRate).toBeCloseTo(1.176, 3);
        expect(result.convertedAmount).toBeCloseTo(100, 2);
    });

    it('should handle triangular conversion', () => {
        const result = converter.convert('EUR', 'GBP', 100);

        // EUR -> USD -> GBP: (1/0.85) * 0.73 = 0.8588
        expect(result.exchangeRate).toBeCloseTo(0.8588, 4);
    });

    it('should throw error for unsupported currency', () => {
        expect(() => converter.convert('USD', 'XYZ', 100))
            .toThrow(InvalidCurrencyPairError);
    });

    it('should throw error when no rate path exists', () => {
        const isolatedConverter = new CurrencyConverter();
        expect(() => isolatedConverter.convert('EUR', 'GBP', 100))
            .toThrow(RateNotFoundError);
    });
});
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

```typescript
// Rate key generation optimization
private generateRateKey(from: string, to: string): string {
    // Normalize to uppercase once and cache
    return `${from.toUpperCase()}_${to.toUpperCase()}`;
}

// Batch rate updates for better performance
public updateRatesBatch(rates: ExchangeRate[]): void {
    const updates: Record<string, ExchangeRate & { timestamp: Date }> = {};

    rates.forEach(rate => {
        this.validateCurrencyPair(rate.from, rate.to);
        const key = this.generateRateKey(rate.from, rate.to);
        updates[key] = { ...rate, timestamp: new Date() };
    });

    // Single batch update to minimize object property assignments
    Object.assign(this.rates, updates);
}
```

This currency converter implementation provides a robust, flexible foundation for handling multi-currency applications
with comprehensive error handling, caching strategies, and support for complex conversion scenarios.
