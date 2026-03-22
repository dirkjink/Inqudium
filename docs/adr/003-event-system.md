# ADR-003: Event-driven observability

**Status:** Accepted  
**Date:** 2026-03-22  
**Deciders:** Core team

## Context

Resilience elements produce observable state changes: a circuit breaker opening, a retry attempt, a rate limiter denying a request. Consumers need to react to these changes for logging, metrics, alerting, and custom business logic.

Common approaches include:

1. **Direct logging** — elements log their own state changes. Inflexible: the consumer can't control the log level, format, or destination.
2. **Callback interfaces** — element-specific listeners (e.g. `CircuitBreakerListener`). Works but fragments the observability surface.
3. **Unified event system** — all elements emit typed events through a shared publisher. Consumers attach listeners to the publisher.

## Decision

We adopt a **unified event system** based on `InqEventPublisher`. Every element, regardless of paradigm, emits events through this shared mechanism.

### Event hierarchy

```
InqEvent (abstract)
├── CircuitBreakerEvent
│   ├── CircuitBreakerOnSuccessEvent
│   ├── CircuitBreakerOnErrorEvent
│   └── CircuitBreakerOnStateTransitionEvent
├── RetryEvent
│   ├── RetryOnRetryEvent
│   └── RetryOnSuccessEvent
├── RateLimiterEvent
│   └── RateLimiterOnDrainedEvent
├── BulkheadEvent
├── TimeLimiterEvent
└── CacheEvent
```

Every event carries:
- `elementName` — the named instance that emitted it (e.g. "paymentService")
- `timestamp` — when the event occurred
- `elementType` — which element kind (CIRCUIT_BREAKER, RETRY, etc.)

Element-specific subclasses add context: `fromState`/`toState` for circuit breaker transitions, `attemptNumber`/`waitDuration` for retries, etc.

### Event types live in `inqudium-core`

The event classes are defined in core, not in the paradigm modules. This is critical: it means Micrometer bindings, JFR bindings, and custom listeners work identically regardless of whether the event was emitted from an imperative, coroutine, or reactive implementation.

### Publisher contract

```java
public interface InqEventPublisher {
    void publish(InqEvent event);
    void onEvent(InqEventConsumer consumer);
    <E extends InqEvent> void onEvent(Class<E> eventType, Consumer<E> consumer);
}
```

Each element instance owns its own `InqEventPublisher`. Consumers subscribe per-instance:

```java
circuitBreaker.getEventPublisher()
    .onEvent(CircuitBreakerOnStateTransitionEvent.class, event -> { ... });
```

### No internal logging

Elements do **not** log their own state changes. All observability goes through events. This gives consumers full control over how state changes are recorded — whether that's SLF4J logging, Micrometer counters, JFR events, or all three.

### Relationship to JFR, Micrometer, and other observability systems

`InqEventPublisher` and its `InqEvent` type hierarchy are the **canonical event bus** of Inqudium. They live in `inqudium-core` and have no dependency on any external observability system.

External observability modules are **consumers** of this event bus — they translate `InqEvent` instances into their own type systems:

```
InqEventPublisher (this ADR)
       │
       ├── inqudium-micrometer     InqEvent → Counter.increment() / Timer.record()
       ├── inqudium-jfr            InqEvent → jdk.jfr.Event subclass → commit()
       ├── Custom SLF4J listener   InqEvent → log.info(...)
       └── Any user-defined consumer
```

Critically, `InqEvent` does **not** extend `jdk.jfr.Event`, and JFR event classes do **not** extend `InqEvent`. The two type hierarchies are fully independent:

- `InqEvent` hierarchy — lightweight POJOs in `inqudium-core`, zero dependencies, always emitted.
- `jdk.jfr.Event` hierarchy — JFR-annotated classes in `inqudium-jfr`, only instantiated when the JFR module is on the classpath.

This separation is intentional. Binding `InqEvent` to `jdk.jfr.Event` would force JFR's class hierarchy (`begin()`/`end()`/`commit()` lifecycle, `@Name`/`@Label` annotations) onto the core event model — an abstraction leak that couples the internal pub/sub mechanism to a specific observability backend.

The JFR binder (ADR-007) bridges the gap: it subscribes to `InqEventPublisher`, receives `InqEvent` instances, and creates the corresponding `jdk.jfr.Event` — mapping fields one-to-one. The same pattern applies to the Micrometer binder, which maps events to metric increments and timer recordings.

See ADR-007 for the JFR event design and the binder mechanism.

## Consequences

**Positive:**
- Single observability contract for all elements and paradigms.
- Consumers choose their observability backend (Micrometer, JFR, logging, custom) without the library making assumptions.
- Event types are stable API — new listeners can be added without modifying element internals.
- Cross-paradigm consistency: a `CircuitBreakerOnStateTransitionEvent` is identical whether it came from `ReentrantLock`-based or `Mutex`-based state machine.

**Negative:**
- Every paradigm implementation must remember to emit events at the correct points. This is enforced by behavioral contract tests in core (given a mock publisher, verify the correct events are emitted for each scenario).
- Slight overhead from event object creation, though this is negligible compared to the actual resilience logic (network calls, timeouts).

**Neutral:**
- Elements ship with zero logging dependency. The `inqudium-test` module may optionally log events for debugging convenience.
