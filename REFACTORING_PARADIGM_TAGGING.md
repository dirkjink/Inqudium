# REFACTORING_PARADIGM_TAGGING.md

**Status:** Draft
**Date:** 2026-05-18
**ADR reference:** ADR-046 (`docs/adr/046-paradigm-tagging.md`)

## Background

ADR-046 establishes:

- `ParadigmTag` lives in `inqudium-core` as a two-level sealed
  hierarchy: top-level `SyncTag`, `AsyncTag`, plus sealed
  sub-families `ReactiveTag` (2 sub-tags), `RxJava3Tag` (5
  sub-tags), `CoroutinesTag` (4 sub-tags). Thirteen concrete tag
  classes total.
- `AnnotationEvaluator` stamps each `MethodPlan` with the
  method's `ParadigmTag`, classified by lazy-class-loading
  probes in `inqudium-annotation`'s evaluator package.
- `MethodPlan.Decorated` carries the paradigm and a list of
  `ElementRef(elementType, name)` triples instead of nude
  element names.
- Downstream consumers (proxy today; aspect, function-style,
  Spring AOP later) consume `plan.paradigm()` directly; the
  proxy's `ParadigmDetector` becomes obsolete.

This plan sequences the work to implement ADR-046.

## Scope decisions

Three architectural decisions shape the scope:

### Classification is library-module-independent

Paradigm classification is a property of **external library
types**, not of Inqudium modules. The classifier in
`inqudium-annotation` stamps `ReactiveTag.MONO` when it sees
a method returning `reactor.core.publisher.Mono` — regardless
of whether `inqudium-reactor` is on the classpath. The same
applies to `RxJava3Tag`, `CoroutinesTag`, `AsyncTag`, and
`SyncTag`.

Result: `inqudium-annotation`'s lazy-class-loading probes look
for the external library types (`Mono`, `Flux`, `Single`,
`Continuation`, etc.). They never reference Inqudium modules.

### Element implementation is sync + async only

Only `SyncTag` and `AsyncTag` get real resilience-element
implementations in this plan, via the existing
`inqudium-imperative` module. `ReactiveTag`, `RxJava3Tag`, and
`CoroutinesTag` are classified by the evaluator but no
resilience element is implemented for them yet. The reactive,
rxjava3, and kotlin paradigm modules stay empty skeletons.

This is the right scope because:

- The library can already classify methods written in any of
  the four supported paradigms. The plumbing is in place.
- Adding a paradigm-specific element implementation (e.g., a
  reactive bulkhead) is independent feature work that can land
  in a future PR once a real use case drives it.
- Until then, attempting to wire a `@InqBulkhead` annotation
  on a `Mono`-returning method produces a clear, early failure
  rather than silent fallback (see below).

### Fail-fast on unsupported paradigm at proxy construction

When the proxy constructs a dispatcher for a method classified
as `ReactiveTag`, `RxJava3Tag`, or `CoroutinesTag`, **and** the
service has a resilience annotation, the proxy throws
`InqAnnotationConfigurationException` at construction time.

The exception message is specific: which method, which
paradigm, which element type was annotated, and a hint that
the paradigm's element implementation is not yet available.

A `PassThrough` plan (no resilience annotations on the method)
classifies cleanly regardless of paradigm and works fine —
the proxy passes the call through to the target unmodified.

This means the migration is **not blocked** by the absence of
reactive/rxjava3/coroutines elements: users who don't use those
paradigms don't notice; users who do, get a clear failure
indicating exactly what's missing.

## Working principle

**Each sub-step lands as its own PR**, reviewed and merged
before the next sub-step begins. Each sub-step:

1. Touches the smallest set of files needed.
2. Is mechanical enough that another engineer (or another Claude
   instance) can execute it from this plan.
3. Surfaces design questions to the maintainer before deviating.
4. Updates the completion log at the bottom of this document on
   merge (two-commit pattern: initial commit ticks with PR #TBD;
   follow-up commit replaces #TBD with the real PR number once
   the PR is opened).

## Out of scope

- Resilience-element implementations for reactive, rxjava3, and
  coroutines paradigms.
- Adding non-bulkhead elements (retry, circuit breaker, rate
  limiter, time limiter, traffic shaper) for any paradigm —
  the bulkhead serves as the lighthouse element that proves the
  sync+async wiring works end-to-end.
