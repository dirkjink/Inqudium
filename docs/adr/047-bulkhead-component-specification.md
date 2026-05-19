# ADR-047: Bulkhead component specification

**Status:** Accepted
**Date:** 2026-05-19
**Deciders:** Core team
**Related:** ADR-020 (Bulkhead design — original semaphore/thread-pool rationale, preserved as design context),
ADR-029 (lifecycle implementation pattern — `BulkheadComponent` extends the contracts specified there),
ADR-033 (Pipeline integration of lifecycle-aware components — this ADR supersedes ADR-033's
bulkhead-specific rules 4, 5, 7; ADR-042 supersedes the general rules 1, 2, 3, 6),
ADR-042 (Pipeline contracts — `LayerAction` / `LayerTerminal` / `InqDecorator` / `InqAsyncDecorator`
contracts that the bulkhead component implements),
ADR-044 (Bulkhead strategies and hot-swap — strategy layer underneath the component),
ADR-045 (Bulkhead configuration, handle, diagnostics — the configuration and diagnostics layer),
ADR-046 (Paradigm tagging — `ParadigmTag` hierarchy that the handle is parameterised by).

## Implementation status

**Accepted.** The four-layer architecture specified by this ADR is
implemented on `main`:

- Layer 1 (Marker) — `eu.inqudium.core.element.InqElement.Kind.Bulkhead`
  in `inqudium-core`
- Layer 2 (Component capabilities) — `eu.inqudium.config.runtime.BulkheadComponent`
  in `inqudium-config`
- Layer 3 (Concrete component) — `eu.inqudium.imperative.bulkhead.InqBulkhead<A, R>`
  in `inqudium-imperative`
- Layer 4 (Paradigm-tagged handles) — `eu.inqudium.config.runtime.BulkheadHandle<P>`
  with package-private permits `SyncBulkheadHandle` and `AsyncBulkheadHandle`
  in `inqudium-config`

