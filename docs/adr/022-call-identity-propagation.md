# ADR-022: Call identity propagation

**Status:** Accepted  
**Date:** 2026-03-29  
**Deciders:** Core team  
**Supersedes:** Initial ThreadLocal-based InqCallContext (removed)

## Context

Every call through an Inqudium pipeline passes through multiple resilience elements — a Circuit Breaker, a Retry, a Rate Limiter, a Bulkhead, a Time Limiter — in sequence. For observability (ADR-003), all events emitted during a single call must share the same `callId`. This is the foundation of end-to-end correlation: filtering by `callId` in Kibana, Grafana, or JFR reconstructs the complete lifecycle of one request across all elements.

### The problem

In the initial implementation, each element generated its own `callId` independently via `UUID.randomUUID().toString()`. When three elements were composed in a pipeline, three different `callId` values appeared on the events:

```
Pipeline invocation:
  CircuitBreaker generates callId "aaa-111" → emits CB events with "aaa-111"
    Retry generates callId "bbb-222"        → emits Retry events with "bbb-222"
      RateLimiter generates callId "ccc-333" → emits RL events with "ccc-333"
```

The `callId` correlation — the core promise of ADR-003 — was broken. There was no mechanism to propagate a single identity through the decoration chain.

### Requirements

1. **Shared identity in pipelines:** All elements in a pipeline must use the same `callId` for a given invocation.
2. **Independent identity standalone:** When an element is used outside a pipeline, it generates its own `callId`. No pipeline context is required for standalone usage.
3. **Paradigm-agnostic:** The solution must work identically for imperative Java, Kotlin Coroutines, Project Reactor, and RxJava 3. It must not rely on thread affinity.
4. **Testable:** The `callId` must be predictable in tests — no random UUIDs that make event assertions fragile.
5. **Extensible:** The propagation mechanism should be able to carry additional context in the future (deadline, priority, trace context) without a redesign.

## Alternatives considered

### Alternative A: ThreadLocal-based InqCallContext

The pipeline sets a `callId` on a `ThreadLocal` before invoking the decoration chain. Each element reads from the `ThreadLocal` instead of generating its own ID:

```java
// Pipeline:
try (var scope = InqCallContext.activate(callId)) {
    return decoratedChain.get();
}

// Element:
var callId = InqCallContext.currentOrGenerate(config.getCallIdGenerator());
```

**Advantages:**
- Simple implementation. No API changes needed — elements read from a global context.
- The public API (`cb.decorateSupplier(supplier)`) remains unchanged.
- Nesting is supported via save/restore of previous value.

**Disadvantages:**
- **Reactive paradigms are broken.** In Project Reactor, execution hops between scheduler threads (`boundedElastic`, `parallel`, `single`). A `ThreadLocal` set on thread A is invisible on thread B. The context propagation SPI (ADR-011) can bridge this, but it means adding a workaround for a problem the architecture created.
- **Hidden state.** The `callId` is not visible in the method signature. A developer reading `element.decorateSupplier(supplier)` has no indication that a `ThreadLocal` is being read. Debugging requires knowledge of the context mechanism.
- **Not extensible.** Adding more context (deadline, priority) means adding more `ThreadLocal` fields — each with its own save/restore lifecycle and the same reactive-incompatibility problem.
- **Virtual thread proliferation.** With Java 21+ virtual threads, the number of threads in an application is effectively unbounded. ThreadLocals consume memory per thread. While the single `String callId` is small, the pattern does not scale if more context is added later.

### Alternative B: Explicit parameter through the chain

The `InqDecorator` interface receives the `callId` as an explicit parameter:

```java
public interface InqDecorator {
    <T> Supplier<T> decorate(Supplier<T> supplier, String callId);
}
```

The pipeline generates the `callId` and passes it through. Each element reads it from the parameter.

**Advantages:**
- Fully transparent — the `callId` is visible in every method signature.
- No hidden state, no threading concerns, works in all paradigms.
- Testable: pass a known `callId` and assert on events.

**Disadvantages:**
- **Dual API surface.** The public API (`cb.decorateSupplier(supplier)`) does not carry a `callId`. The pipeline-internal API needs an additional parameter. This creates two decoration methods per element — one for standalone (generates own ID), one for pipeline (receives ID). The distinction is an implementation detail that leaks into the API.
- **Not extensible.** Adding more context means adding more parameters: `decorate(supplier, callId, deadline, priority)`. The method signature grows with every new context field. Alternatively, a context object could bundle them — which is essentially Alternative D.
- **Stringly typed.** The `callId` is a bare `String`. There is no type-safe distinction between "a callId that came from a pipeline" and "a callId that an element generated itself."

