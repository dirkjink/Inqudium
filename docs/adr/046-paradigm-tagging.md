# ADR-046: ParadigmTag stamping by AnnotationEvaluator

**Status:** Accepted
**Date:** 2026-05-18 (proposed); 2026-05-19 (accepted)
**Deciders:** Core team

## Context

Inqudium protects user code with resilience elements — bulkheads,
retries, circuit breakers, rate limiters, time limiters, traffic
shapers. The protection itself is delivered by integration
mechanisms: a JDK proxy, an AspectJ aspect, a function-style call
wrapper, a Spring AOP advice. All of them apply the same logical
element set to user methods; only the mechanism of interception
differs.

Each user method is written in a specific programming style — a
**paradigm**. The library identifies four broad paradigms plus
one anticipated:

- **Synchronous imperative.** Direct return value, synchronous
  exceptions, ThreadLocal-bound execution context. The classical
  Java method, including `void`-returning methods.
- **Asynchronous imperative.** `CompletionStage<T>` return type.
  Exceptions surface in a failed stage rather than being thrown.
  The execution context can change mid-call (no ThreadLocal
  guarantee). Permits and other resources release on stage
  completion, not method return.
- **Reactive (Project Reactor).** `Mono<T>` (single-value) or
  `Flux<T>` (multi-value) return type. Cold streams; backpressure
  semantics; subscription-driven execution.
- **RxJava 3.** Five distinct return-type shapes: `Single<T>`
  (single-value, must produce a result), `Maybe<T>` (optional
  single-value), `Completable` (signal-only, no value),
  `Flowable<T>` (backpressure-aware multi-value), `Observable<T>`
  (multi-value without backpressure).
- **Kotlin coroutines.** Four distinct shapes:
  - `suspend fun foo(): T` — continuation-based suspension
  - `fun foo(): Deferred<T>` — async handle with awaitable result
  - `fun foo(): Job` — async handle without result, lifecycle only
  - `fun foo(): Flow<T>` — cold stream

### The classification principle: completion semantics

The library's paradigm classification rests on a single
principle: **paradigm is determined by the completion semantics
of the method call**, not by the presence or absence of a return
value.

- **Synchronous paradigm:** method return signals operation
  completion. A `void`-returning method and a `String`-returning
  method are both synchronous because both are *finished from a
  resilience standpoint* when control returns to the caller.
  Permit-release happens at method-return.
- **Asynchronous paradigm:** method return delivers a handle that
  later signals operation completion. A `CompletionStage<Void>`-
  returning method and a `CompletionStage<String>`-returning
  method are both asynchronous. So is a `Job`-returning Kotlin
  function: the call returns a handle while the actual work
  continues; the operation is not finished at method-return.
  Permit-release happens at handle-completion.

`Job` and `CompletableFuture<Void>` are structurally equivalent
from this perspective: both return a lifecycle handle, both
signal completion through the handle (via `Job.invokeOnCompletion`
or `CompletableFuture.whenComplete`), both have actual work that
continues after method-return. They both belong to async
paradigms — `CompletableFuture<Void>` to the `CompletionStage`
async-imperative paradigm, `Job` to the coroutine paradigm.

### Two responsibilities for a paradigm identifier

The library needs a **type-level identifier of paradigm** for
two reasons:

1. **Routing.** When a user method is wrapped by an integration
   mechanism, the mechanism routes the call through the
   paradigm's matching contract. A `CompletionStage`-returning
   method routes through the async chain; a `String`-returning
   method routes through the sync chain. The integration
   mechanism makes this choice statically rather than
   re-discovering it on every call.

2. **Registry identity.** A user can register two logically
   distinct elements under the same name — `"orderBh"` as a
   sync-imperative bulkhead AND `"orderBh"` as a reactive
   bulkhead — if the user happens to expose both styles. The
   registry's primary key is therefore `(paradigm, name)`, not
   `name` alone. Lookups need the paradigm to disambiguate.

A paradigm identifier carrying these two responsibilities is a
**`ParadigmTag`**: a sealed, library-wide marker that names the
paradigm at the type level. Each paradigm contributes one or
more tags permitted by the sealed family. Tags carry no runtime
data; they exist to make the paradigm a compile-time fact.

### Why some paradigms split into sub-shapes

A resilience element implements the paradigm's contract over a
specific completion-handle type. For asynchronous paradigms with
multiple completion-handle types (Reactor's `Mono`/`Flux`,
RxJava3's five reactive types, coroutines' four shapes), the
sub-shapes differ in *subscription contract* and *result
semantics*, not in async-ness:

- `Mono<T>` produces zero or one value; `Flux<T>` produces zero
  or more. A bulkhead releases its permit on Mono's
  success/error; on Flux's terminal signal. Same hook (`doFinally`),
  but the resilience policy may want to count emissions for Flux
  in a way that's meaningless for Mono.
- A retry on `Single<T>` retries on error and re-subscribes to
  get a new value. A retry on `Completable` retries on error but
  has no value to replay. The retry mechanism for both is
  re-subscription, but the result-handling differs.
- `Deferred<T>` carries an awaitable result; `Job` does not.
  Retry-with-result-replay is meaningful for `Deferred<T>`,
  meaningless for `Job`.

A flat tag-per-paradigm would force every resilience element to
re-discover these sub-shape distinctions internally. A
hierarchical tag scheme preserves the distinction the classifier
already made, making it available at the type level for
resilience elements that need it.

Async-imperative is **not split** because its sub-shapes —
`CompletableFuture<T>`, `MinimalCompletionStage<T>`, user-defined
`CompletionStage` implementations — share a single completion
contract: `CompletionStage.whenComplete(...)` is the entire
interaction surface. No resilience element would behave
differently across these sub-shapes; no distinction is worth
preserving.

## Decision

Five concrete decisions, building on each other.

### 1. `ParadigmTag` lives in `inqudium-core`