- ADR-039 full implementation (uniform stack introspection).
- The `chainId → stackId` library-wide rename (Option-B from
  proxy rewrite).

## Sub-steps

### Q.0 — Audit (no commit)

**Goal:** Establish the current state of every type, package,
and consumer site that the migration will touch.

**Tasks:**

1. Inventory every reference to `ImperativeTag` across the
   codebase, grouped by purpose (main code, tests, integration
   examples).
2. Inventory every reference to
   `eu.inqudium.config.runtime.ParadigmTag` across the codebase.
3. Confirm the current `MethodPlan` shape — exact record
   declarations, exact field types, exact public API.
4. Confirm the proxy's current paradigm-detection sites
   (`ParadigmDetector`, `DetectionAsync`, both folders).
5. Confirm `BulkheadHandle<P extends ParadigmTag>` and
   `ParadigmContainer<P extends ParadigmTag>` are the only
   library-internal types parameterised on `ParadigmTag`. List
   any others if present.
6. Confirm `inqudium-reactor`, `inqudium-rxjava3`, and
   `inqudium-kotlin` are empty module skeletons (only
   `.gitkeep` and pom.xml).

**Output:** `audit-Q.0-report.md` at repo root (transient,
untracked). Lists the locations grouped by which sub-step will
touch each.

**No commit.** Working document for Q.1+.

---

### Q.1 — New `ParadigmTag` hierarchy in `inqudium-core`

**Goal:** Land the new sealed-interface family from ADR-046 §2
as new types in `inqudium-core`, alongside the existing
`eu.inqudium.config.runtime.ParadigmTag`. No consumer changes
yet.

**Tasks:**

1. Create package `eu.inqudium.core.element.paradigm` in
   `inqudium-core/src/main/java`.

2. Add the top-level sealed interface:

   ```java
   package eu.inqudium.core.element.paradigm;

   public sealed interface ParadigmTag
           permits SyncTag, AsyncTag, ReactiveTag, RxJava3Tag, CoroutinesTag {
   }
   ```

3. Add `SyncTag` and `AsyncTag` as top-level final classes with
   private constructors and public `INSTANCE` constants.

4. Add `ReactiveTag` as a sealed sub-interface plus its two
   final implementations (`ReactiveMonoTag`, `ReactiveFluxTag`).
   Constants `MONO` and `FLUX` on the interface itself.

5. Add `RxJava3Tag` analogously with five concrete tags
   (`RxJava3SingleTag`, `RxJava3MaybeTag`,
   `RxJava3CompletableTag`, `RxJava3FlowableTag`,
   `RxJava3ObservableTag`). Constants `SINGLE`, `MAYBE`,
   `COMPLETABLE`, `FLOWABLE`, `OBSERVABLE`.

6. Add `CoroutinesTag` analogously with four concrete tags
   (`CoroutinesSuspendTag`, `CoroutinesDeferredTag`,
   `CoroutinesJobTag`, `CoroutinesFlowTag`). Constants
   `SUSPEND`, `DEFERRED`, `JOB`, `FLOW`.

7. Comprehensive `package-info.java` describing the hierarchy
   and the constants pattern.

8. Tests in `inqudium-core/src/test/java` exercise:
   - Each tag's `INSTANCE` is non-null.
   - Sealed permits matches the documented hierarchy (use
     `Class.getPermittedSubclasses()`).
   - Exhaustive `switch` over `ParadigmTag` compiles and works.
   - No public constructors anywhere (reflection check on each
     concrete tag class).

**Verification gates:**

- [ ] `mvn verify` green.
- [ ] 13 new concrete tag classes + 3 sealed sub-family
      interfaces + 1 top-level sealed interface in
      `eu.inqudium.core.element.paradigm`.
- [ ] Test count delta: +15 to +30 in `inqudium-core`.
- [ ] No reference to the existing
      `eu.inqudium.config.runtime.ParadigmTag` is touched. Both
      types coexist.

**Branch:** `feat/paradigm-tags-in-core`.

---

### Q.2 — `ParadigmClassifier` + lazy-class probes in `inqudium-annotation`

**Goal:** Implement the classifier from ADR-046 §4 as a
package-private collaborator of the evaluator.