The structure was delivered by the component-handle separation refactor
(PR #102) and is normative for the bulkhead family. Future bulkhead
components for additional paradigms (Reactive, RxJava 3, Coroutines)
follow the same layering; future component families (CircuitBreaker,
Retry, RateLimiter, TimeLimiter, TrafficShaper) follow the same
four-layer pattern.

## Context

ADR-033 was a consolidation ADR that combined two kinds of rules
under one document: general pipeline contracts (Rules 1, 2, 3, 6 —
what every element looks like in the pipeline) and bulkhead-specific
applications (Rules 4, 5, 7 — how the bulkhead component fits the
pipeline). Cross-references to ADR-033 from other ADRs (notably
ADR-044 and ADR-045) sometimes named a placeholder *"ADR-046
(bulkhead imperative implementation)"* as the future home for the
bulkhead-specific applications, before ADR-046 was assigned to
paradigm tagging instead. This ADR closes that gap by occupying the
*"bulkhead imperative implementation"* slot under its own number.

Two ADRs split ADR-033's content into focused documents:

- **ADR-042 (Pipeline contracts)** absorbed the general rules, with
  some renaming (`InternalExecutor` → `LayerTerminal`,
  `InternalAsyncExecutor` → `AsyncLayerTerminal`) and a slightly
  expanded treatment.

- **ADR-047 (this document)** absorbs the bulkhead-specific
  applications and extends them with the component-handle separation
  that PR #102 delivered.

The component-handle separation was motivated by a structural
problem: `InqBulkhead<A, R>` originally implemented
`BulkheadHandle<SyncTag>` directly. The class was simultaneously
the component (paradigm-spanning, owning lifecycle, strategy,
snapshot, decorator surfaces for both `InqDecorator` and
`InqAsyncDecorator`) and a paradigm-tagged handle (synchronous-only,
exposing the read API and listeners). The conflation forced the
component to carry a single paradigm tag — `SyncTag` — even though
its decorator surface covered both sync and async, and a workaround
(`BulkheadHandleAsAsyncView`) had to wrap the same instance as an
async-tagged view.

PR #102 separated the two roles into distinct types and removed
the workaround. This ADR records the resulting structure.

## Decision

The bulkhead family is specified by a **four-layer architecture**.
Each layer has a single responsibility, lives in a specific module,
and depends only on layers below it. The directionality of
dependencies (`inqudium-core → inqudium-config → inqudium-imperative`)
is explicit and stable.

### Layer 1 — Marker (`InqElement.Kind.Bulkhead`)

```java
// inqudium-core/src/main/java/eu/inqudium/core/element/InqElement.java
public interface InqElement {

    String name();
    InqElementType elementType();
    InqEventPublisher eventPublisher();

    interface Kind {

        interface Bulkhead extends InqElement {
            @Override default InqElementType elementType() {
                return InqElementType.BULKHEAD;
            }
        }

        // CircuitBreaker, Retry, TimeLimiter, RateLimiter,
        // TrafficShaper — same shape, with the matching
        // InqElementType default.
    }
}
```

The marker carries *structural type-identity* — "this is a bulkhead-
typed element". It carries no behaviour beyond the default
`elementType()` override. It lives in `inqudium-core` because it
needs no knowledge of lifecycle, listeners, configuration, or any
paradigm-specific concept.

This is the layer that future bulkhead components in any paradigm
(`InqReactiveBulkhead`, `InqRxJava3Bulkhead`, `InqCoroutinesBulkhead`,
…) implement to declare their kind. It is also the lower bound of
`BulkheadHandle.target()` — see Layer 4.

### Layer 2 — Component capabilities (`BulkheadComponent`)

```java
// inqudium-config/src/main/java/eu/inqudium/config/runtime/BulkheadComponent.java
public interface BulkheadComponent
        extends InqElement.Kind.Bulkhead,
                LifecycleAware,
                ListenerRegistry<BulkheadSnapshot>,
                InternalMutabilityCheck<BulkheadSnapshot> {

    BulkheadSnapshot snapshot();
    int availablePermits();
    int concurrentCalls();
}
```

`BulkheadComponent` is the paradigm-agnostic capability surface of
a bulkhead. It bundles four contracts:

- `InqElement.Kind.Bulkhead` — element identity (Layer 1).
- `LifecycleAware` — cold/hot/removed transitions (ADR-029).
- `ListenerRegistry<BulkheadSnapshot>` — change-request listener
  registration.
- `InternalMutabilityCheck<BulkheadSnapshot>` — ADR-028 veto-chain
  participation.

Plus three bulkhead-specific accessors for direct read access to
the live container.

It lives in `inqudium-config` because the lifecycle, listener, and
snapshot contracts already live there; co-locating the bulkhead
capability surface with its prerequisites keeps the module
boundaries clean.

Handles delegate every method to a `BulkheadComponent` reference
they hold. They never cast on a concrete paradigm-specific class —
all delegation flows through this interface.

### Layer 3 — Concrete implementation (`InqBulkhead<A, R>`)

```java
// inqudium-imperative/src/main/java/eu/inqudium/imperative/bulkhead/InqBulkhead.java
public final class InqBulkhead<A, R>
        extends ImperativeLifecyclePhasedComponent<BulkheadSnapshot, A, R>
        implements BulkheadComponent,
                   InqDecorator<A, R>,
                   InqAsyncDecorator<A, R> {

    // ... constructor, createHotPhase(), eventPublisher() ...
}
```

`InqBulkhead<A, R>` is the concrete component class for the
**imperative** paradigm family. It inherits the lifecycle scaffolding
from `ImperativeLifecyclePhasedComponent` (which already implements
`LifecycleAware`, `ListenerRegistry`, and `InternalMutabilityCheck`)
and adds the bulkhead-specific decorator surfaces.

Crucially, `InqBulkhead` implements **both** `InqDecorator<A, R>`
(the synchronous pipeline contract) and `InqAsyncDecorator<A, R>`
(the asynchronous pipeline contract). A bulkhead is one component
regardless of which call shape its callers use — sync and async paths
share the same hot-phase strategy, the same listener registry, and
the same lifecycle identity.

Future paradigm families will contribute their own concrete classes
in their own modules:

- `InqReactiveBulkhead<T>` in `inqudium-reactor` —
  `implements BulkheadComponent, InqReactiveDecorator<T>`
- `InqRxJava3Bulkhead<T>` in `inqudium-rxjava3` —
  `implements BulkheadComponent, InqRxJava3Decorator<T>`
- `InqCoroutinesBulkhead<T>` in `inqudium-kotlin` —
  `implements BulkheadComponent, InqCoroutinesDecorator<T>`

Each lives in its own paradigm module, depends only on
`inqudium-config` (for `BulkheadComponent`) and `inqudium-core`
(for `InqElement.Kind.Bulkhead`), and is invisible to the other
paradigm modules.

### Layer 4 — Paradigm-tagged handles (`BulkheadHandle<P>`)

```java
// inqudium-config/src/main/java/eu/inqudium/config/runtime/BulkheadHandle.java
public sealed interface BulkheadHandle<P extends ParadigmTag>
        extends BulkheadComponent
        permits SyncBulkheadHandle, AsyncBulkheadHandle {

    <T extends InqElement.Kind.Bulkhead> T target();

    static BulkheadHandle<SyncTag> sync(BulkheadComponent component) {
        return new SyncBulkheadHandle(component);
    }

    static BulkheadHandle<AsyncTag> async(BulkheadComponent component) {
        return new AsyncBulkheadHandle(component);
    }
}
```

`BulkheadHandle<P>` is a paradigm-tagged surface that wraps a
`BulkheadComponent`. The handle adds nothing functional beyond
the component's own capabilities — `BulkheadHandle` *extends*
`BulkheadComponent`, so callers can read snapshots, register
listeners, and observe lifecycle through the handle the same way
they would through the component. What the handle adds is two things:

1. A compile-time **paradigm tag** `P` that constrains where a
   handle may be used. A method that accepts a `BulkheadHandle<AsyncTag>`
   refuses a sync handle at compile time; a sync-only API path
   cannot accidentally pull in async semantics.

2. A `target()` accessor that returns the wrapped component for
   callers that need it. The component carries the
   paradigm-spanning decorator methods (`decorateSupplier`,
   `decorateAsyncSupplier`, …); a caller routes the same component
   through whichever call shape it actually uses.

The two concrete permits are `SyncBulkheadHandle` and
`AsyncBulkheadHandle`, both package-private in `inqudium-config`.
The implementations are trivial — every method delegates to the
wrapped `BulkheadComponent` — and they exist solely to carry the
paradigm tag and the `target()` accessor.

Multiple handles can point at the same component. `DefaultImperative`
returns a `SyncBulkheadHandle` from `runtime.sync().bulkhead(name)`;
`DefaultAsync` returns an `AsyncBulkheadHandle` for the same name,
wrapping the same component instance. The component itself has
exactly one instance per `(paradigm-family, name)` registry key.

## Namespace convention: `InqElement.Kind`

The Layer-1 markers (`Bulkhead`, `CircuitBreaker`, `Retry`,
`TimeLimiter`, `RateLimiter`, `TrafficShaper`) live nested under
an intermediate utility interface `Kind` rather than directly under
`InqElement`. The reason is structural, not aesthetic: an early
version of the refactor placed them as `InqElement.Bulkhead`,
`InqElement.CircuitBreaker`, etc. The build broke immediately.

The legacy top-level interface `eu.inqudium.imperative.bulkhead.Bulkhead<A, R>`
extends `InqDecorator<A, R>`, which transitively extends `InqElement`.
A nested member type of a super-interface enters the scope of every
subtype, so inside `interface Bulkhead<A, R> extends InqDecorator<A, R>`
the simple name `Bulkhead` is ambiguous: it can refer to the
enclosing type (the legacy interface itself) or to the inherited
nested type (`InqElement.Bulkhead`). The Java compiler resolves the
ambiguity to the nested type — *self-shadowing* — and the legacy
interface's own self-references stop compiling.

The intermediate `Kind` namespace breaks the conflict: the simple
name `Bulkhead` is only reachable through a two-segment path
(`Kind.Bulkhead`), and the legacy interface's self-references remain
unambiguous. The pattern generalises to every other element kind
(`Kind.CircuitBreaker`, `Kind.Retry`, …) and is robust against any
future legacy class that might share a name with an element kind.

A secondary benefit: the `Kind` namespace names the semantic
relationship explicitly. A class that implements `InqElement.Kind.Bulkhead`
is declaring *"my element kind is bulkhead"* — a flatter
`InqElement.Bulkhead` could read as a Bulkhead-Is-A-InqElement
inheritance claim. The intermediate level disambiguates the intent.

## Architecture properties

### Separation of concerns

**Component = what it is + what it can do.** **Handle = how user
code holds and observes it.**

Before the refactor, `InqBulkhead` was simultaneously both. The
class wore the `BulkheadHandle<SyncTag>` interface, which paradigm-
tagged a component that fundamentally supported two paradigms. The
workaround (`BulkheadHandleAsAsyncView`) wrapped the same instance
to expose it as `BulkheadHandle<AsyncTag>`. The conflation made it
unclear whether `InqBulkhead` was the component or a handle.

After the refactor, every type has one role. `InqBulkhead` is the
component — no paradigm tag. Handles are independent wrapper objects
that carry the tag. The `BulkheadHandleAsAsyncView` workaround is
gone; the async handle is a first-class `AsyncBulkheadHandle` that
wraps the component directly.

### Module-cycle freedom

The dependency direction is
`inqudium-core → inqudium-config → inqudium-imperative`, with no
back-edges. The component-handle separation respects this direction:

- `inqudium-core` defines the marker (`InqElement.Kind.Bulkhead`)
  and depends on nothing.
- `inqudium-config` defines `BulkheadComponent`, `BulkheadHandle`,
  and the two handle permits. It depends on `inqudium-core`.
- `inqudium-imperative` provides the concrete component
  (`InqBulkhead`) and the runtime (`DefaultImperative`). It depends
  on `inqudium-config`.

The handles never reference `InqBulkhead` directly. They reference
the `BulkheadComponent` interface in their own module. The
delegation pattern keeps Maven happy without sacrificing the
component-handle separation.

The first-cut sketch of the refactor had `SyncBulkheadHandle`
(in `inqudium-config`) downcast to
`eu.inqudium.imperative.bulkhead.InqBulkhead<?, ?>` to access
component methods. That closes the cycle (`inqudium-config →
inqudium-imperative`) and Maven would reject it. The
`BulkheadComponent` interface eliminates the cast entirely and
keeps the directionality stable.

### Public static factories for cross-module construction

The sealed `BulkheadHandle` permits two package-private concrete
classes in `inqudium-config`. Code in other modules — notably
`DefaultImperative` in `inqudium-imperative` — needs to construct
handles. Direct `new SyncBulkheadHandle(component)` is not possible
because the concrete class is package-private; making the class
public exposes implementation details.

The idiomatic Java solution is to add **public static factory
methods** on the sealed interface itself:

```java
public sealed interface BulkheadHandle<P extends ParadigmTag>
        extends BulkheadComponent
        permits SyncBulkheadHandle, AsyncBulkheadHandle {

    static BulkheadHandle<SyncTag> sync(BulkheadComponent component) {
        return new SyncBulkheadHandle(component);
    }

    static BulkheadHandle<AsyncTag> async(BulkheadComponent component) {
        return new AsyncBulkheadHandle(component);
    }

    // ...
}
```

The interface owns the permits and can therefore reach them at
compile time, including from a static method body. Cross-module
callers see a clean, declarative construction API
(`BulkheadHandle.sync(component)`) without learning the names of
the concrete classes.

This is the recommended pattern for every future sealed handle
hierarchy — `CircuitBreakerHandle`, `RetryHandle`, …  — when the
concrete permits stay package-private.

### Scalability for paradigm families

Today's paradigm families are `SyncTag` and `AsyncTag` (both
imperative). The architecture absorbs new paradigms by addition,
not modification:

To add a Reactive paradigm:

1. New concrete component in `inqudium-reactor`:
   ```java
   public final class InqReactiveBulkhead<T>
           extends ReactiveLifecyclePhasedComponent<...>
           implements BulkheadComponent,
                      InqReactiveDecorator<T> {
       // ...
   }
   ```
2. New handle permit in `inqudium-config`:
   `ReactiveMonoBulkheadHandle implements BulkheadHandle<ReactiveMonoTag>`
3. `BulkheadHandle`'s `permits` list extended to include the new
   handle.
4. Static factory `BulkheadHandle.reactiveMono(component)` added
   alongside `.sync(...)` and `.async(...)`.
5. New `Reactive` runtime surface providing
   `runtime.reactive().bulkhead(name)`.

No change to `inqudium-core`. No change to `inqudium-imperative`.
No change to `InqElement.Kind.Bulkhead` or `BulkheadComponent`.
Pure extension.

### Scalability for component families

Today the architecture is exercised only by the bulkhead family.
The same four-layer pattern applies to every other resilience
component:

| Component       | Layer 1 marker                            | Layer 2 capability             | Layer 3 concrete classes                                       | Layer 4 sealed handle                                         |
|-----------------|-------------------------------------------|--------------------------------|----------------------------------------------------------------|---------------------------------------------------------------|
| Bulkhead        | `InqElement.Kind.Bulkhead` *(today)*      | `BulkheadComponent` *(today)*  | `InqBulkhead`, future paradigm peers                           | `BulkheadHandle<P>` *(today)*                                 |
| CircuitBreaker  | `InqElement.Kind.CircuitBreaker` *(today)*| `CircuitBreakerComponent`      | `InqCircuitBreaker`, future paradigm peers                     | `CircuitBreakerHandle<P>`                                     |
| Retry           | `InqElement.Kind.Retry` *(today)*         | `RetryComponent`               | `InqRetry`, future paradigm peers                              | `RetryHandle<P>`                                              |
| TimeLimiter     | `InqElement.Kind.TimeLimiter` *(today)*   | `TimeLimiterComponent`         | `InqTimeLimiter`, future paradigm peers                        | `TimeLimiterHandle<P>`                                        |
| RateLimiter     | `InqElement.Kind.RateLimiter` *(today)*   | `RateLimiterComponent`         | `InqRateLimiter`, future paradigm peers                        | `RateLimiterHandle<P>`                                        |
| TrafficShaper   | `InqElement.Kind.TrafficShaper` *(today)* | `TrafficShaperComponent`       | `InqTrafficShaper`, future paradigm peers                      | `TrafficShaperHandle<P>`                                      |