### Alternative C: ScopedValue (Java 21+ Preview)

Java 21 introduced `ScopedValue` as a modern alternative to `ThreadLocal`, designed for structured concurrency:

```java
private static final ScopedValue<String> CALL_ID = ScopedValue.newInstance();

// Pipeline:
ScopedValue.runWhere(CALL_ID, callId, () -> decoratedChain.get());

// Element:
var callId = CALL_ID.isBound() ? CALL_ID.get() : generator.generate();
```

**Advantages:**
- No manual cleanup — scope-bound, automatically restored.
- Inherits into `StructuredTaskScope` child tasks — designed for structured concurrency.
- Faster than `ThreadLocal` (immutable after binding, no hash lookup).

**Disadvantages:**
- **Preview feature.** `ScopedValue` is a preview API in Java 21–24, finalized in Java 25. Using a preview feature in the core of a library means requiring `--enable-preview` for all consumers — unacceptable for a library that targets production use on Java 21+.
- **Same reactive incompatibility as ThreadLocal.** `ScopedValue` is thread-bound. Reactor's `publishOn` and `subscribeOn` switch threads — the scoped value is not visible on the new thread. The same context propagation workaround (ADR-011) would be needed.
- **Not extensible in the same way.** Adding more context means adding more `ScopedValue` declarations. The pattern is cleaner than `ThreadLocal` but fundamentally has the same single-field-per-declaration limitation.

### Alternative D: Context-carrying wrapper object (InqCall) ✅ Selected

Instead of propagating the `callId` through hidden state or extra parameters, the call itself carries its context. The `Supplier<T>` that flows through the decoration chain is replaced by an `InqCall<T>` record that bundles the `callId` with the supplier:

```java
public record InqCall<T>(String callId, Supplier<T> supplier) {
    public InqCall<T> withSupplier(Supplier<T> newSupplier) {
        return new InqCall<>(this.callId, newSupplier);
    }
    public T execute() {
        return supplier.get();
    }
}
```

The `InqDecorator` interface operates on `InqCall` instead of `Supplier`:

```java
public interface InqDecorator extends InqElement {
    <T> InqCall<T> decorate(InqCall<T> call);
}
```

**Advantages:**
- **No hidden state.** The `callId` is a field on the object flowing through the chain. Every decorator reads `call.callId()` — the source of the identity is visible and traceable.
- **Paradigm-agnostic.** The `InqCall` is a value object. It does not depend on thread identity, ThreadLocal, ScopedValue, or any execution model. It works identically in imperative, reactive, and coroutine contexts.
- **Extensible.** The `InqCall` record can grow to include additional context fields (deadline, priority, trace context, baggage) in future versions. All decorators automatically have access to the new fields without signature changes.
- **Testable.** Create an `InqCall` with a known `callId` and pass it to any decorator — assert on the events it emits. No setup, no ThreadLocal initialization, no cleanup.
- **Type-safe.** The `InqCall` is a distinct type. A method that accepts `InqCall` is unambiguously a pipeline-aware method. A method that accepts `Supplier` is a standalone entry point. The distinction is part of the type system, not a convention.

**Disadvantages:**
- **API surface change.** The `InqDecorator` interface uses `InqCall<T>` instead of `Supplier<T>`. Element interfaces must provide both `decorateSupplier(Supplier)` for standalone use and `decorate(InqCall)` for pipeline use. This is two methods instead of one — but the distinction is meaningful (standalone vs. pipeline) and not arbitrary.
- **Record overhead.** Each decoration step creates a new `InqCall` instance via `withSupplier()`. This is one object allocation per element per call. In practice, this is negligible compared to the cost of the actual downstream call, and the JVM's escape analysis may eliminate the allocation entirely.

## Decision

**Use Alternative D: `InqCall` as a context-carrying wrapper.**

The `InqCall<T>` record carries the `callId` (and future context) through the decoration chain as part of the data flow. No ThreadLocal, no ScopedValue, no extra parameters.

### Type hierarchy

```
InqElement (getName, getElementType, getEventPublisher)
  └── InqDecorator (+ decorate(InqCall<T>))
        ├── CircuitBreaker
        ├── Retry
        ├── RateLimiter
        ├── Bulkhead
        └── TimeLimiter
```