The probes recognise the external library types
(`reactor.core.publisher.Mono`,
`io.reactivex.rxjava3.core.Single`,
`kotlin.coroutines.Continuation`, etc.). They are independent
of any Inqudium paradigm module being on the classpath —
classification works whenever the external library itself is
available.

**Tasks:**

1. Add a new package
   `eu.inqudium.annotation.evaluator` (or extend the existing
   one) for the classifier and probes.

2. Implement four probe classes per ADR-046 §4:
   - **`Reactive`** — probes `reactor.core.publisher.Mono` and
     `Flux`, returns `Optional<ReactiveTag>`.
   - **`RxJava3`** — probes the five RxJava 3 types
     (`io.reactivex.rxjava3.core.Single`, `Maybe`,
     `Completable`, `Flowable`, `Observable`), returns
     `Optional<RxJava3Tag>`. Order doesn't matter (no subtype
     relationships among these five at the relevant level).
   - **`Coroutines`** — probes `kotlin.coroutines.Continuation`
     (for suspend detection on parameter list),
     `kotlinx.coroutines.Deferred`, `kotlinx.coroutines.Job`,
     `kotlinx.coroutines.flow.Flow`. **Deferred is checked
     before Job** (subtype-relationship in Kotlin coroutines).
     Returns `Optional<CoroutinesTag>`.

3. Implement `ParadigmClassifier` with the fall-through ladder:
   Reactive → RxJava3 → Coroutines → CompletionStage → Sync.

4. Tests:
   - Each probe's positive cases (one test per supported
     sub-tag).
   - Each probe's negative cases (returns empty when the type
     doesn't match).
   - **Each probe behaves correctly when the external library
     is absent.** This is the critical contract: the probe
     class itself must load without exception on a classpath
     that omits the external library. Use a child classloader
     or URLClassLoader-isolation pattern to verify.
   - The classifier's fall-through ladder via fixture methods
     of each supported return type.
   - **Coroutines Deferred-before-Job ordering** pinned by an
     explicit test: a `Deferred<String>`-returning fixture
     classifies as `DEFERRED`, not `JOB`.
   - Custom user types fall through to `SyncTag`.
   - `void` classifies as `SyncTag`.
   - `CompletableFuture<Void>` classifies as `AsyncTag`.

5. **Module-loading-discipline test for `inqudium-annotation`**,
   modeled on `ModuleLoadingDisciplineTest` in `inqudium-proxy`.
   Verifies that loading `ParadigmClassifier` does NOT trigger
   loading of `reactor.core.publisher.Mono`,
   `io.reactivex.rxjava3.core.Single`,
   `kotlin.coroutines.Continuation`, etc. — the probes'
   lazy-class-loading discipline. Uses URLClassLoader-isolation
   per the proven pattern from proxy sub-step 3.13.

6. `inqudium-annotation/pom.xml`: add the paradigm-library
   dependencies as **test-scope, optional** so the probes can
   be exercised against real types in tests but the production
   classpath of `inqudium-annotation` remains free of them.

   Specifically: `reactor-core`, `rxjava` (RxJava 3),
   `kotlinx-coroutines-core`. All `scope=test, optional=true`.

**Verification gates:**

- [ ] `mvn verify` green.
- [ ] `ParadigmClassifier` and probes are package-private.
- [ ] Discipline test passes — none of the four external
      paradigm libraries' classes load when constructing the
      classifier on a sync-only classpath.
- [ ] Test count delta: +30 to +50 in `inqudium-annotation`.
- [ ] No reference to `MethodPlan` is changed yet (still the
      legacy shape).

**Branch:** `feat/paradigm-classifier`.

---

### Q.3 — `ElementRef` + new `MethodPlan` shape, parallel to old

**Goal:** Add the new `MethodPlan.Decorated` and
`MethodPlan.PassThrough` shapes carrying `ParadigmTag` and
`ElementRef`, **alongside** the existing shapes. The evaluator
exposes both shapes via two parallel methods so consumers can
migrate independently.

**Tasks:**

1. Add `ElementRef` record to `inqudium-annotation/evaluator`:

   ```java
   public record ElementRef(InqElementType elementType, String name) {
       public ElementRef {
           Objects.requireNonNull(elementType, "elementType");
           Objects.requireNonNull(name, "name");
       }
   }
   ```

