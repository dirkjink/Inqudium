# ADR-003: Event-driven observability

**Status:** Accepted  
**Date:** 2026-03-22  
**Last updated:** 2026-03-23  
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
- `callId` — unique identifier for the call that triggered this event (see below)
- `elementName` — the named instance that emitted it (e.g. "paymentService")
- `timestamp` — when the event occurred
- `elementType` — which element kind (CIRCUIT_BREAKER, RETRY, etc.)

Element-specific subclasses add context: `fromState`/`toState` for circuit breaker transitions, `attemptNumber`/`waitDuration` for retries, etc.

### Call identity: the `callId`

Every invocation through an Inqudium element receives a unique `callId` (UUID or similar) that is carried by all events emitted during that call's lifecycle. In a pipeline with multiple elements, all elements share the same `callId`:

```
Call abc-123 → CircuitBreaker (emits event with callId=abc-123)
            → Retry attempt 1 (emits event with callId=abc-123)
            → Retry attempt 2 (emits event with callId=abc-123)
            → TimeLimiter timeout (emits event with callId=abc-123)
            → Orphaned result arrives (emits event with callId=abc-123)
```

This enables end-to-end correlation of a single call across all resilience elements — in JFR recordings, Micrometer traces, structured logs, or custom listeners.

**Generation:** The `callId` is generated once at the outermost element in the pipeline. Inner elements receive it through a `CallContext` that propagates through the decoration chain. If an element is used standalone (not in a pipeline), it generates its own `callId`.

**Propagation per paradigm:**

| Paradigm | Mechanism |
|---|---|
| Imperative | `CallContext` passed as `ThreadLocal` through the decoration chain |
| Kotlin | `CallContext` passed as `CoroutineContext` element |
| Reactor | `CallContext` passed via Reactor `Context` (subscriber context) |
| RxJava 3 | `CallContext` passed via `Single.compose()` / `Flowable.compose()` context propagation |

Each paradigm uses its native context propagation mechanism — no thread-local bridging in reactive code.

**External correlation:** Applications can supply their own `callId` (e.g. a trace ID from OpenTelemetry) via a `CallIdSupplier` in the element configuration. This avoids generating a separate ID when a correlation key already exists:

```java
var config = CircuitBreakerConfig.builder()
    .callIdSupplier(() -> Span.current().getSpanContext().getTraceId())
    .build();
```

If no supplier is configured, a random UUID is generated per call.

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

### Events are observational — not a control mechanism

Events flow in **one direction: out.** They are notifications about something that has already happened. No element uses events from another element to make state decisions. This is a deliberate constraint with two important implications.

#### Cross-element communication happens through the pipeline, not through events

Consider a typical composition:

```
Call → CircuitBreaker → TimeLimiter → external service
       (outer layer)     (inner layer)
```

When the TimeLimiter fires, it throws a `TimeoutException`. This exception propagates upward to the Circuit Breaker through the normal pipeline call stack (ADR-002). The Circuit Breaker records it as `onError(TimeoutException)`, counts it in its sliding window, and opens when the failure rate threshold is exceeded.

The Circuit Breaker **already reacts to timeouts** — not because it subscribes to TimeLimiter events, but because errors propagate naturally through the decoration layers. This is exactly what functional decoration (ADR-002) is designed for.

If the Circuit Breaker were to additionally consume TimeLimiter events through the event bus, two problems emerge:

1. **Double-counting.** The Circuit Breaker sees the timeout twice — once as an exception through the pipeline, once as an event. Deduplication logic would be needed to prevent a single timeout from counting double in the sliding window.
2. **Module coupling.** The Circuit Breaker would need to know `TimeLimiterEvent` as a type. This creates a dependency from `inqudium-circuitbreaker` to `inqudium-timelimiter` — violating the zero-transitive-dependency principle from ADR-001.

The pipeline already provides the semantics that event-based cross-element communication would attempt to replicate — but without the coupling, without the deduplication, and without the indirection overhead on the hot path.

#### Cross-instance coordination is an explicit API, not event subscription

There is a legitimate scenario that the pipeline does not cover: **cross-instance reaction.** For example: "When the Circuit Breaker for Service-A opens, the Circuit Breaker for Service-B should also open because B depends on A." These two breakers protect different calls — there is no shared pipeline.

Even in this case, the solution is not event subscription between elements. A Circuit Breaker subscribing to another Circuit Breaker's events creates hidden coupling: the behavior depends on subscription order, event bus timing, and whether the subscription was set up before the first failure. This is fragile and invisible in code.

The planned approach is an **explicit, declarative API**:

```java
CircuitBreakerGroup.of(breakerA, breakerB)
    .propagateOpen();  // A opens → B opens
```

This makes the dependency relationship visible in application code, testable in isolation (configure the group, force-open A, assert B is open), and free from event-bus timing concerns. The group implementation calls `forceOpen()` directly on the dependent breaker — a command, not a reaction to an event.

After any such state change, events are emitted as usual — but they document the propagation, they don't drive it.

#### The rule

```
Pipeline       → controls what happens within a call chain
Explicit API   → controls what happens across element instances
Events         → document what happened (observability only)
```

### Extensibility: open for consumption, closed for emission

The event system is intentionally open in one direction and closed in another.

#### Open: consuming events

Any external code can register as an event consumer through `InqEventPublisher.onEvent()`. This is a first-class API, not a back door. Inqudium ships with two built-in consumers (`inqudium-micrometer` and `inqudium-jfr`), but they have no privileged access — they use the same `onEvent()` API that any external consumer uses.

#### Open: exporting events to external systems (planned)

The built-in consumers (Micrometer, JFR) cover the two most common observability backends. But production environments often need events in other systems — Kafka topics for event sourcing, CloudEvents for cross-service integration, webhooks for alerting, or proprietary monitoring platforms.

Rather than building adapters for every possible target, the event system will expose an `InqEventExporter` SPI:

```java
public interface InqEventExporter {

    /**
     * Called for every event emitted by any element.
     * Implementations must be thread-safe and non-blocking.
     * Exceptions thrown by the exporter are caught and reported
     * — they never affect the resilience element's operation.
     */
    void export(InqEvent event);

    /**
     * Called once at startup. Allows the exporter to filter
     * which event types it is interested in.
     */
    default Set<Class<? extends InqEvent>> subscribedEventTypes() {
        return Set.of(); // empty = all events
    }
}
```

Exporters are registered globally (not per-element) and receive events from all elements in the application:

```java
InqEventExporterRegistry.register(new KafkaEventExporter(kafkaProducer, "inqudium-events"));
InqEventExporterRegistry.register(new CloudEventsExporter(webhookUrl));
```

The `subscribedEventTypes()` filter prevents unnecessary serialization — a Kafka exporter interested only in state transitions does not receive per-call events.

Design constraints for exporters:

- **Non-blocking.** Exporters must not block the thread that emits the event. If the target system requires I/O (Kafka produce, HTTP webhook), the exporter is responsible for buffering internally.
- **Exception-safe.** A failing exporter is caught, reported through `InqEventPublisher` as an exporter error, and does not affect the resilience element or other exporters.
- **No backpressure to the element.** If the exporter cannot keep up, it drops events or buffers — it never slows down the protected call.

#### Closed: emitting custom event types

`InqEventPublisher` transports only `InqEvent` subtypes defined in `inqudium-core`. External modules **cannot** define their own event types and push them through the publisher.

This boundary is deliberate. If the publisher accepted arbitrary types, every consumer would need to handle unknown events — requiring type registries, schema evolution, and defensive deserialization. The event system would become a generic event bus, which is not the responsibility of a resilience library. Projects that need a general-purpose event bus should use dedicated infrastructure (Spring ApplicationEvent, CDI Events, Kafka, etc.).

The `InqEventExporter` SPI bridges the gap: events flow **out** of Inqudium into external systems, but external events do not flow **in**.

## Consequences

**Positive:**
- Single observability contract for all elements and paradigms.
- Consumers choose their observability backend (Micrometer, JFR, logging, custom) without the library making assumptions.
- Event types are stable API — new listeners can be added without modifying element internals.
- Cross-paradigm consistency: a `CircuitBreakerOnStateTransitionEvent` is identical whether it came from `ReentrantLock`-based or `Mutex`-based state machine.
- `callId` enables end-to-end correlation of a single call across all elements, paradigms, and observability backends.
- `InqEventExporter` SPI allows integration with arbitrary external systems without Inqudium needing to know the target.

**Negative:**
- Every paradigm implementation must remember to emit events at the correct points. This is enforced by behavioral contract tests in core (given a mock publisher, verify the correct events are emitted for each scenario).
- Slight overhead from event object creation, though this is negligible compared to the actual resilience logic (network calls, timeouts).
- Exporter implementations must handle their own buffering and error recovery — Inqudium provides the SPI, not a production-ready exporter for every target system.

**Neutral:**
- Elements ship with zero logging dependency. The `inqudium-test` module may optionally log events for debugging convenience.
- The event system is open for consumption and export, but closed for external event emission. This keeps the type contract stable.