The six markers were added in the same refactor as the bulkhead
work, so the Layer-1 work is already done for every component
family. Future component-family ADRs reference back to this ADR
for the architectural template and add only their family-specific
material.

### Type-witness pattern for `target()`

```java
<T extends InqElement.Kind.Bulkhead> T target();
```

The bounded type parameter `<T extends InqElement.Kind.Bulkhead>`
makes the receiving variable's type drive the inferred component
class. The most common call sites then read without explicit casts:

```java
// Caller writes:
InqBulkhead<String, Integer> bh =
        runtime.sync().bulkhead("payment").target();

// Type inference picks T = InqBulkhead<String, Integer>; the
// unchecked cast inside target() is the implementation's
// concern, not the caller's.
```

The bound catches structural mistakes at compile time:

- `String s = handle.target()` — `String` is not a
  `InqElement.Kind.Bulkhead`, so it fails to compile.
- `InqCircuitBreaker cb = handle.target()` — a
  `Kind.CircuitBreaker` does not satisfy `Kind.Bulkhead`, so it
  fails to compile.

What the bound does **not** catch is the rarer mistake of using
the wrong paradigm-specific bulkhead component (e.g. assigning a
sync-handle's target to an `InqReactiveBulkhead` variable when
both implement `Kind.Bulkhead`). That kind of mistake remains a
runtime `ClassCastException`, but it is unusual in practice — a
service that obtains a handle from `runtime.sync()` knows it is
working with the imperative family, and the call-site variable type
declares the expected component.