2. Extend the `MethodPlan` sealed family with **new permits**
   that carry the paradigm + ElementRef shape:

   ```java
   public sealed interface MethodPlan permits
           PassThrough, Decorated,           // legacy
           StampedPassThrough, StampedDecorated {  // new

       // Legacy permits unchanged

       record StampedPassThrough(ParadigmTag paradigm) implements MethodPlan {
           public StampedPassThrough {
               Objects.requireNonNull(paradigm, "paradigm");
           }
       }

       record StampedDecorated(
               ParadigmTag paradigm,
               List<ElementRef> elementsOuterToInner)
               implements MethodPlan {

           public StampedDecorated {
               Objects.requireNonNull(paradigm, "paradigm");
               elementsOuterToInner = List.copyOf(elementsOuterToInner);
           }
       }
   }
   ```

   The temporary `Stamped*` naming is intentional — it signals
   "this is the new shape, the old one is going away" without
   forcing a rename across all consumers in this step. Q.6's
   rename cleanly replaces `Stamped*` with `PassThrough` /
   `Decorated` when the old permits are removed.

3. Update the evaluator to expose a new method
   `evaluateStamped(Class<?> serviceInterface, Class<?> implClass)`
   alongside the existing `evaluate(...)`. The new method
   returns the `Stamped*` plan shape, calling
   `ParadigmClassifier.classify(method)` per method.

   Both methods coexist; nothing is removed yet. The new
   method's body is a slim adaptation of the old method's body
   that additionally calls the classifier and packs the
   `(paradigm, List<ElementRef>)` into the new record.

4. Tests for the new evaluator method:
   - **All four broad paradigms exercised end-to-end:** sync,
     async, reactive, coroutine return types each produce the
     expected `Stamped*` plan with the matching paradigm.
   - `ElementRef` list contents match the old shape's name
     list, with element types correctly resolved.
   - `PassThrough` methods still produce `StampedPassThrough`
     with paradigm stamped.

**Verification gates:**

- [ ] `mvn verify` green.
- [ ] Both `evaluate(...)` and `evaluateStamped(...)` work.
- [ ] All existing tests pass — legacy plan shape unchanged.
- [ ] Test count delta: +20 to +40 in `inqudium-annotation`.

**Branch:** `feat/method-plan-stamped-shape`.

---

### Q.4 — Proxy consumes the new plan shape

**Goal:** Migrate `inqudium-proxy` to consume `StampedDecorated`
and `StampedPassThrough` from the evaluator. The proxy is the
single largest consumer of the plan; this sub-step demonstrates
the migration pattern. `ParadigmDetector` is removed.

For methods classified as `ReactiveTag`, `RxJava3Tag`, or
`CoroutinesTag` AND carrying a resilience annotation, the proxy
fails fast at construction time with a specific error. Methods
without annotations (yielding `StampedPassThrough`) are handled
correctly for all paradigms — the proxy just passes through.

**Tasks:**

1. `ProxyBuilder.build(...)` calls
   `evaluator.evaluateStamped(...)` instead of `evaluate(...)`.

2. `MethodDispatchEntryFactory.createEntry(...)` switches on
   the new plan permits (`StampedPassThrough`, `StampedDecorated`).
   The dispatch decision (sync chain vs. async chain vs.
   pass-through) is driven by `plan.paradigm()` instead of
   `ParadigmDetector.isAsyncMethod(method)`.

3. **For `StampedDecorated` with `ReactiveTag`, `RxJava3Tag`,
   or `CoroutinesTag`:** throw
   `InqAnnotationConfigurationException` at construction time
   with a specific message:

   ```
   Method <ClassName>#<methodName>(...) is classified as <paradigmTag>
   but the resilience-element implementation for this paradigm is
   not yet available. The library currently implements resilience
   elements only for the sync (SyncTag) and async (AsyncTag)
   paradigms. Either remove the resilience annotation(s) from this
   method, or restrict the method's return type to a sync or async
   imperative shape.
   ```

   This branch lives in `MethodDispatchEntryFactory` next to
   the sync/async decision; an exhaustive `switch` over the
   sealed `ParadigmTag` family forces every paradigm to be
   handled.

