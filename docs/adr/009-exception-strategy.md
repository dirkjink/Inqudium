# ADR-009: Exception strategy

**Status:** Accepted  
**Date:** 2026-03-23  
**Deciders:** Core team

## Context

A resilience library wraps application calls and must decide how to signal failures — both failures from the protected downstream service and failures caused by the resilience elements themselves (circuit breaker open, rate limit exceeded, etc.).

There are three competing concerns:

1. **Transparency.** When a downstream call fails, the application should see the original exception — not a library-specific wrapper. A `ServiceUnavailableException` thrown by a REST client should arrive at the catch-site as a `ServiceUnavailableException`, regardless of how many resilience elements the call passed through.

2. **Distinguishability.** When a resilience element intervenes (rejects a call, exhausts retries), the application must be able to distinguish "the downstream service failed" from "the library blocked the call." These are fundamentally different situations requiring different handling.

3. **Decoupling.** The application should not become deeply coupled to library-specific exception types. If `catch (CallNotPermittedException e)` appears in controllers, service layers, and error handlers throughout the codebase, the resilience library has become an invasive dependency — the opposite of its purpose.

Additionally, exception typing is fragile in practice. Between the throw-site and the catch-site, exceptions are routinely wrapped by frameworks, proxies, and reflection machinery:

```
Origin:         TimeoutException
↓ Future.get()  ExecutionException(cause: TimeoutException)
↓ Spring AOP    UndeclaredThrowableException(cause: ...)
↓ JDK Proxy     InvocationTargetException(cause: ...)
```

At the catch-site, the original type may be buried several layers deep. Developers who write `catch (TimeoutException e)` rarely implement recursive cause-chain traversal — and those who do face edge cases like circular cause references and double-wrapping.

## Decision

### Principle: Inqudium exceptions are interventions, not wrappings

Inqudium throws its own exceptions **only** when the resilience element itself prevents or alters the call. When the downstream call executes and fails, the original exception propagates unchanged.

| Situation | What Inqudium does | What the application sees |
|---|---|---|
| Call succeeds | Returns result | The result |
| Call fails, Circuit Breaker CLOSED | Records failure, propagates | **Original exception** |
| Call fails, Retry has attempts left | Retries, eventually propagates | **Last original exception** |
| Retry exhausted | All attempts failed | **Last original exception**, wrapped in `InqRetryExhaustedException` with attempt count |
| Circuit Breaker OPEN | Call never made | `InqCallNotPermittedException` |
| Rate Limiter denied | Call never made | `InqRequestNotPermittedException` |
| Bulkhead full | Call never made | `InqBulkheadFullException` |
| TimeLimiter fires | Caller stops waiting | See ADR-010 |

### Exception hierarchy: flat and minimal

```
RuntimeException
└── InqException (abstract)
    ├── InqCallNotPermittedException      Circuit breaker rejected the call
    ├── InqRequestNotPermittedException   Rate limiter denied the request
    ├── InqBulkheadFullException          No concurrency permits available
    ├── InqTimeLimitExceededException     Caller's wait time exceeded (see ADR-010)
    └── InqRetryExhaustedException        All retry attempts failed
```

All Inqudium exceptions extend `RuntimeException` (unchecked). This is non-negotiable for the functional decoration API (ADR-002) — `Supplier.get()`, `Runnable.run()`, and `Function.apply()` do not declare checked exceptions.

### Every InqException carries element context

```java
public abstract class InqException extends RuntimeException {
    private final String elementName;
    private final InqElementType elementType;
    // ...
}
```

Subclasses add element-specific context:

- `InqCallNotPermittedException` → current state (OPEN / HALF_OPEN), failure rate
- `InqRequestNotPermittedException` → wait estimate until next permit
- `InqBulkheadFullException` → current concurrent call count, max permitted
- `InqRetryExhaustedException` → number of attempts, last cause (the original exception from the final attempt)

### Cause-chain navigation utility

Because exception wrapping by frameworks is pervasive, Inqudium provides a utility that traverses the cause chain:

```java
InqFailure.find(exception)
    .ifCircuitBreakerOpen(info -> {
        // info.elementName(), info.state(), info.failureRate()
    })
    .ifRateLimited(info -> {
        // info.elementName(), info.waitEstimate()
    })
    .ifBulkheadFull(info -> {
        // info.elementName(), info.concurrentCalls()
    })
    .ifTimeLimitExceeded(info -> {
        // info.elementName(), info.configuredDuration(), info.actualDuration()
    })
    .ifRetryExhausted(info -> {
        // info.elementName(), info.attempts(), info.lastCause()
    })
    .orElseThrow(); // re-throw if no Inqudium intervention found
```

`InqFailure.find()` walks the entire cause chain recursively, handles circular references, and returns the first `InqException` it encounters — regardless of how many layers of `ExecutionException`, `InvocationTargetException`, or `UndeclaredThrowableException` surround it.

This allows the application to catch a broad type (`RuntimeException` or even `Exception`) and inspect whether Inqudium intervened — without coupling the catch-site to Inqudium exception types.

### What Inqudium does NOT do with exceptions

- **No wrapping of original exceptions.** If the downstream throws `ServiceUnavailableException`, the application sees `ServiceUnavailableException` — never `InqException(cause: ServiceUnavailableException)`. The only exception is `InqRetryExhaustedException`, which wraps the last cause because the application needs to know that retries were attempted.
- **No custom exception hierarchy that mirrors standard exceptions.** There is no `InqTimeoutException extends TimeoutException`. This would pollute catch-sites: every `catch (TimeoutException e)` would silently catch Inqudium timeouts too, blurring the line between "the service timed out" and "Inqudium stopped waiting."
- **No checked exceptions.** All Inqudium exceptions are unchecked. Checked exceptions cannot propagate through functional interfaces.

## Consequences

**Positive:**
- Maximum transparency: original exceptions pass through untouched in the common case (call made, call failed).
- Minimal coupling: applications only encounter Inqudium exceptions when the library actively intervenes, and can use the `InqFailure.find()` utility to inspect them without type-level coupling.
- Flat hierarchy: five concrete exception types, each with clear semantics. No abstract intermediate layers, no parallel hierarchies.
- Framework-resilient: `InqFailure.find()` handles the real-world cause-chain wrapping that makes type-based catching unreliable.

**Negative:**
- `InqRetryExhaustedException` wrapping the last cause is a deviation from the "no wrapping" principle. Justified because the application needs to know retries were attempted — the retry count is not available on the original exception.
- `InqFailure.find()` is an opt-in utility. Developers who don't know about it will fall back to `catch (InqException e)` — which works but doesn't solve the cause-chain wrapping problem.

**Neutral:**
- The exception hierarchy lives in `inqudium-core`. All paradigm implementations throw the same exception types.