A migration lesson worth recording: the refactor that replaced
`unwrap(InqBulkhead.class)` with `target()` had to clean up
**two** anti-patterns, not one. The explicit `unwrap` call was
the obvious case; a direct cast like
`(InqBulkhead<?, ?>) runtime.sync().bulkhead("name")` lurked in
one main-source consumer (`DefaultOrderService` in the proxy
example) and was easy to miss. Future refactors that rename access
APIs should `git grep` for both shapes.

## Bulkhead-specific applications

### Rule 4 (generalised): `BulkheadHandle` exposes `InqElement` identity

ADR-033 Rule 4 stated *"the bulkhead handle implements `InqElement`
so it can name itself and emit events."* Post-refactor this rule
generalises: `BulkheadComponent` extends `InqElement.Kind.Bulkhead`
(which transitively extends `InqElement`), so every component
**is** an `InqElement` by construction. `BulkheadHandle<P>` extends
`BulkheadComponent` and therefore also exposes `InqElement` identity
without redeclaring the methods. Both paths converge to the same
contract.

### Rule 5: `ImperativeBulkhead` removal

ADR-033 Rule 5 anticipated the deletion of the legacy
`ImperativeBulkhead` interface that predated `InqBulkhead`. That
deletion was carried out by the P.9 polish step. Users type against
`BulkheadHandle<SyncTag>` via `runtime.sync().bulkhead(name)` and
reach the concrete component with `.target()`. The legacy `Bulkhead`
top-level interface still exists for source-compatibility reasons
under a `@Deprecated` marker; its removal is a separate refactor.

