# Refactoring plan: ADR-039 implementation (Phase B)

**Plan reference:** Phase B of the two-phase post-polish
refactor sequence. Phase A (legacy proxy stack removal +
component/handle separation) completed 2026-05-19
(PRs #98 — #104).

**Goal:** Implement ADR-039's "Uniform stack introspection
across wrapping paradigms" deferred work. After Phase B:

- The library-wide `chainId` → `stackId` rename is
  complete.
- `InqStackInfo` sealed hierarchy and DTOs live in
  `inqudium-pipeline`. `FunctionStackInfo` exists as
  an empty permit; its adapter is deferred until
  Function-Dispatch-Integration lands (see B.3 deferral
  notice).
- `InqIntrospector` and `InqStackRenderer` exist as
  central dispatch utilities in `inqudium-pipeline`,
  dispatching to `ProxyStackAdapter` (the only
  implemented adapter for Phase B).
- The Phase-A bridge state (legacy
  `eu.inqudium.core.pipeline.InqPipeline`,
  `AnnotationEvaluator`, `DefaultAnnotationEvaluator`,
  `InqPipelineAnnotationEvaluator`) is resolved.
- ADR-039 status flips from `Proposed` to `Accepted`.

**Status:** Audit completed 2026-05-19. Plan approved
by maintainer (option (a): bare-bones Phase B + bridge
resolution; aspect/spring adapters anticipated as future
work).

## Background

### Why bare-bones Phase B

ADR-039 lists four `InqStackInfo` permits and four
adapters in its "Deferred" section. Two of them —
`AspectJStackInfo` / `AspectJStackAdapter` and
`SpringAspectStackInfo` / `SpringAspectStackAdapter` —
target modules that are **stubbed** post-Phase-A
(`inqudium-aspect` and `inqudium-spring` contain only
`package-info.java` markers after PR #98).

Two implementation strategies were considered:

- **(b) Full Phase B** — rebuild `inqudium-aspect` and
  `inqudium-spring` as part of Phase B, implement all
  four adapters. Estimated 6-10 days. Substantial scope
  expansion beyond ADR-039.

- **(a) Bare-bones Phase B** — implement the adapter
  whose source module exists today (`ProxyStackAdapter`
  already lives in `inqudium-proxy`). Document
  `FunctionStackAdapter`, AspectJ, and Spring adapters
  as anticipated when their respective integration
  infrastructure exists. Estimated 2-3 days.
  *(Note: at planning time, `FunctionStackAdapter` was
  also assumed buildable against `inqudium-core`'s
  function-wrapper family. A B.3 pre-audit revealed
  this assumption was wrong — see the B.3 deferral
  notice below.)*

**Maintainer's choice: (a).** Aspect/Spring rebuild is
an independent refactor with its own scope; coupling it
to ADR-039 conflates two concerns. ADR-039's "Accepted"
status documents the architecture as it is achievable
today; future work expands the adapter set when the
underlying modules are ready.

### What's in scope of Phase B

1. **chainId → stackId rename** library-wide
2. **InqStackInfo sealed hierarchy** in `inqudium-pipeline`
   (with `FunctionStackInfo` as an empty future permit)
3. **InqIntrospector** central dispatch in
   `inqudium-pipeline` (dispatches to
   `ProxyStackAdapter` only for Phase B)
4. **InqStackRenderer** (toTree, toJson) in
   `inqudium-pipeline`
5. **Bridge-state resolution**: replace
   `InqPipelineAnnotationEvaluator` transitional bridge
   with a new `AnnotationEvaluator` that works directly
   against `eu.inqudium.pipeline.InqPipeline`. Delete the
   four legacy bridge files.
6. **ADR-039 status flip** Proposed → Accepted with
   anticipated-future-work note for AspectJ/Spring
   adapters and for `FunctionStackAdapter` (deferred
   pending Function-Dispatch-Integration).

### What's out of scope

- **AspectJStackAdapter and SpringAspectStackAdapter** —
  anticipated when `inqudium-aspect` and `inqudium-spring`
  modules are rebuilt (separate refactor).
- **DetectionAspectJ and DetectionSpringAop probes** —
  same reason.
- **SerializedLambda tier-2 method resolution** — ADR-039
  lists this as deferred future enhancement; not part of
  the Accepted-promotion path.
- **Top-level legacy `Bulkhead.java` and
  `ImperativeBulkhead.java`** — separate "legacy
  resilience surface" refactor.

## Audit findings

Recorded here once, referenced throughout the sub-steps.

### B.0.1 Existing implementation (already on main)

In `inqudium-proxy/src/main/java/eu/inqudium/proxy/introspection/`:

```
MethodLayers.java
MethodSignatureFormatter.java
ProxyStackAdapter.java
ProxyStackInfo.java
package-info.java
```

These were built during the proxy rewrite per ADR-039
sub-step 3.12. They are functional, tested, and remain
in place. Phase B work integrates them with the new
sealed hierarchy and migrates them to `inqudium-pipeline`
where the central introspector lives.

### B.0.2 chainId → stackId rename scope

**Verified 2026-05-19:**

- 72 files with `chainId` references (main + test)
- 327 total occurrences
- Per-module main-source distribution:
  - `inqudium-core`: 21 main files
  - `inqudium-imperative`: 13 main files
  - `inqudium-config`: 5 main files
  - `inqudium-proxy`: 2 main files

**Substantially smaller than Phase-A audit estimate
(769 occurrences in 131 files).** Reason: Phase A's
~160-file legacy-stack deletion removed much of the
chainId-bearing code.

Notable callers:
- `PipelineIds.nextChainId()` → `nextStackId()`
- `AbstractBaseWrapper.chainId()` → `stackId()`
- Bulkhead event constructors (`BulkheadOnAcquireEvent`,
  `BulkheadOnRejectEvent`, `BulkheadOnReleaseEvent`,
  `BulkheadWaitTraceEvent`, `BulkheadRollbackTraceEvent`)
- Exception constructors (`InqRuntimeException`,
  `BulkheadEventPublishFailureException`,
  `InqBulkheadInterruptedException`,
  `InqBulkheadFullException`)
- Various test files

### B.0.3 Bridge-state files (Phase A heritage)

Four files that Phase A preserved as transitional bridge,
to be deleted in B.5:

- `inqudium-core/.../pipeline/InqPipeline.java` (legacy
  concrete class, 387 LOC)
- `inqudium-annotation/.../evaluator/AnnotationEvaluator.java`
- `inqudium-annotation/.../evaluator/DefaultAnnotationEvaluator.java`
- `inqudium-pipeline/.../InqPipelineAnnotationEvaluator.java`
  (transitional bridge)

**Current production importer:**
`inqudium-proxy/.../ProxyBuilder.java` references
`InqPipelineAnnotationEvaluator`. ProxyBuilder will be
migrated to use the new AnnotationEvaluator in B.5.

### B.0.4 Module dependency direction

After Phase A's clarifications:

```
inqudium-core (foundation)
    ↑
inqudium-config (capability interfaces)
    ↑
inqudium-imperative (sync/async paradigm)
    ↑
inqudium-pipeline (interface module + dispatch)
    ↑
inqudium-proxy (proxy integration)
```

**Phase B introduces no new module-dependency edges.**
`InqStackInfo`, `InqIntrospector`, `InqStackRenderer`
all live in `inqudium-pipeline`. `FunctionStackAdapter`
is deferred (see B.3); when implemented, its placement
follows Function-Dispatch-Integration's module location.

### B.0.5 Baselines

Current main, verified 2026-05-19 (post-PR #104):

- `@SuppressWarnings`: 42 main / 128 total
- 22 modules
- `mvn verify` green
- 0 reverted PRs across Phase A and component/handle
  separation

## Sub-step structure

Phase B is divided into seven sub-steps. Each sub-step
is one PR. Execution order matters: the chainId rename
goes first so subsequent work uses the new vocabulary;
introspection comes before bridge resolution because the
new AnnotationEvaluator references the new pipeline
interface.

```
B.0  — Audit (this document). No commit.
B.1  — chainId → stackId rename library-wide
B.2  — InqStackInfo sealed hierarchy + DTO migration
B.3  — FunctionStackAdapter (deferred — see section
       below for rationale)
B.4  — InqIntrospector + InqStackRenderer
B.5  — Bridge-state resolution (new AnnotationEvaluator,
       delete 4 legacy files)
B.6  — ADR-039 status flip + final cleanup
```

The original plan committal will be a separate Plan-PR
(analog to Phase A) before B.1 starts.

## Sub-step B.1 — chainId → stackId rename

**Goal:** Rename `chainId` to `stackId` everywhere it
appears — code, Javadoc, comments, test names. After
B.1, no `chainId` references remain in the library.

**Approach:** Mechanical sed-pass library-wide, then
per-module test run, then full reactor verification.

**Affected:**
- 21 files in inqudium-core
- 13 files in inqudium-imperative
- 5 files in inqudium-config
- 2 files in inqudium-proxy
- ~30 test files (count at B.1 start)

**Sed patterns (preliminary — refine at B.1 start):**

```bash
# Method names
sed -i 's/\bchainId\b/stackId/g'
sed -i 's/\bnextChainId\b/nextStackId/g'

# Parameter names (careful — verify each)
sed -i 's/long chainId,/long stackId,/g'
sed -i 's/long chainId)/long stackId)/g'

# Javadoc / comments
sed -i 's/chain ID/stack ID/g'
sed -i 's/chain-ID/stack-ID/g'
sed -i 's/chain identifier/stack identifier/g'
```

**Pause-and-ask** if sed matches turn up edge cases not
anticipated (e.g. `chainId` inside string literals that
should not be renamed, or comments that legitimately
discuss "the original chainId design").

**Verification gates:**

- [ ] `mvn verify` green across 22 modules.
- [ ] `git grep -l '\bchainId\b\|chain_id\|chainID' inqudium-*/src` returns empty.
- [ ] `git grep -l '\bnextChainId\b' inqudium-*/src` returns empty.
- [ ] `@SuppressWarnings` baseline unchanged.

**Estimated effort:** 3-4 hours (rename is mostly
mechanical; test verification is the time sink).

**Branch:** `refactor/chainid-to-stackid-rename`.

## Sub-step B.2 — InqStackInfo sealed hierarchy + DTO migration

**Goal:** Create the sealed `InqStackInfo` interface in
`inqudium-pipeline` plus two concrete permits
(`FunctionStackInfo`, `ProxyStackInfo`). Migrate
`MethodLayers` and `ProxyStackInfo` from
`inqudium-proxy/.../introspection/` to
`inqudium-pipeline/.../introspection/`.

**New files:**

- `inqudium-pipeline/.../introspection/InqStackInfo.java`
  (sealed interface, two permits)
- `inqudium-pipeline/.../introspection/FunctionStackInfo.java`
  (record, implements InqStackInfo)
- `inqudium-pipeline/.../introspection/MethodLayers.java`
  (record, migrated from inqudium-proxy)

**Modified files:**

- `inqudium-pipeline/.../introspection/ProxyStackInfo.java`
  (migrated from inqudium-proxy; now implements
  InqStackInfo with the new sealed declaration)
- Whatever imports `ProxyStackInfo` from
  `inqudium-proxy.introspection` — updated to point at
  `inqudium-pipeline.introspection`

**Deleted files:**

- `inqudium-proxy/.../introspection/ProxyStackInfo.java`
- `inqudium-proxy/.../introspection/MethodLayers.java`
- (`MethodSignatureFormatter` stays — it's
  proxy-internal formatting infrastructure)

**Pause-and-ask:** `MethodSignatureFormatter` could also
move to inqudium-pipeline if both adapters need it. Check
at B.2 start whether the formatter is referenced from
outside `ProxyStackAdapter`. If yes, migrate. If no,
keep in inqudium-proxy.

**Important — sealed permits placement:**

```java
public sealed interface InqStackInfo permits
        FunctionStackInfo, ProxyStackInfo {

    long stackId();
    Optional<Class<?>> targetType();
    List<InqElement> elements();
    List<MethodLayers> methodLayers();
}
```

Note: **only two permits** in Phase B, not four. ADR-039
spec lists four (`FunctionStackInfo`, `ProxyStackInfo`,
`AspectJStackInfo`, `SpringAspectStackInfo`). Phase B's
bare-bones strategy means the sealed declaration starts
with two; future Phase B+ work expands the permits when
aspect/spring modules are rebuilt.

**This is a deliberate ADR-039 deviation.** Document
in B.6's ADR-039 update: "Sealed declaration starts with
FunctionStackInfo and ProxyStackInfo only. AspectJStackInfo
and SpringAspectStackInfo will be added when their
respective modules are rebuilt."

**Verification gates:**

- [ ] `mvn verify` green.
- [ ] `InqStackInfo` exists, sealed, two permits.
- [ ] `MethodLayers` migrated to inqudium-pipeline; old
  location returns 404.
- [ ] `ProxyStackInfo` implements `InqStackInfo`.
- [ ] `FunctionStackInfo` exists as record implementing
  `InqStackInfo`.
- [ ] `ProxyStackAdapter` updated to return the new
  `InqStackInfo` (or its `ProxyStackInfo` permit) instead
  of standalone `ProxyStackInfo`.

**Estimated effort:** 4-5 hours.

**Branch:** `refactor/inqstackinfo-sealed-hierarchy`.

## Sub-step B.3 — FunctionStackAdapter (deferred)

**Status:** **Deferred to post-Phase-B work.** The
original B.3 plan was to implement `FunctionStackAdapter`
analogous to `ProxyStackAdapter`, walking the
`AbstractBaseWrapper.inner()` chain to collect elements
and method-layer descriptions.

**Why deferred:** A pre-audit during B.3 preparation
revealed an architectural mismatch. ADR-040 §6 specifies
that `InqPipeline.protect(...)` carries paradigm-specific
overloads — `protect(Supplier<T>)` for functional
decoration, `protect(Class<T>, T)` for proxy, and so on
— each routing to its respective integration dispatcher.
The proxy overload exists today; the function overload
(`protect(Supplier<T>)`, `protect(Runnable)`,
`protect(Function<I, O>)`, `protect(Callable<V>)`,
`protect(JoinPointExecutor<R>)`) does not.

Without a Function-Dispatch-Integration that produces
*pipeline-aware* function wrappers, a `FunctionStackAdapter`
would have to inspect raw `AbstractBaseWrapper` chains
constructed directly by user code. Those chains don't
carry a pipeline reference, so the adapter would
synthesise `InqStackInfo.elements()` from
`AbstractBaseWrapper.layerDescription()` strings — which
bypasses ADR-040's "pipeline is the unit of composition"
contract.

**What needs to happen first:**

1. **Function-Dispatch-Integration must exist.** Analogous
   to `ProxyDispatcher` / `InqInvocationHandler` /
   `ProxyDelegation`, the function paradigm needs a
   dispatcher that:
   - Takes an `InqPipeline` and a function
     (`Supplier<T>`, `Runnable`, `Function<I, O>`,
     `Callable<V>`, `JoinPointExecutor<R>`) as input
   - Produces a wrapper that carries a pipeline reference
   - Routes invocations through the pipeline's elements
     in canonical composition order

2. **`InqPipeline.protect(Supplier<T>)` and siblings**
   must exist as default methods on `InqPipeline`, with
   classpath-probing analogous to `DetectionProxy`
   (`DetectionFunction`) and a reflective bridge
   analogous to `ProxyDelegation`
   (`FunctionDelegation`).

3. **Once those exist,** the `FunctionStackAdapter`
   becomes a straightforward port of `ProxyStackAdapter`:
   `supports(Object)` checks for the pipeline-aware
   wrapper type; `inspect(Object)` extracts the pipeline
   reference and reads `elements()` / `methodLayers()`
   from it.

**`FunctionStackInfo` stays.** The empty record permit
created in B.2 remains in the sealed `InqStackInfo`
hierarchy. It's the correct future permit; when
Function-Dispatch-Integration lands, the permit is
ready.

**Phase B continues with B.4.** The `InqIntrospector`
implemented in B.4 dispatches only to `ProxyStackAdapter`
today. The dispatch surface is the right place for
future adapters; adding `FunctionStackAdapter` later is
strictly additive.

**ADR-039 acceptance** (B.6) documents this clearly:
ADR-039 is promoted to Accepted with one implemented
adapter (`ProxyStackAdapter`); `FunctionStackAdapter`,
`AspectJStackAdapter`, and `SpringAspectStackAdapter`
are all anticipated when their respective integration
infrastructure exists.

## Sub-step B.4 — InqIntrospector + InqStackRenderer

**Goal:** Create the central `InqIntrospector.inspect(Object)`
that dispatches to the available adapters. Plus the
`InqStackRenderer` for paradigm-agnostic output formatting
(`toTree`, `toJson`).

**New files:**

- `inqudium-pipeline/.../introspection/InqIntrospector.java`
- `inqudium-pipeline/.../introspection/InqStackRenderer.java`

**InqIntrospector shape:**

```java
public final class InqIntrospector {

    public static Optional<InqStackInfo> inspect(Object instance) {
        // ProxyStackAdapter check via classpath probe
        // (using ProxyStackAdapter directly since both
        // modules are now adjacent in the dep graph)
        if (ProxyStackAdapter.supports(instance)) {
            return Optional.of(ProxyStackAdapter.inspect(instance));
        }
        // FunctionStackAdapter is deferred (see B.3) —
        // a future `if (FunctionStackAdapter.supports(...))`
        // branch will land here when Function-Dispatch-
        // Integration exists.
        return Optional.empty();
    }
}
```

**Pause-and-ask:** the adapter chain may benefit from
the detection-probe pattern (DetectionAsync-style) for
the proxy adapter, especially since `inqudium-pipeline`
has only an optional dependency on `inqudium-proxy`. Or
the direct call may work because both modules sit at
the same level. Decide at B.4 start based on module
dependency declarations.

**InqStackRenderer shape:**

```java
public final class InqStackRenderer {

    public static String toTree(InqStackInfo info) {
        // Build ASCII tree representation.
        // Per ADR-039: stack ID at the top, element chain,
        // per-method layer descriptions.
    }

    public static String toJson(InqStackInfo info) {
        // Build JSON representation. No third-party JSON
        // library — write directly or use java.text helpers.
    }
}
```

**Tests:**
- `InqIntrospectorTest.java` — dispatch correctness
- `InqStackRendererTest.java` — output format correctness
  (verify against literal expected strings for several
  representative `InqStackInfo` shapes)

**Verification gates:**

- [ ] `mvn verify` green.
- [ ] `InqIntrospector.inspect(Object)` dispatches to
  the right adapter or returns Optional.empty().
- [ ] `InqStackRenderer.toTree/toJson` produce stable
  output.
- [ ] Tests exist and cover dispatch + format edge cases.

**Estimated effort:** 5-7 hours.

**Branch:** `feat/inq-introspector-renderer`.

## Sub-step B.5 — Bridge-state resolution

**Goal:** Replace the Phase-A transitional bridge with a
new `AnnotationEvaluator` that operates directly on
`eu.inqudium.pipeline.InqPipeline`. Delete the four
legacy bridge files.

**Steps:**

### B.5.1 New AnnotationEvaluator

Implement a new `AnnotationEvaluator` (or rename the
existing `InqPipelineAnnotationEvaluator` to be the
canonical one). Location decided at B.5 start —
likely `inqudium-pipeline/.../AnnotationEvaluator.java`.

Functionality identical to the legacy
`AnnotationEvaluator` + `DefaultAnnotationEvaluator`, but:
- Returns plans pointing at `eu.inqudium.pipeline.InqPipeline`
  (new interface)
- No reference to legacy
  `eu.inqudium.core.pipeline.InqPipeline` (concrete class)

### B.5.2 Migrate ProxyBuilder

`inqudium-proxy/.../construction/ProxyBuilder.java`
currently uses `InqPipelineAnnotationEvaluator`. Update
to use the new `AnnotationEvaluator`.

### B.5.3 Delete the four bridge files

```bash
git rm inqudium-core/src/main/java/eu/inqudium/core/pipeline/InqPipeline.java
git rm inqudium-annotation/src/main/java/eu/inqudium/annotation/evaluator/AnnotationEvaluator.java
git rm inqudium-annotation/src/main/java/eu/inqudium/annotation/evaluator/DefaultAnnotationEvaluator.java
git rm inqudium-pipeline/src/main/java/eu/inqudium/pipeline/InqPipelineAnnotationEvaluator.java
```

Plus their respective test files. Audit at B.5 start to
ensure no remaining importers.

### B.5.4 Update related code

Other files in `inqudium-annotation/.../evaluator/`
(e.g. `EvaluationResult`, `MethodPlan`,
`AnnotationSource`, `ParadigmClassifier`, `MethodResolver`,
`OrderingResolver`, etc.) — verify they don't depend on
the legacy `InqPipeline` class. If they do, migrate.

**Pause-and-ask:** at B.5 start, audit `inqudium-annotation`'s
evaluator package thoroughly. Some classes may need to
move to `inqudium-pipeline` along with the new
AnnotationEvaluator; some may stay in
`inqudium-annotation`. Surface the per-class plan.

**Verification gates:**

- [ ] `mvn verify` green.
- [ ] Four bridge files deleted.
- [ ] `git grep -l 'eu\.inqudium\.core\.pipeline\.InqPipeline\b' inqudium-*/src` returns empty.
- [ ] New AnnotationEvaluator works (proxy construction
  still succeeds for all integration tests).
- [ ] `@SuppressWarnings` delta documented.

**Estimated effort:** 5-7 hours.

**Branch:** `refactor/resolve-bridge-state`.

## Sub-step B.6 — ADR-039 status flip + final cleanup

**Goal:** Promote ADR-039 from `Proposed` to `Accepted`
with accurate Implementation-status section. Plus
package-info updates and any remaining housekeeping.

### B.6.1 Update ADR-039

File: `docs/adr/039-uniform-stack-introspection.md`

**Status change:**

```markdown
// Was:
**Status:** Proposed
**Date:** 2026-05-13

// Is now:
**Status:** Accepted
**Date:** 2026-05-13 (proposed); 2026-05-19 (accepted)
```

**Implementation-status section** — rewrite from "Proposed"
to "Accepted" with current state. Describe:
- Sub-step 3.12 of the proxy rewrite (already documented)
- Phase B's bare-bones implementation of the remaining
  deferred work
- `chainId` → `stackId` rename completed
- `InqStackInfo` sealed hierarchy with two initial permits
  (`FunctionStackInfo`, `ProxyStackInfo`). Only
  `ProxyStackAdapter` is implemented; `FunctionStackInfo`
  remains an empty permit pending Function-Dispatch-
  Integration. Two more permits anticipated when
  `inqudium-aspect` and `inqudium-spring` modules are
  rebuilt.
- `InqIntrospector` + `InqStackRenderer` operational
- Bridge state resolved; legacy `InqPipeline` deleted

### B.6.2 Add anticipated-future-work note

In ADR-039, near the Decision section's "Adapter chain"
listing, add a paragraph:

> **Phase B implementation status (2026-05-19):**
> One of the four anticipated adapters
> (`ProxyStackAdapter`) is implemented.
> `FunctionStackAdapter` is anticipated when
> Function-Dispatch-Integration on `InqPipeline`
> (`protect(Supplier<T>)` and siblings, per ADR-040 §6)
> exists. `AspectJStackAdapter` and
> `SpringAspectStackAdapter` are anticipated when the
> `inqudium-aspect` and `inqudium-spring` modules
> (currently stubbed per Phase A) are rebuilt. The
> sealed `InqStackInfo` hierarchy will be widened with
> `AspectJStackInfo` and `SpringAspectStackInfo` permits
> at that time.

### B.6.3 ADR cross-reference cleanup

Verify other ADRs' references to `chainId` are updated
(should mostly be in historical-context passages, but
verify):

```bash
grep -rln "chainId" docs/adr/*.md
```

If hits remain in "Accepted" ADRs (not superseded ones),
update them per the rename.

### B.6.4 Delete the plan document

```bash
git rm REFACTORING_ADR_039.md
```

Git history preserves the plan.

### B.6.5 Reactor verification

```bash
mvn verify
```

Expected: green.

**Verification gates:**

- [ ] ADR-039 status is `Accepted` with updated date.
- [ ] ADR-039 implementation-status section reflects
  Phase B reality.
- [ ] Anticipated-future-work note for aspect/spring
  adapters present.
- [ ] No `chainId` references in non-superseded ADRs.
- [ ] `REFACTORING_ADR_039.md` deleted.
- [ ] `mvn verify` green.

**Estimated effort:** 2-3 hours.

**Branch:** `docs/adr-039-acceptance`.

## Total estimated effort

| Sub-step | Estimated effort |
|---|---|
| B.0 | 1 hour (audit, no commit) |
| B.1 | 3-4 hours |
| B.2 | 4-5 hours |
| B.3 | 0 hours (deferred) |
| B.4 | 5-7 hours |
| B.5 | 5-7 hours |
| B.6 | 2-3 hours |

**Total range:** 19-27 hours across the 5 active
sub-steps (B.0 audit + B.1, B.2, B.4, B.5, B.6),
distributed over 3 working days at sustainable pace.

## Completion log

* [x] B.0 — Audit (no commit) (2026-05-19)
* [x] B.1 — chainId → stackId rename library-wide (2026-05-20, PR #106)
* [x] B.2 — InqStackInfo sealed hierarchy + DTO migration (2026-05-20, PR #107)
* [~] B.3 — FunctionStackAdapter (deferred 2026-05-20, PR #108)
* [x] B.4 — InqIntrospector + InqStackRenderer (2026-05-20, PR #109)
* [x] B.5 — Bridge-state resolution (2026-05-21, PR #110)
* [x] B.6 — ADR-039 status flip + final cleanup (2026-05-21, PR #XXX)

**Phase B complete 2026-05-21.**

## Scope discipline

These items are **out of scope** for Phase B:

- **AspectJStackAdapter and SpringAspectStackAdapter**
  (require aspect/spring module rebuild — separate work)
- **DetectionAspectJ and DetectionSpringAop probes**
  (same reason)
- **FunctionStackAdapter** — deferred (was B.3; see the
  deferral notice in the B.3 section). Requires
  Function-Dispatch-Integration on `InqPipeline`
  (`protect(Supplier<T>)`, `protect(Runnable)`, etc.)
  per ADR-040 §6 first.
- **Function-Dispatch-Integration itself** —
  `InqPipeline.protect(Supplier<T>)` and siblings,
  classpath probe (`DetectionFunction`), reflective
  bridge (`FunctionDelegation`). Anticipated as the
  prerequisite for `FunctionStackAdapter`. Likely
  warrants its own refactor plan when undertaken.
- **SerializedLambda tier-2 method resolution** (ADR-039
  flagged as deferred future enhancement)
- **Top-level legacy `Bulkhead.java` and
  `ImperativeBulkhead.java`** (separate "legacy resilience
  surface" refactor)
- **Rebuilding `inqudium-aspect` or `inqudium-spring`**
  (separate refactor)

If during execution a Phase B element seems unavoidable
to make progress, surface as a finding and pause. The
clean separation between Phase B (implement the deferred
ADR-039 work for available paradigms) and future work
(rebuild stubbed modules + add their adapters) is worth
preserving.

## When unsure

- The codebase is the ground truth. Use `grep` and `find`
  to verify counts and locations.
- Sub-step decisions that look ambiguous are
  pause-and-ask moments. The plan delegates execution
  detail; the maintainer delegates broad direction.
- ADR-039 is the authoritative reference; if any sub-step
  finds an inconsistency between ADR-039 and the
  achievable state, surface for resolution.

## Approach for new findings

If during execution a previously-unknown dependency
surfaces:

1. **Trivial fix in scope** → fix it as part of the
   current sub-step, document in the report.
2. **Non-trivial but in Phase B scope** → split into
   pause-and-ask + extension of the sub-step.
3. **Phase B-out-of-scope work** → stop, surface, decide
   whether to:
   - (a) defer to future work,
   - (b) accept a temporary intermediate form in
     Phase B.

The default for (3) is "defer" — Phase B's purpose is
ADR-039 promotion, not introduction of new architecture.