`ParadigmTag` is conceptually as primitive as `InqElementType`.
Both classify the static identity of a resilience element from a
different axis: element type ("is this a bulkhead or a retry?")
and paradigm ("is this synchronous, asynchronous, reactive, or
coroutine-based?"). Both must be visible to every module that
touches a resilience element.

`inqudium-core` is the base module that every other paradigm-
aware module depends on. `ParadigmTag` and its sealed family
belong there.

### 2. Two-level sealed hierarchy

```java
package eu.inqudium.core.element;

public sealed interface ParadigmTag
        permits SyncTag, AsyncTag, ReactiveTag, RxJava3Tag, CoroutinesTag {
}

public final class SyncTag implements ParadigmTag {
    public static final SyncTag INSTANCE = new SyncTag();
    private SyncTag() { }
}

public final class AsyncTag implements ParadigmTag {
    public static final AsyncTag INSTANCE = new AsyncTag();
    private AsyncTag() { }
}

public sealed interface ReactiveTag extends ParadigmTag
        permits ReactiveMonoTag, ReactiveFluxTag {

    ReactiveMonoTag MONO = ReactiveMonoTag.INSTANCE;
    ReactiveFluxTag FLUX = ReactiveFluxTag.INSTANCE;
}

public final class ReactiveMonoTag implements ReactiveTag {
    static final ReactiveMonoTag INSTANCE = new ReactiveMonoTag();
    private ReactiveMonoTag() { }
}

public final class ReactiveFluxTag implements ReactiveTag {
    static final ReactiveFluxTag INSTANCE = new ReactiveFluxTag();
    private ReactiveFluxTag() { }
}

public sealed interface RxJava3Tag extends ParadigmTag
        permits RxJava3SingleTag, RxJava3MaybeTag, RxJava3CompletableTag,
                RxJava3FlowableTag, RxJava3ObservableTag {

    RxJava3SingleTag       SINGLE      = RxJava3SingleTag.INSTANCE;
    RxJava3MaybeTag        MAYBE       = RxJava3MaybeTag.INSTANCE;
    RxJava3CompletableTag  COMPLETABLE = RxJava3CompletableTag.INSTANCE;
    RxJava3FlowableTag     FLOWABLE    = RxJava3FlowableTag.INSTANCE;
    RxJava3ObservableTag   OBSERVABLE  = RxJava3ObservableTag.INSTANCE;
}

// (five final tag classes with INSTANCE constants, omitted for brevity)

public sealed interface CoroutinesTag extends ParadigmTag
        permits CoroutinesSuspendTag, CoroutinesDeferredTag,
                CoroutinesJobTag, CoroutinesFlowTag {

    CoroutinesSuspendTag   SUSPEND  = CoroutinesSuspendTag.INSTANCE;
    CoroutinesDeferredTag  DEFERRED = CoroutinesDeferredTag.INSTANCE;
    CoroutinesJobTag       JOB      = CoroutinesJobTag.INSTANCE;
    CoroutinesFlowTag      FLOW     = CoroutinesFlowTag.INSTANCE;
}

// (four final tag classes with INSTANCE constants, omitted for brevity)
```

`SyncTag` and `AsyncTag` are top-level final classes — no
sub-shapes because no resilience-relevant sub-distinction exists.

`ReactiveTag`, `RxJava3Tag`, and `CoroutinesTag` are sealed
sub-interfaces; their permitted concrete tags are the
resilience-relevant sub-shapes. Constants live on the family
interface so that `ReactiveTag.MONO`, `CoroutinesTag.SUSPEND`,
etc. read like enum access at call sites.

Exhaustive `switch` works at any level:

```java
String describe(ParadigmTag tag) {
    return switch (tag) {
        case SyncTag s        -> "synchronous";
        case AsyncTag a       -> "asynchronous (CompletionStage)";
        case ReactiveTag r    -> switch (r) {
            case ReactiveMonoTag m -> "reactive Mono";
            case ReactiveFluxTag f -> "reactive Flux";
        };
        case RxJava3Tag rx -> switch (rx) {
            case RxJava3SingleTag s      -> "rxjava3 Single";
            case RxJava3MaybeTag m       -> "rxjava3 Maybe";
            case RxJava3CompletableTag c -> "rxjava3 Completable";
            case RxJava3FlowableTag f    -> "rxjava3 Flowable";
            case RxJava3ObservableTag o  -> "rxjava3 Observable";
        };
        case CoroutinesTag c -> switch (c) {
            case CoroutinesSuspendTag s  -> "coroutine suspend fun";
            case CoroutinesDeferredTag d -> "coroutine Deferred";
            case CoroutinesJobTag j      -> "coroutine Job";
            case CoroutinesFlowTag f     -> "coroutine Flow";
        };
    };
}
```

Resilience elements parameterise their typed handles at the level
of specificity they need. `BulkheadHandle<ReactiveTag>` works for
both Mono and Flux. `RetryHandle<ReactiveMonoTag>` works only for
single-value reactive streams.

### 3. `AnnotationEvaluator` stamps each method's `MethodPlan` with its `ParadigmTag`

The evaluator is the single point in the library that already
walks every service-interface method, analyses its declaration,
and looks up annotations. Determining the method's paradigm from
its return type and parameter list is a natural extension of
work the evaluator already does. Every downstream consumer
(proxy today; aspect, function-style, Spring AOP in the future)
receives the paradigm as a stamped fact on the plan rather than
re-classifying.

`MethodPlan` evolves to carry the paradigm and element triples:

```java
public sealed interface MethodPlan {

    record PassThrough(ParadigmTag paradigm) implements MethodPlan {
        public PassThrough {
            Objects.requireNonNull(paradigm, "paradigm");
        }
    }

    record Decorated(
            ParadigmTag paradigm,
            List<ElementRef> elementsOuterToInner)
            implements MethodPlan {

        public Decorated {
            Objects.requireNonNull(paradigm, "paradigm");
            elementsOuterToInner = List.copyOf(elementsOuterToInner);
        }
    }
}

public record ElementRef(InqElementType elementType, String name) {
    public ElementRef {
        Objects.requireNonNull(elementType, "elementType");
        Objects.requireNonNull(name, "name");
    }
}
```

The paradigm is recorded **per method**, not per element. All
elements wrapping one method share the method's paradigm — a
sync method cannot be wrapped by a reactive bulkhead and an
async-imperative retry simultaneously; the chain folder picks
the matching contract on each layer from a single tag.

`PassThrough` also carries the paradigm: a method without
resilience annotations still has a paradigm, and consumers
(integration mechanisms) may need it for routing even when no
chain is folded.

`ElementRef` replaces nude element names. Each ref is the
`(elementType, name)` pair that combined with the method's
paradigm forms the full `(paradigm, type, name)` triple matching
`InqRuntime`'s registry key.

### 4. Paradigm classification by lazy class-loading probes

`AnnotationEvaluator` gains an internal collaborator
`ParadigmClassifier` that maps a `Method` to its `ParadigmTag`:

```java
package eu.inqudium.annotation.evaluator;

import eu.inqudium.core.element.*;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

final class ParadigmClassifier {

    private ParadigmClassifier() { }

    static ParadigmTag classify(Method method) {
        Class<?> returnType = method.getReturnType();

        Optional<ReactiveTag> reactive = Reactive.classify(returnType);
        if (reactive.isPresent()) {
            return reactive.get();
        }
        Optional<RxJava3Tag> rxjava3 = RxJava3.classify(returnType);
        if (rxjava3.isPresent()) {
            return rxjava3.get();
        }
        Optional<CoroutinesTag> coroutines = Coroutines.classify(method);
        if (coroutines.isPresent()) {
            return coroutines.get();
        }
        if (CompletionStage.class.isAssignableFrom(returnType)) {
            return AsyncTag.INSTANCE;
        }
        return SyncTag.INSTANCE;
    }
}
```

Each probe class returns the most specific sub-tag of its
paradigm family, or `Optional.empty()` if the method does not
belong to that paradigm:

```java
final class Reactive {

    private static final Optional<Class<?>> MONO_CLASS =
            loadType("reactor.core.publisher.Mono");
    private static final Optional<Class<?>> FLUX_CLASS =
            loadType("reactor.core.publisher.Flux");

    private Reactive() { }

    static Optional<ReactiveTag> classify(Class<?> returnType) {
        if (MONO_CLASS.map(c -> c.isAssignableFrom(returnType)).orElse(false)) {
            return Optional.of(ReactiveTag.MONO);
        }
        if (FLUX_CLASS.map(c -> c.isAssignableFrom(returnType)).orElse(false)) {
            return Optional.of(ReactiveTag.FLUX);
        }
        return Optional.empty();
    }

    private static Optional<Class<?>> loadType(String fqn) {
        try {
            return Optional.of(
                    Class.forName(fqn, false, Reactive.class.getClassLoader()));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }
}
```

`Coroutines` follows the same shape but inspects the method's
parameter list for `suspend`-function detection, and checks
return-type subtypes in **most-specific-first order** because
`Deferred<T> extends Job` in the Kotlin coroutines class
hierarchy:

```java
final class Coroutines {

    private static final Optional<Class<?>> CONTINUATION_CLASS =
            loadType("kotlin.coroutines.Continuation");
    private static final Optional<Class<?>> DEFERRED_CLASS =
            loadType("kotlinx.coroutines.Deferred");
    private static final Optional<Class<?>> JOB_CLASS =
            loadType("kotlinx.coroutines.Job");
    private static final Optional<Class<?>> FLOW_CLASS =
            loadType("kotlinx.coroutines.flow.Flow");

    private Coroutines() { }

    static Optional<CoroutinesTag> classify(Method method) {
        if (isSuspendFunction(method)) {
            return Optional.of(CoroutinesTag.SUSPEND);
        }

        Class<?> returnType = method.getReturnType();

        // Deferred is a subtype of Job — check Deferred first.
        if (DEFERRED_CLASS.map(c -> c.isAssignableFrom(returnType)).orElse(false)) {
            return Optional.of(CoroutinesTag.DEFERRED);
        }
        if (JOB_CLASS.map(c -> c.isAssignableFrom(returnType)).orElse(false)) {
            return Optional.of(CoroutinesTag.JOB);
        }
        if (FLOW_CLASS.map(c -> c.isAssignableFrom(returnType)).orElse(false)) {
            return Optional.of(CoroutinesTag.FLOW);
        }
        return Optional.empty();
    }

    private static boolean isSuspendFunction(Method method) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 0) {
            return false;
        }
        Class<?> lastParam = params[params.length - 1];
        return CONTINUATION_CLASS
                .map(c -> c.isAssignableFrom(lastParam))
                .orElse(false);
    }

    private static Optional<Class<?>> loadType(String fqn) {
        try {
            return Optional.of(
                    Class.forName(fqn, false, Coroutines.class.getClassLoader()));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }
}
```

`RxJava3` follows the same pattern with five reactive types
(`Single`, `Maybe`, `Completable`, `Flowable`, `Observable`).

`Optional<Class<?>>` makes the "external library not on the
classpath" case explicit. The probes never reference paradigm-
specific types by class literal; their bytecode is loadable on
any classpath. When the external library is absent, `classify(...)`
returns `Optional.empty()` and the next paradigm probe gets a
chance — no class-load-time crash, no runtime crash, no
special-case.

The classifier and probes are **package-private to
`eu.inqudium.annotation.evaluator`**. No SPI, no
`ServiceLoader`. Paradigm types are external library types plus
JDK types; the `Method` object plus optional classpath presence
of those external libraries is sufficient information. Lazy
class loading is the canonical Inqudium pattern, proven by
`DetectionAsync`, `DetectionProxy`, and `AsyncEntryBuilder`.

The package-private visibility preserves freedom to evolve the
classifier's API. If a future non-evaluator consumer ever needs
paradigm classification, the change to expose it then is small;
preemptively making it public locks in a design not yet
demonstrably needed.

### 5. Downstream consumers consume the stamped paradigm

`inqudium-proxy`'s `ElementResolver` no longer needs to assume
name-uniqueness. The resolver receives `List<ElementRef>` from
the plan and resolves each `(elementType, name)` pair against
the pipeline's elements directly — name collisions across
element types are inherently safe.

`inqudium-proxy`'s `ParadigmDetector.isAsyncMethod(Method)` is
removed; the proxy reads `plan.paradigm()` from the
`MethodPlan` instead. This eliminates duplicated classification
logic between the evaluator and the proxy.

Future integration consumers (aspect-based, function-style,
Spring AOP) consume the same stamped paradigm. None performs
paradigm classification independently.

## Consequences

### Positive

- **Single source of truth for paradigm.** The evaluator decides
  once. Every integration mechanism downstream consumes a
  stamped fact, not a re-classification.

- **Aligns library-wide.** `InqRuntime`'s
  `ComponentKey(name, paradigm)` is already paradigm-aware;
  `InqPipeline`'s `pipeline.elements()` is paradigm-blind. After
  this ADR, the entire integration path — evaluator → plan →
  proxy / aspect / function-style — carries paradigm
  consistently.

- **Multi-paradigm-ready.** When `inqudium-reactor`,
  `inqudium-rxjava3`, and `inqudium-coroutines` ship actual code,
  the proxy, the aspect, the function-style integration, and the
  Spring AOP integration do not change — they all already
  consume `plan.paradigm()`. Only the evaluator's probes
  activate (their `loadType` calls now succeed) and the
  classifier returns the appropriate sub-tag instead of the
  fallback.

- **Sub-shape distinctions preserved.** `ReactiveTag.MONO` vs.
  `ReactiveTag.FLUX`, the four `CoroutinesTag` variants, the
  five `RxJava3Tag` variants — all available to resilience
  elements that need them. Elements that want to behave
  uniformly across a family parameterise on the family tag
  (`Bulkhead<ReactiveTag>`); elements that need finer control
  parameterise on the concrete tag (`Retry<ReactiveMonoTag>`).

- **Pair-uniqueness becomes naturally type-safe.** The proxy's
  `ElementResolver` keys on `(elementType, name)`. Name
  collisions across element types are legal by construction.
  The latent crash mode currently documented as finding 1.1 in
  `REFACTORING_PROXY_POLISH.md` dissolves.

- **Eliminates duplication.** The proxy's
  `ParadigmDetector.isAsyncMethod(Method)` is no longer needed;
  the proxy reads `plan.paradigm()`. One fewer classification
  site; one fewer internal-API class.

### Negative

- **Breaking change to `MethodPlan`.** Public API since 0.8.0.
  `MethodPlan.Decorated.elementNamesOuterToInner` is replaced by
  `elementsOuterToInner` returning `List<ElementRef>`.
  `MethodPlan.PassThrough` gains a `paradigm` component. Direct
  consumers of these records must migrate. No external consumer
  is believed to construct these records directly — they are
  evaluator outputs — but the surface change is real.

- **`ImperativeTag` is replaced by two tags.**
  `BulkheadHandle<ImperativeTag>` becomes `BulkheadHandle<SyncTag>`
  or `BulkheadHandle<AsyncTag>`. Real internal consumers
  reference these (per repository search: ~6 call sites in
  `inqudium-config` and `inqudium-imperative`, plus test sites
  in integration examples). Each updates to the appropriate
  parameterised form.

- **`ParadigmTag` moves package.** Existing imports of
  `eu.inqudium.config.runtime.ParadigmTag` break. The move from
  `inqudium-config` to `inqudium-core` is mechanical
  (search-and-replace import) but touches multiple modules.

- **Larger tag surface than a flat enumeration.** Thirteen
  concrete tag classes (2 imperative + 2 reactive + 5 rxjava3 +
  4 coroutines) plus three sealed sub-family interfaces, against
  one current `ImperativeTag`. Each new tag class is short — an
  `INSTANCE` constant and a private constructor — but the file
  count grows. Mitigated by colocating tag families in
  per-paradigm packages under `eu.inqudium.core.element.paradigm`
  (one package per family).

- **Lazy class-loading probes added to `inqudium-annotation`.**
  Three probe classes (`Reactive`, `RxJava3`, `Coroutines`),
  each ~40 lines, plus `ParadigmClassifier` (~30 lines). The
  discipline-test pattern from `inqudium-proxy` extends
  naturally: a similar module-loading-discipline test in
  `inqudium-annotation` verifies that paradigm-specific classes
  (`reactor.core.publisher.Mono`, etc.) do not load on a
  classpath that excludes them.

### Risks

- **Custom paradigm types in user code.** A user wrapping
  `CompletionStage` in their own `MyAsyncResult<T>` that is NOT
  a `CompletionStage` subtype gets classified as `SyncTag`. This
  is the correct conservative default — the library's paradigm
  tags only recognise paradigms the library supports. Users
  wanting resilience for custom async types either use the
  library's supported types or contribute a new paradigm module
  with its own tag family and probe.

- **Generic erasure.** Classification reads
  `Method.getReturnType()` (raw type), which is correct:
  paradigm is a property of the raw return type. The generic
  parameter (`<T>` in `CompletionStage<T>`) carries no paradigm
  information.

- **Order-of-check sensitivity in Coroutines probe.**
  `Deferred<T> extends Job` in the Kotlin coroutines class
  hierarchy, so the `Deferred` check MUST precede the `Job`
  check; otherwise every `Deferred` classifies as `Job`. A unit
  test pins this ordering by exercising a `Deferred<String>`-
  returning method and asserting `CoroutinesTag.DEFERRED`, not
  `CoroutinesTag.JOB`.

- **Bytecode-verifier eager-loading concern (ADR-037 §6
  parallel).** The probes use `Class.forName(fqn, false,
  loader)` exactly as `DetectionAsync` does. No class literal of
  a paradigm-specific type appears in the probe bytecode; the
  probe class is loadable on any classpath. The discipline test
  recommended in the implementation plan empirically verifies
  this — `reactor.core.publisher.Mono` and the other paradigm
  types do not appear in any `BootstrapMethods` attribute of
  `inqudium-annotation` classes.

---

**Related ADRs:**
- ADR-036 (annotation model — defines `MethodPlan`)
- ADR-037 (module topology — class-loading discipline pattern,
  extended to `inqudium-annotation`)
- ADR-040 (pipeline composition model — pair-uniqueness invariant)

**Related findings:**
- `REFACTORING_PROXY_POLISH.md` sub-step P.3 (finding 1.1) —
  dissolved by this ADR's adoption.

**Implementation:**

The migration is complete (2026-05-18 to 2026-05-19). The
multi-PR plan document that drove the migration was deleted
in Q.8 once all sub-steps merged; the full timeline is
preserved in Git history (search for the file by its former
path at any commit before Q.8's deletion commit). Ten
sub-steps were merged as PRs #82, #83, #84, #85, #87, #88,
#89, #90, and #91. The implementation followed
the architectural decisions in §2–§5 of this ADR with two
notable evolutions:

- **Q.5a pre-emptively migrated the type-system layer** when
  a generic-bound conflict between the existing
  `BulkheadHandle<P>` parameter and the new `SyncTag`/
  `AsyncTag` types surfaced mid-implementation. The legacy
  `eu.inqudium.config.runtime.ParadigmTag` was deleted and
  `ImperativeTag` relocated to `inqudium-core` as part of
  the same PR, ahead of the Q.7 cleanup phase. The deviation
  is documented in PR #87.

- **Q.7.5 (added mid-plan)** converted all 13 leaf paradigm
  tags from `final class` to `sealed interface` declarations
  with package-private default implementations. The change
  brings the entire tag hierarchy into structural consistency
  and enables generic intersection bounds like
  `<P extends SyncTag & ReactiveTag>` that classes forbid.
  The structural choice supports future multi-paradigm
  resilience elements (e.g. a hypothetical `ReactiveBulkhead`
  serving both `Mono` and `Flux`).

Related dissolved finding: `REFACTORING_PROXY_POLISH.md`
finding 1.1 (`ElementResolver` name-uniqueness across element
types) was dissolved by Q.4's introduction of
`ElementResolver.resolveTriples(...)` — paradigm-tagged
plans key on `(elementType, name)` pairs, making name
collisions across element types safe by construction.