### Rule 7: `InqBulkhead<A, R>` generic propagation

ADR-033 Rule 7 specified the generic propagation
`InqBulkhead<A, R>` → `InqDecorator<A, R>` → `LayerAction<A, R>`,
with `A`/`R` chosen by callers at the resolve site. Post-refactor
the rule is unchanged for the component but clarified for the
handle:

- The **component** is fully generic: `InqBulkhead<A, R>
  implements BulkheadComponent, InqDecorator<A, R>,
  InqAsyncDecorator<A, R>`. The `<A, R>` parameters propagate
  through every decorator method.

- The **handle** is *not* generic in `<A, R>`. It only carries the
  paradigm tag `<P extends ParadigmTag>`. The `<A, R>` parameters
  appear only at the call site where the caller assigns the
  `target()` result to a typed variable:

  ```java
  BulkheadHandle<SyncTag> handle = runtime.sync().bulkhead("payment");
  InqBulkhead<String, Integer> bh = handle.target();
  // <A, R> = <String, Integer> chosen here, not on the handle.
  ```

This split keeps the runtime-lookup API (`runtime.sync().bulkhead(...)`)
unconstrained by call-site types — one bulkhead handle serves
callers with any `<A, R>` shape.

## Paradigm-specific chapters

### Synchronous imperative (`SyncTag`)