4. **For `StampedPassThrough` of any paradigm:** the existing
   `PassThroughEntry` dispatch works — the method just calls
   the target. No paradigm-specific handling needed.

5. **Remove `ParadigmDetector` entirely.** It's the only
   purpose-built classification site in the proxy; the
   evaluator now owns paradigm classification.

6. `ElementResolver`:
   - New API method
     `resolveTriples(List<ElementRef>, InqPipeline)` keying on
     `(elementType, name)` instead of `name` alone.
   - Old `resolveNames(...)` stays for now — `inqudium-proxy`'s
     internal callers shift to the new method; nothing external
     depends on `resolveNames(...)`.

7. **Finding 1.1 (`ElementResolver` `Collectors.toMap` crash)
   dissolves naturally.** The new method resolves
   `(elementType, name)` pairs; duplicate names across element
   types are inherently safe. Add a regression test that pins
   this: a pipeline with two same-named elements of different
   types resolves cleanly.

8. **`ARCHITECTURE.md` §11 update:** the paradigm-classification
   section is rewritten to describe the new flow (evaluator
   stamps → plan carries → proxy consumes). `ParadigmDetector`
   removed from the package listing. Add a paragraph
   documenting the fail-fast behaviour for unsupported
   paradigms.

9. **Tests:**
   - Existing proxy tests pass unmodified.
   - New test asserting fail-fast for a `Mono`-returning
     annotated method.
   - New test asserting fail-fast for a `Single`-returning
     annotated method.
   - New test asserting fail-fast for a `Deferred`-returning
     annotated method.
   - New test asserting `Mono`-returning **unannotated**
     method goes through pass-through cleanly.
   - Regression test for duplicate names across element types.

**Verification gates:**

- [ ] `mvn verify` green.
- [ ] `ParadigmDetector` is gone (`grep -r ParadigmDetector
      inqudium-proxy/src` returns empty).
- [ ] `ElementResolver.resolveTriples(...)` keyed on
      `(elementType, name)` pair.
- [ ] Fail-fast tests pass for all three non-imperative
      paradigms with annotation.
- [ ] Pass-through tests pass for all three non-imperative
      paradigms without annotation.
- [ ] Regression test for duplicate names across element types
      passes.
- [ ] Test count delta: +10 to +20 in `inqudium-proxy`.
- [ ] **`REFACTORING_PROXY_POLISH.md` finding 1.1 is now
      annotated as "dissolved by ADR-046 / PARADIGM_TAGGING
      Q.4".**

**Branch:** `feat/proxy-consume-stamped-plan`.

---

### Q.5a — Runtime: `Sync` / `Async` accessors and typed handles

**Goal:** Land the `Sync` and `Async` paradigm-container
interfaces in `inqudium-config`, add corresponding
`InqRuntime.sync()` / `runtime.async()` accessors, and split
`inqudium-imperative`'s `DefaultImperative` into a pair of
typed views that share a single backing `InqBulkhead` instance
per name. `Imperative` and `runtime.imperative()` remain as
deprecated aliases until Q.7's cleanup.

**Tasks:**

1. **`inqudium-config/runtime`** — two new interfaces:

   ```java
   public interface Sync extends ParadigmContainer<SyncTag> {
       BulkheadHandle<SyncTag> bulkhead(String name);
       Optional<BulkheadHandle<SyncTag>> findBulkhead(String name);
       Set<String> bulkheadNames();
   }

   public interface Async extends ParadigmContainer<AsyncTag> {
       BulkheadHandle<AsyncTag> bulkhead(String name);
       Optional<BulkheadHandle<AsyncTag>> findBulkhead(String name);
       Set<String> bulkheadNames();
   }
   ```

2. **`InqRuntime`** gains `sync()` and `async()` accessor
   methods. `imperative()` is annotated `@Deprecated` and
   documented as delegating to `sync()`; left in place for
   one release cycle.

3. **`DefaultInqRuntime`** — the containers Map gains
   `SyncTag.INSTANCE` and `AsyncTag.INSTANCE` keys.
   `imperative()` delegates to `sync()`. The
   `ImperativeTag.INSTANCE` key is retained alongside (Q.7
   removes it), so a runtime built via the legacy path still
   works.