Every element interface extends `InqDecorator`, which extends `InqElement`. This means every element is automatically usable in `InqPipeline.shield()` without an adapter.

### InqCall record

```java
public record InqCall<T>(String callId, Supplier<T> supplier) {

    public InqCall<T> withSupplier(Supplier<T> newSupplier) {
        return new InqCall<>(this.callId, newSupplier);
    }

    public T execute() {
        return supplier.get();
    }
}
```

`withSupplier()` is the key method: it creates a new `InqCall` with the same `callId` but a different supplier. This is how decorators wrap the call:

```java
// Inside a CircuitBreaker decorator:
@Override
public <T> InqCall<T> decorate(InqCall<T> call) {
    return call.withSupplier(() -> {
        acquirePermission(call.callId());
        var start = clock.instant();
        try {
            T result = call.supplier().get();
            onSuccess(call.callId(), start);
            return result;
        } catch (Exception e) {
            onError(call.callId(), start, e);
            throw e;
        }
    });
}
```

### Pipeline flow

The pipeline generates one `callId`, wraps the original supplier in an `InqCall`, and passes it through all decorators in order:

```java
return () -> {
    var callId = callIdGenerator.generate();
    InqCall<T> call = InqCall.of(callId, originalSupplier);

    // Each decorator wraps the call, preserving the callId
    for (int i = chain.size() - 1; i >= 0; i--) {
        call = chain.get(i).decorate(call);
    }

    return call.execute();
};
```

All elements in the chain read `call.callId()` — the same value, from the same source, without any hidden state.

### Standalone usage

When an element is used outside a pipeline, the public API creates an `InqCall` internally:

```java
// CircuitBreaker.decorateSupplier — standalone entry point
@Override
public <T> Supplier<T> decorateSupplier(Supplier<T> supplier) {
    return () -> {
        var call = InqCall.of(config.getCallIdGenerator().generate(), supplier);
        return executeCall(call);
    };
}
```

The `callId` is generated fresh for each standalone invocation. The internal logic is identical — `executeCall(InqCall)` does not know or care whether the call came from a pipeline or from standalone usage.

### InqCallIdGenerator

The `callId` is generated by `InqCallIdGenerator` — a `@FunctionalInterface` on every config, analogous to `InqClock` for time:

```java
@FunctionalInterface
public interface InqCallIdGenerator {
    String generate();
    static InqCallIdGenerator uuid() { return () -> UUID.randomUUID().toString(); }
}
```

Override for deterministic tests, trace ID integration (reuse OpenTelemetry `traceId` as `callId`), or custom formats (ULID, Snowflake).

### Future extensibility

The `InqCall` record is designed to grow. When deadline propagation is needed:

```java
public record InqCall<T>(
    String callId,
    Supplier<T> supplier,
    Instant deadline       // new field — all decorators see it automatically
) { ... }
```

All existing decorators continue to work — they read `call.callId()` and `call.supplier()` as before. New decorators can additionally read `call.deadline()`. No signature changes, no ThreadLocal additions, no migration.

## Consequences

**Positive:**
- The `callId` correlation promise (ADR-003) is now structurally guaranteed. There is no code path where two elements in the same pipeline can see different `callId` values.
- Works identically across all four paradigms — no thread-affinity assumptions, no reactive workarounds.
- Testable without setup: `InqCall.of("test-id", supplier)` → pass to any decorator → assert on events.
- Extensible: new context fields can be added to the record without API-breaking changes.
- The type hierarchy (`InqDecorator extends InqElement`) means elements are directly usable in pipelines — no wrapper or adapter needed.

**Negative:**
- Two decoration methods per element: `decorateSupplier(Supplier)` for standalone, `decorate(InqCall)` for pipeline. This is intentional — the distinction is meaningful and part of the API contract — but it increases the interface surface.
- One extra object allocation per decoration step (`InqCall.withSupplier()`). Negligible in practice.

**Neutral:**
- The `InqCall` record is a core type that all paradigm modules will use. It must be stable — field additions are additive (new fields with defaults), field removals are breaking changes. This is the same stability contract as `InqEvent` and `InqConfig`.
- The `ThreadLocal` alternative was implemented and removed. The removal is a breaking change for anyone who depended on `InqCallContext` — but it was never released, so no external migration is needed.