`runtime.sync().bulkhead(name)` returns `BulkheadHandle<SyncTag>`.
The concrete handle is `SyncBulkheadHandle`, package-private in
`inqudium-config`. The handle wraps the `InqBulkhead` component
shared with the async path; the only difference from the async
view is the compile-time tag and the call-shape contract of
`SyncTag`:

- Method-return semantics. The bulkhead acquires a permit on entry
  to the protected method and releases it on method return,
  whether normal or by thrown exception.
- ThreadLocal-bound execution context. The protected method runs
  on the calling thread.
- Synchronous-decorator use: `bh.decorateSupplier(...)`,
  `bh.decorateRunnable(...)`, `bh.decorateFunction(...)`,
  `bh.decorateCallable(...)`, `bh.decorateJoinPoint(...)`.

### Asynchronous imperative (`AsyncTag`)

`runtime.async().bulkhead(name)` returns `BulkheadHandle<AsyncTag>`.
The concrete handle is `AsyncBulkheadHandle`, also package-private
in `inqudium-config`. It wraps **the same** `InqBulkhead` instance
that the sync handle wraps — `syncHandle.target()` and
`asyncHandle.target()` return the same component reference.

The `AsyncTag` contract differs from `SyncTag`:

- `CompletionStage<T>` return shape. Exceptions surface in a failed
  stage rather than being thrown.
- Execution context may change mid-call; no ThreadLocal guarantee.
- Permit release happens on stage completion (via `whenComplete`),
  not on method return.
- Asynchronous-decorator use: `bh.decorateAsyncSupplier(...)`,
  `bh.decorateAsyncRunnable(...)`, etc.

### Future paradigms

Each future paradigm family contributes:

1. A concrete component class in its paradigm module
   (`InqReactiveBulkhead`, `InqRxJava3Bulkhead`,
   `InqCoroutinesBulkhead`, …) implementing `BulkheadComponent`
   plus the paradigm's decorator interface.
2. One or more handle permit classes in `inqudium-config`
   (`ReactiveMonoBulkheadHandle`, `ReactiveFluxBulkheadHandle`,
   `RxJava3SingleBulkheadHandle`, …) implementing
   `BulkheadHandle<ParadigmTag>`.
3. An entry in `BulkheadHandle`'s `permits` list and a matching
   static factory method.
4. A runtime-lookup method (`runtime.reactive().bulkhead(name)`,
   …) on a paradigm-specific runtime surface.

Anticipated paradigm tags (per ADR-046):

- **Reactor:** `ReactiveMonoTag`, `ReactiveFluxTag`.
- **RxJava 3:** `RxJava3SingleTag`, `RxJava3MaybeTag`,
  `RxJava3CompletableTag`, `RxJava3FlowableTag`,
  `RxJava3ObservableTag`.
- **Kotlin coroutines:** `CoroutinesSuspendTag`,
  `CoroutinesDeferredTag`, `CoroutinesJobTag`, `CoroutinesFlowTag`.

Each component family (Bulkhead, CircuitBreaker, Retry, …)
multiplied by each paradigm produces a concrete component class.
The matrix is large in principle; the four-layer architecture
ensures that adding any single cell is a localised operation
without touching the existing cells.

## Consequences

**Positive**

- Clear separation between the component (what a bulkhead is) and
  the handle (how user code holds and observes it).
- No module cycles. `inqudium-core → inqudium-config →
  inqudium-imperative` is preserved without back-edges.
- Linear extension model for new paradigms. Adding a Reactive
  bulkhead is a localised change in a new module plus three small
  additions in `inqudium-config` (new handle permit, new permits
  entry, new static factory).
- Linear extension model for new component families. The Layer-1
  markers for `CircuitBreaker`, `Retry`, `TimeLimiter`,
  `RateLimiter`, `TrafficShaper` are already in place; future
  family ADRs reuse the four-layer template without re-deriving
  it.