4. **`inqudium-imperative`** — replace `DefaultImperative`
   with `DefaultSync` and `DefaultAsync`, each backed by the
   **same underlying** `InqBulkhead<A, R>` instance map.
   Two typed views over one registry. The bulkhead instance
   already implements both `InqDecorator` and
   `InqAsyncDecorator`; the views just project the right
   typed handle.

5. **`ComponentKey`** — update to use the new `ParadigmTag`
   from `inqudium-core`. Imports flip across approximately
   six call sites in `inqudium-config` and
   `inqudium-imperative` (Audit Q.0). The legacy
   `eu.inqudium.config.runtime.ParadigmTag` is retained as a
   deprecated alias / re-export until Q.7.

6. Tests:
   - `runtime.sync().bulkhead("foo")` returns a typed
     `BulkheadHandle<SyncTag>`.
   - `runtime.async().bulkhead("foo")` returns a typed
     `BulkheadHandle<AsyncTag>` over the **same backing
     instance** (verify by identity check on the underlying
     `InqBulkhead`).
   - Deprecated `runtime.imperative()` still works, returns
     the sync view.
   - Runtime updates propagate to both views (tune one,
     observe the change via the other).

**Verification gates:**

- [ ] `mvn -pl inqudium-imperative -am verify` green.
- [ ] Full `mvn verify` green.
- [ ] `runtime.sync()` and `runtime.async()` work.
- [ ] `runtime.imperative()` still works (deprecated).
- [ ] Test count delta: +15 to +25 in `inqudium-config` +
      `inqudium-imperative`.
- [ ] No new `@SuppressWarnings` introduced.
- [ ] Audit baselines unchanged: 126 `ImperativeTag` hits,
      44 legacy `ParadigmTag` hits — Q.5a does not yet remove
      any.

**Branch:** `feat/sync-async-runtime-accessors`.

---

### Q.5b — DSL + Integration Examples migrate to `sync()` / `async()`

**Goal:** Migrate the user-facing DSL (`BulkheadBuilder<P>`,
`BulkheadBuilderBase<P>`, builder hierarchy) to expose paradigm
choice via `.sync(...)` / `.async(...)` rather than
`.imperative(...)`. Migrate both bulkhead-integration examples
(`inqudium-bulkhead-integration-proxy`,
`inqudium-bulkhead-integration-function`) to demonstrate
end-to-end usage of both paradigms. Establish end-to-end
coverage of async-imperative dispatch via an annotated
`CompletionStage<T>` method.

**Tasks:**

1. **`inqudium-config/dsl`** — the builder hierarchy.
   `BulkheadBuilder<P extends ParadigmTag>` and
   `BulkheadBuilderBase<P extends ParadigmTag>` stay
   parameterised, but the user-facing entry points become
   `.sync(s -> s.bulkhead("foo", b -> ...))` and
   `.async(a -> a.bulkhead("foo", b -> ...))`.

   Approximately five files in the DSL package will be
   touched (per Q.0 Audit finding 1 — surface them now):

   - `BulkheadBuilder.java` — parameter-update
   - `BulkheadBuilderBase.java` — parameter-update
   - `ImperativeBulkheadBuilder.java` — split into
     `SyncBulkheadBuilder` + `AsyncBulkheadBuilder`, with
     `ImperativeBulkheadBuilder` as deprecated alias
   - `DefaultImperativeBulkheadBuilder.java` — split
     analogously
   - `DefaultInqudiumBuilder.java` and
     `DefaultInqudiumUpdateBuilder.java` — gain `.sync(...)`
     and `.async(...)` entry methods; `.imperative(...)`
     deprecated alias to `.sync(...)`

2. **`inqudium-bulkhead-integration-proxy`** — migrate test
   code from `runtime.imperative()` to `runtime.sync()` /
   `runtime.async()`. The example may need to demonstrate
   both paradigms in a single end-to-end run; the
   `OrderService` interface can grow an
   `placeOrderAsync()`-returning `CompletionStage<String>`
   method protected by the same `@InqBulkhead("orderBh")`
   annotation — both views (`sync` and `async`) hand back
   handles to the **same backing bulkhead**.

3. **`inqudium-bulkhead-integration-function`** — analogous
   migration. Per Q.0 Audit finding 3, this example was not
   in the original Q.5 plan but uses the same patterns.