- The type-witness pattern on `target()` catches the common
  mistakes (wrong element kind, fundamentally non-`InqElement`
  type) at compile time without forcing callers to write casts.

**Negative**

- One more interface (`BulkheadComponent`) for readers to absorb.
  The four layers are conceptually clean but represent four
  distinct types where one used to live.
- Cross-module public static factories on a sealed interface
  (`BulkheadHandle.sync(...)`, `.async(...)`) are an unusual idiom
  for readers unfamiliar with Java sealed hierarchies. The
  pattern's necessity is non-obvious until one encounters the
  package-private-permit constraint.
- The `Kind` namespace nesting (`InqElement.Kind.Bulkhead`) adds
  one segment to every type reference. The reason — self-shadowing
  prevention against legacy top-level types — is structural and
  worth explaining, but it costs a measure of brevity at every
  call site.
- The unchecked cast inside `target()` (`(T) component`) is
  guarded by the bounded type parameter but is still an unchecked
  cast at runtime. A wrong concrete component class fails at the
  call-site assignment, not inside `target()` itself.

**Neutral**

- The architecture preserves ADR-029's lifecycle implementation
  pattern entirely; `BulkheadComponent` extends `LifecycleAware`,
  `ListenerRegistry`, and `InternalMutabilityCheck` so every
  component automatically participates in lifecycle phases and
  veto-chain negotiation.
- The Layer-3 concrete classes still inherit from
  `ImperativeLifecyclePhasedComponent` (or paradigm-equivalent
  base classes). The refactor changed only the interfaces the
  classes declare; the lifecycle scaffolding is unchanged.
- The decorator surfaces (`InqDecorator`, `InqAsyncDecorator`,
  future `InqReactiveDecorator`, …) remain orthogonal to the
  component / handle split. A component implements as many
  decorator interfaces as the call shapes it supports.
- ADR-044 (Bulkhead strategies and hot-swap), ADR-045 (Bulkhead
  configuration, handle, diagnostics), and ADR-046 (Paradigm
  tagging) compose with this ADR: ADR-044 specifies the strategy
  layer beneath the component, ADR-045 specifies configuration and
  diagnostics inputs and outputs, ADR-046 specifies the tag
  hierarchy that parameterises the handle.

## What this ADR does not decide

- **Does not introduce concrete components for CircuitBreaker,
  Retry, TimeLimiter, RateLimiter, or TrafficShaper.** Those will
  each get a dedicated component-specification ADR following the
  same four-layer template.
- **Does not introduce reactive, RxJava 3, or coroutines bulkhead
  components.** The architecture accommodates them; the
  implementations are future work.
- **Does not delete the legacy top-level interfaces
  `Bulkhead` and `ImperativeBulkhead`** still residing in
  `inqudium-imperative` as `@Deprecated` source-compatibility
  shims. Their removal is a separate "legacy resilience surface"
  refactor.
- **Does not modify ADR-029's lifecycle implementation pattern.**
  The lifecycle scaffolding is preserved as-is.

## Lessons (general)

Three lessons from this refactor apply beyond ADR-047 to future
component-family architectural decisions:

1. **Module-dependency direction is an architectural anchor.**
   ADRs should explicitly state the module dependency direction
   they assume. Implementation sessions discover hidden cycles
   that ADRs glossed over; naming the direction up front catches
   the problem before the cycle closes.

2. **The four-layer pattern (Marker / Capability / Concrete /
   Handle) is reusable.** Every resilience component family fits
   the same template. Future component-family ADRs should cite
   this ADR for the architectural template rather than re-deriving
   it.

3. **Bounded type parameters are a concrete type-witness tool.**
   They replace explicit casts at call sites with compile-time
   checks at variable-type declarations. Idiomatic Java, minimal
   boilerplate, effective against the most common caller
   mistakes. The unchecked cast inside the API is a small cost
   for the type-safety it enables outside the API.