4. **End-to-end test for async-imperative dispatch.**
   A new test in one of the two examples (preferably
   `inqudium-bulkhead-integration-proxy`) exercises the full
   path: proxy → stamped plan → AsyncTag → async chain →
   permit release on stage completion. The test confirms:
   - A `@InqBulkhead`-annotated method returning
     `CompletionStage<T>` works without code change to the
     proxy (Q.4 wired this).
   - Permit-release happens at stage completion, not at
     method return — verify by saturating, attempting a
     blocked subscribe, completing the in-flight stage, and
     observing the previously-blocked attempt succeed.

5. Test updates in `inqudium-config` for the DSL builder
   parameter-types. Existing tests that use the
   `ImperativeBulkheadBuilder` form via the deprecated path
   continue to work — backward compatibility is the
   migration's promise.

**Verification gates:**

- [ ] `mvn verify` green (full reactor — both examples plus
      core/config/imperative).
- [ ] User-facing API: `Inqudium.configure().sync(...)`,
      `.async(...)`, plus deprecated `.imperative(...)` all
      work.
- [ ] End-to-end async-imperative test passes — annotated
      `CompletionStage<T>` method correctly limits concurrency.
- [ ] Test count delta: +20 to +40 across
      `inqudium-config`, both integration examples, and
      `inqudium-imperative`.
- [ ] No new `@SuppressWarnings` introduced.
- [ ] Audit baselines: `ImperativeTag` hits **decrease**
      (the DSL builders previously had `<ImperativeTag>`
      parameters that now flip to `<SyncTag>` /
      `<AsyncTag>`; the integration examples previously had
      `BulkheadHandle<ImperativeTag>` declarations that flip
      similarly). The legacy `ImperativeTag` type itself
      remains in place until Q.7; only the parameter use
      shifts. Expect the count to drop by approximately
      15-30, not to zero.

**Branch:** `feat/sync-async-dsl-and-examples`.

---

### Q.6 — `MethodPlan` rename: `Stamped*` becomes the canonical names

**Goal:** After Q.4 migrated the proxy and Q.5a/Q.5b added the
async-imperative path, **every internal consumer uses the
`Stamped*` plan shape**. This sub-step renames `Stamped*` back
to `PassThrough` / `Decorated`, removing the temporary
disambiguation naming.

**Tasks:**

1. Remove the legacy permits `PassThrough(...)` and
   `Decorated(List<String>)` from `MethodPlan`.

2. Rename `StampedPassThrough` → `PassThrough` and
   `StampedDecorated` → `Decorated`. The new records (now
   under the original names) carry the paradigm and
   `List<ElementRef>`.

3. The evaluator's `evaluate(...)` method is removed; only the
   new method remains, renamed from `evaluateStamped(...)` to
   `evaluate(...)`.

4. Update every internal consumer's import + call site.
   Mechanical search-and-replace. Audit-Q.0's inventory is the
   reference list.

5. Tests: the existing test surface continues to pass after
   the rename — semantically nothing changed, only names. If
   any test asserts on the literal `"StampedDecorated"` or
   `"evaluateStamped"` string, update it.

**Verification gates:**

- [ ] `mvn verify` green.
- [ ] `grep -rn "Stamped" inqudium-annotation/` returns empty.
- [ ] `grep -rn "evaluateStamped" inqudium-*/` returns empty.
- [ ] Test count delta: 0.

**Branch:** `refactor/method-plan-rename-stamped`.

---

### Q.7 — Final cleanup: remove legacy `ParadigmTag` and `ImperativeTag`

**Goal:** Drop the legacy `eu.inqudium.config.runtime.ParadigmTag`
and `ImperativeTag` types now that nothing references them
except the deprecated aliases. Remove the deprecated
`imperative()` accessor on `InqRuntime`.

**Tasks:**

1. **Remove** `eu.inqudium.config.runtime.ImperativeTag.java`.

2. **Remove** the deprecated `Imperative` interface
   (`eu.inqudium.config.runtime.Imperative.java`).

3. **Remove** `InqRuntime.imperative()` method.

4. **Remove** the deprecated alias / re-export at
   `eu.inqudium.config.runtime.ParadigmTag`. Any remaining
   internal reference falls back to importing the new location
   in `inqudium-core`.

5. Final grep: zero references to the deprecated types or
   methods anywhere in the repository.

6. **Update ADRs:**
   - ADR-046 status `Proposed` → `Accepted`. Add an
     "Implementation status" section listing the realising
     artefacts (the classifier in `inqudium-annotation`, the
     stamped plan, the proxy's consumption, the
     `sync()`/`async()` accessors in `inqudium-imperative`).
   - ADR-004 (`004-native-per-paradigm.md`) review — it likely
     describes the per-paradigm-module pattern; check for
     references that need updating now that we have two
     paradigm tags inside `inqudium-imperative` instead of one.

7. **Update `REFACTORING_PROXY_POLISH.md`:** mark finding 1.1
   as **resolved** by ADR-046's implementation. Sub-step P.3
   marked as `[x]` (resolved through ADR-046, not through any
   of P.3's original three options).

**Verification gates:**

- [ ] `mvn verify` green.
- [ ] `grep -rn ImperativeTag` returns empty across the entire
      repo.
- [ ] `grep -rn "config.runtime.ParadigmTag"` returns empty.
- [ ] `grep -rn "runtime.imperative()"` returns empty (only
      `sync()` and `async()` remain).
- [ ] ADR-046 status is `Accepted`.

**Branch:** `cleanup/remove-legacy-paradigm-tags`.

---

### Q.8 — Plan deletion + final consistency

**Goal:** After all sub-steps merge, delete this plan
document. Sanity-check `ARCHITECTURE.md` files and ADRs for
any remaining drift.

**Tasks:**

1. Verify all Q.0 through Q.7 are merged.
2. Delete `REFACTORING_PARADIGM_TAGGING.md`.
3. Final consistency scan: `grep -rn
   REFACTORING_PARADIGM_TAGGING` returns empty across the repo.
4. If `inqudium-proxy/docs/ARCHITECTURE.md` mentions
   `ParadigmDetector`, remove the mention (the class is gone
   since Q.4).

**Verification gates:**

- [ ] `REFACTORING_PARADIGM_TAGGING.md` not in working tree.
- [ ] No grep hits for it in the codebase.

**Branch:** `chore/paradigm-tagging-plan-deletion`.

---

## Completion log

* [x] Q.0 — Audit (no commit) (2026-05-18)
* [x] Q.1 — New `ParadigmTag` hierarchy in `inqudium-core` (2026-05-18, PR #82)
* [x] Q.2 — `ParadigmClassifier` + lazy-class probes (2026-05-18, PR #83)
* [x] Q.3 — `ElementRef` + new `MethodPlan` shape (parallel) (2026-05-18, PR #84)
* [x] Q.4 — Proxy consumes the new plan shape (2026-05-18, PR #85)
* [x] Q.5a — Runtime: `Sync` / `Async` accessors and typed handles (2026-05-18, PR #87)
* [x] Q.5b — DSL + Integration Examples migrate to `sync()` / `async()` (2026-05-19, PR #TBD)
* [ ] Q.6 — `MethodPlan` rename: `Stamped*` → canonical names
* [ ] Q.7 — Final cleanup: remove legacy types
* [ ] Q.8 — Plan deletion + final consistency

---

## Estimated effort

Rough numbers for someone familiar with the codebase:

| Sub-step | Effort |
|---|---|
| Q.0 | 30 minutes |
| Q.1 | half a day (lots of small files, comprehensive tests) |
| Q.2 | 1 day (classifier + 3 probes + discipline test) |
| Q.3 | half a day |
| Q.4 | 1 day (proxy migration + ParadigmDetector removal + tests) |
| Q.5a | 1 day (runtime API + DefaultImperative split) |
| Q.5b | 1 day (DSL + 2 integration examples + e2e tests) |
| Q.6 | 1 hour (mechanical rename) |
| Q.7 | 2 hours (cleanup + ADR updates) |
| Q.8 | 15 minutes |

Total range: about **6-8 days** for the full sequence at a
sustainable pace.

## When unsure

Search the code first. The audit report from Q.0 is the source
of truth for current locations. When the audit and this plan
disagree, the audit wins (it's based on current HEAD; the plan
was written 2026-05-18).
