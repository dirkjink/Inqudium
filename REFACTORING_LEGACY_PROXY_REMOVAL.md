# Refactoring plan: Legacy proxy stack removal (Phase A)

**Plan reference:** Phase A of the two-phase post-polish
refactor sequence. Phase B (ADR-039 implementation +
chainId rename) is planned and executed separately after
this plan completes.

**Goal:** Remove the legacy proxy implementation in
`eu.inqudium.core.pipeline.proxy` together with the legacy
`eu.inqudium.core.pipeline.InqPipeline` concrete class and
its companion `Wrapper`-hierarchy. After Phase A, all
production code imports `eu.inqudium.pipeline.InqPipeline`
(the new interface) and the JDK-proxy integration lives
exclusively in `inqudium-proxy`. The legacy stack — kept
alive per ADR-035's Strategy-G greenfield-parallel approach
— is decommissioned.

**Status:** Audit completed. Plan ready for sub-step
execution. Maintainer decisions baked in: full scope
(A1+A2+A3), phase-structured, pragmatic test strategy.

## Background

### What exists today

Two complete parallel pipeline stacks coexist on `main`:

**Legacy stack** (predates ADR-035):
- `eu.inqudium.core.pipeline.InqPipeline` (concrete class,
  387 LOC, ~14 main importers across 8 modules)
- `eu.inqudium.core.pipeline.Wrapper` + `BaseWrapper` +
  `AbstractBaseWrapper` + 4 function-specific wrappers
- `eu.inqudium.core.pipeline.proxy/*` (8 classes, ~2,062
  LOC) — `InqProxyFactory`, `ProxyWrapper`,
  `AbstractProxyWrapper`, `DispatchExtension`,
  `PipelineDispatchExtension`, `SyncDispatchExtension`,
  `MethodInvoker`, `MethodHandleCache`
- `inqudium-imperative/.../proxy/*` (`InqAsyncProxyFactory`
  and friends) bridging legacy proxy to async paradigm

**New stack** (built per ADR-035 spec):
- `eu.inqudium.pipeline.InqPipeline` (interface, 92 LOC) +
  `InqPipelineBuilder` + `DefaultInqPipeline` +
  `InqPipelineAnnotationEvaluator`
- `inqudium-proxy/*` (41 classes, 2,618 LOC) with
  `ProxyDispatcher.protect(pipeline, serviceInterface, target)`
  as the public entry point
- `DetectionProxy`, `ProxyDelegation` for reflective
  bridging (avoids Maven cycle between `inqudium-pipeline`
  and `inqudium-proxy`)

### Why now

ADR-035 §"legacy proxy" explicitly defers the legacy
removal: "Its removal is a separate refactor." Phase A is
that refactor.

The motivation is no longer just code hygiene. ADR-039 (the
upcoming Phase B work) requires a `chainId` → `stackId`
rename across the entire library — 131 files, ~769
occurrences. Doing that rename across two parallel stacks
doubles the work; removing the legacy stack first
collapses Phase B's scope substantially.

### What stays out of Phase A scope

Phase A removes only the legacy pipeline-and-proxy stack.
The following are out of scope:

- **chainId → stackId rename** (Phase B work, ADR-039)
- **`InqStackInfo` sealed hierarchy** (Phase B work)
- **AspectJ and Spring AOP adapters** (Phase B work)
- **`Bulkhead`, `ImperativeBulkhead`, `Bulkhead.of()`** —
  separate legacy-resilience-surface cleanup (P.9 already
  flagged these as future work)
- **CircuitBreaker (top-level legacy class)** — not
  deprecated; out of scope
- **Historical ADRs** — terminology changes deferred

## Audit findings

Recorded here once, referenced throughout the sub-steps.

### A.0.1 Legacy stack files (with line counts)

Production code, `inqudium-core/src/main/java/eu/inqudium/core/pipeline/`:

```
InqPipeline.java                      387 LOC
Wrapper.java                          (interface, base of hierarchy)
BaseWrapper.java                      (sealed abstract base)
AbstractBaseWrapper.java              (impl helper)
LayerTerminal.java                    (functional terminal)
InqDecorator.java                     (decorator interface)
PipelineOrdering.java                 (ordering enum/values)
PipelineIds.java                      (id generation)
PipelineValidator.java                (validation)
SyncPipelineTerminal.java             (sync terminal)
Throws.java                           (checked-exception helper)
LayerAction.java                      (action shape)
ResolvedPipelineState.java            (resolved state)
package-info.java                     (package docs)
```

The legacy proxy sub-package `inqudium-core/src/main/java/eu/inqudium/core/pipeline/proxy/`:

```
AbstractProxyWrapper.java             303 LOC
DispatchExtension.java                203 LOC
InqProxyFactory.java                  199 LOC
MethodHandleCache.java                246 LOC
MethodInvoker.java                     67 LOC
PipelineDispatchExtension.java        457 LOC
ProxyWrapper.java                     284 LOC
SyncDispatchExtension.java            303 LOC
TOTAL                               2,062 LOC
```

`inqudium-imperative/src/main/java/eu/inqudium/imperative/core/pipeline/proxy/`:

```
InqAsyncProxyFactory.java
AsyncDispatchExtension.java
AsyncPipelineDispatchExtension.java
```

### A.0.2 Production importers of legacy `InqPipeline`

14 files in 8 modules:

- `inqudium-annotation`:
  - `AnnotationEvaluator.java`, `DefaultAnnotationEvaluator.java`
- `inqudium-annotation-support`:
  - `PipelineFactory.java`
- `inqudium-aspect`:
  - `AspectPipelineTerminal.java`, `HybridAspectPipelineTerminal.java`
- `inqudium-bulkhead-integration/inqudium-bulkhead-integration-aspectj`:
  - `OrderBulkheadAspect.java`
- `inqudium-core` (self):
  - `InqProxyFactory.java`, `PipelineDispatchExtension.java`
- `inqudium-imperative`:
  - `AsyncPipelineTerminal.java`, `InqAsyncProxyFactory.java`,
    `AsyncPipelineDispatchExtension.java`
- `inqudium-spring`:
  - `ResolvedShieldPipeline.java`, `InqShieldAspect.java`

### A.0.3 Production importers of legacy `Wrapper` / `BaseWrapper`

Approximately 15 files (10 main + 5 supporting) — mostly in
`inqudium-aspect`, `inqudium-spring`, and the integration
examples. Detailed list to be regenerated when sub-step A.3
starts (count may shift slightly as A.1/A.2 remove
upstream usages).

### A.0.4 Legacy test files

Roughly 12-15 test files in `inqudium-core/src/test/.../proxy/`
plus a handful of cross-module bulkhead-library-tests:

```
InqProxyFactorySyncTest.java
InqProxyFactoryPipelineTest.java
ProxyObjectMethodsTest.java
ProxyCreationValidationTest.java
ProxyChainBypassTest.java
MethodHandleDispatchTest.java
MethodHandleCacheTest.java
ProxyMultiThreadedBenchmark.java
ProxyMultiThreadedMultiLayerBenchmark.java
... plus a few cross-module integration tests
```

### A.0.5 Mapping: legacy → new

| Legacy | Replacement | Notes |
|---|---|---|
| `eu.inqudium.core.pipeline.InqPipeline` (class) | `eu.inqudium.pipeline.InqPipeline` (interface) + `InqPipelineBuilder` + `DefaultInqPipeline` | Concrete-class → interface migration |
| `InqProxyFactory.of(pipeline).protect(iface, target)` | `ProxyDispatcher.protect(pipeline, iface, target)` | Static-call API |
| `InqAsyncProxyFactory` | (covered by `ProxyDispatcher` — hybrid sync/async) | New stack handles both natively |
| `Wrapper<S>` recursive self-type | (replaced in Phase B by `InqStackInfo` adapter pattern) | Phase A removes; Phase B introduces the new shape |
| `AnnotationEvaluator` (legacy InqPipeline) | `InqPipelineAnnotationEvaluator` (new stack) | Module-level migration needed |

### A.0.6 Audit baselines

Current main, verified at audit time:

- Java files in legacy proxy package: **8 classes**
- Java files in new proxy module: **41 classes**
- `@SuppressWarnings` (per-instance count): **70 main /
  181 total** (P.9's baseline)
- chainId references: **769 occurrences** across **131
  files** (Phase B will rename; Phase A preserves
  unchanged)
- `mvn verify` on current main: **green across 24 modules**

## Sub-step structure

Phase A is divided into four sub-steps, executed in order.
Each sub-step is one PR; no PR is merged until all its
sub-step's verification gates pass.

```
A.0  — Audit (this document). No commit.
A.1  — Migrate legacy InqPipeline importers in non-proxy modules
A.2  — Migrate proxy callers (InqProxyFactory, InqAsyncProxyFactory)
A.3  — Migrate legacy Wrapper-hierarchy importers
A.4  — Delete legacy stack (final removal + cleanup)
```

The ordering matters. A.1 removes most consumer-side
dependencies before A.2 touches proxy bridging; A.3 then
handles remaining Wrapper-imports; A.4 deletes the now-
unused legacy types. Each sub-step preserves green-main:
the legacy stack remains functional until A.4 removes it
in one atomic step.

## Sub-step A.1 — Migrate legacy `InqPipeline` importers in non-proxy modules

**Goal:** Replace `import eu.inqudium.core.pipeline.InqPipeline`
with `import eu.inqudium.pipeline.InqPipeline` in all
production code outside the proxy bridge path. After A.1,
only `InqProxyFactory.java` and its direct collaborators
(handled in A.2) still import the legacy type.

**Files affected (~10 main + their tests):**

- `inqudium-annotation`: `AnnotationEvaluator`,
  `DefaultAnnotationEvaluator` — switch to
  `InqPipelineAnnotationEvaluator` shape
- `inqudium-annotation-support`: `PipelineFactory`
- `inqudium-aspect`: `AspectPipelineTerminal`,
  `HybridAspectPipelineTerminal`
- `inqudium-bulkhead-integration-aspectj`: `OrderBulkheadAspect`
- `inqudium-imperative`: `AsyncPipelineTerminal`
- `inqudium-spring`: `ResolvedShieldPipeline`, `InqShieldAspect`

**Approach:** Per-file analysis. The legacy `InqPipeline` is
a concrete class with methods like `elements()`,
`ordering()`, `depth()`, `chain(...)`. The new `InqPipeline`
is an interface with (likely) similar accessor methods.
Verify per file that the new interface provides the API
surface the consumer uses.

**Pause and ask** if a legacy method signature has no
equivalent in the new interface — that means the new
interface needs extension (out of A.1 scope; surface as
finding) or a different migration path is needed for that
consumer.

**Tasks (per file):**

1. Identify legacy method calls.
2. Map to new interface methods.
3. Update `import` and references.
4. Run `mvn -pl <module> -am test` to verify.

**Verification gates:**

- [ ] `mvn verify` green across all 24 modules.
- [ ] `grep -rln "import eu\.inqudium\.core\.pipeline\.InqPipeline\b" inqudium-*/src --include="*.java" | grep -v "/proxy/"` returns only the proxy-bridge files (to be migrated in A.2).
- [ ] `@SuppressWarnings` baseline: 70/181 unchanged (or
  documented delta).
- [ ] Test-count delta: zero (no new tests; existing tests
  pass against migrated code).

**Estimated effort:** 4-6 hours.

**Branch:** `refactor/migrate-legacy-pipeline-non-proxy`.

## Sub-step A.2 — Migrate proxy callers

**Goal:** Remove all `InqProxyFactory.of(...)` and
`InqAsyncProxyFactory.of(...)` calls in production code,
replacing with `ProxyDispatcher.protect(...)`. After A.2,
the legacy proxy factories are unused by production code
(only their own internal references and test files remain).

**Files affected:**

- Production callers (verified at A.0):
  - `inqudium-core/.../InqPipeline.java` — references
    `InqProxyFactory` in Javadoc-example code; verify
    whether it has an actual code-level call too
  - `inqudium-imperative/.../InqAsyncProxyFactory.java` —
    bridges legacy proxy to async paradigm; full replacement
    via `ProxyDispatcher` (which handles both natively)
- Integration code in `inqudium-bulkhead-integration-proxy`
  that demonstrates legacy proxy usage — verify migration
  scope

**Test migration decision** (per the pragmatic strategy):

For each of the ~12 legacy proxy tests, check whether
equivalent coverage exists in `inqudium-proxy/src/test/`.
For each test:

- **Equivalent exists** → mark for deletion in A.4.
- **No equivalent** → migrate the test to use the new stack
  (`ProxyDispatcher.protect(...)`) in this sub-step.

**Concrete inventory** (audit at A.2 start):

```bash
ls inqudium-core/src/test/java/eu/inqudium/core/pipeline/proxy/
# Cross-reference with:
ls inqudium-proxy/src/test/java/
```

Surface the migrate-vs-delete decision per test before
executing. Record in the sub-step report.

**Verification gates:**

- [ ] `mvn verify` green.
- [ ] `grep -rln "InqProxyFactory\|InqAsyncProxyFactory" inqudium-*/src/main --include="*.java" | grep -v "/core/pipeline/proxy/"` returns zero hits.
- [ ] All non-deletable legacy proxy tests either migrated
  to new stack or marked for A.4 deletion (with rationale
  in the report).
- [ ] `@SuppressWarnings` baseline preserved.

**Estimated effort:** 6-10 hours (depends heavily on
feature parity findings).

**Branch:** `refactor/migrate-proxy-callers-to-new-stack`.

## Sub-step A.3 — Migrate legacy `Wrapper` hierarchy importers

**Goal:** Replace `Wrapper`, `BaseWrapper`,
`AbstractBaseWrapper` imports in production code. The new
stack uses a different design (likely interface-based, no
recursive self-type) — verify the migration path during
sub-step planning, not in this audit document.

**Files affected:** ~10 production files, primarily in:

- `inqudium-aspect/` (multiple)
- `inqudium-spring/` (multiple)
- Integration examples

**Pause and ask** if the migration requires a new type to
be introduced in `inqudium-pipeline` (e.g., a stack-info
adapter that hasn't been ADR'd). The clean separation is:
A.3 migrates **consumer code**; new types come later (in
Phase B's ADR-039 implementation).

If A.3 reveals consumers that genuinely need a Phase-B
type (e.g., something like `InqStackInfo`), the option is
either:
1. **Stop A.3, advance to Phase B** for that bit and resume
   A.3 after — *not ideal*, breaks phase separation.
2. **Surface the gap as a finding** and migrate consumers
   to a temporary intermediate form. Phase B then refines.

**Verification gates:**

- [ ] `mvn verify` green.
- [ ] `grep -rln "import eu\.inqudium\.core\.pipeline\.Wrapper\b\|BaseWrapper\b\|AbstractBaseWrapper\b" inqudium-*/src --include="*.java" | grep -v "/core/pipeline/"` returns zero hits.
- [ ] `@SuppressWarnings` baseline preserved.

**Estimated effort:** 6-10 hours.

**Branch:** `refactor/migrate-legacy-wrapper-hierarchy`.

## Sub-step A.4 — Delete legacy stack (final removal)

**Goal:** Delete the legacy `inqudium-core/src/main/java/eu/inqudium/core/pipeline/proxy/`
sub-package and the legacy `InqPipeline` class plus its
`Wrapper` hierarchy. At this point, A.1+A.2+A.3 have
migrated every consumer; the legacy types have no remaining
production importers and only their own test files (which
are deleted with them).

**Tasks:**

1. **Delete legacy proxy sub-package:**
   ```bash
   git rm -r inqudium-core/src/main/java/eu/inqudium/core/pipeline/proxy/
   git rm -r inqudium-imperative/src/main/java/eu/inqudium/imperative/core/pipeline/proxy/
   ```

2. **Delete legacy pipeline core classes:**
   ```bash
   git rm inqudium-core/src/main/java/eu/inqudium/core/pipeline/InqPipeline.java
   git rm inqudium-core/src/main/java/eu/inqudium/core/pipeline/Wrapper.java
   git rm inqudium-core/src/main/java/eu/inqudium/core/pipeline/BaseWrapper.java
   git rm inqudium-core/src/main/java/eu/inqudium/core/pipeline/AbstractBaseWrapper.java
   # Plus the function-specific wrappers if no remaining importers
   ```

3. **Delete legacy tests** (per A.2's marked-for-deletion list):
   ```bash
   git rm inqudium-core/src/test/java/eu/inqudium/core/pipeline/proxy/InqProxyFactorySyncTest.java
   # ... etc per A.2's inventory
   ```

4. **Update `inqudium-core/src/main/java/eu/inqudium/core/pipeline/package-info.java`**
   if it mentions removed types — drop the references; the
   package's remaining contents (LayerTerminal, InqDecorator,
   etc.) may not need a separate package-info or may need
   a slimmed-down one.

5. **Sweep verification:**
   ```bash
   grep -rln "core\.pipeline\.proxy\|InqProxyFactory\|InqAsyncProxyFactory\|core\.pipeline\.InqPipeline\b\|core\.pipeline\.Wrapper\b" inqudium-*/src --include="*.java"
   ```
   Expected: zero hits.

6. **Update ADR-035** to mark its
   "legacy proxy preserved" note as **historical** —
   removed by Phase A's completion.

**Verification gates:**

- [ ] `mvn verify` green across all 24 modules.
- [ ] All legacy types deleted (working tree + Git).
- [ ] `grep -rln "core\.pipeline\.proxy\|InqProxyFactory\|InqAsyncProxyFactory" inqudium-*/src` returns zero hits.
- [ ] `grep -rln "import eu\.inqudium\.core\.pipeline\.InqPipeline\b" inqudium-*/src` returns zero hits.
- [ ] ADR-035 updated.
- [ ] `@SuppressWarnings` baseline: 70/181 (or documented delta).

**Estimated effort:** 2-3 hours.

**Branch:** `refactor/delete-legacy-pipeline-stack`.

## Total estimated effort

| Sub-step | Estimated effort |
|---|---|
| A.0 | 1 hour (audit, no commit) |
| A.1 | 4-6 hours |
| A.2 | 6-10 hours |
| A.3 | 6-10 hours |
| A.4 | 2-3 hours |

**Total range:** 19-30 hours of work, distributed over
4-6 working days at sustainable pace.

The wide range reflects the genuine uncertainty around
A.2 and A.3 — until each sub-step starts and the per-file
analysis happens, the migration complexity per consumer is
not fully known. Pause-and-ask points are documented at
each sub-step's risky moments.

## Completion log

* [x] A.0 — Audit (no commit) (2026-05-19)
* [ ] A.1 — Migrate legacy `InqPipeline` importers in non-proxy modules
* [ ] A.2 — Migrate proxy callers (`InqProxyFactory`, `InqAsyncProxyFactory`)
* [ ] A.3 — Migrate legacy `Wrapper` hierarchy importers
* [ ] A.4 — Delete legacy stack (final removal)

## Scope discipline

These items are **out of scope** for Phase A:

- **chainId → stackId rename** (Phase B work)
- **`InqStackInfo` sealed interface and adapters** (Phase B
  work)
- **AspectJ / Spring AOP introspection adapters** (Phase B
  work)
- **Top-level legacy `Bulkhead`, `ImperativeBulkhead`,
  `Bulkhead.of()`** — separate "remove legacy resilience
  surface" refactor (deferred from polish-plan P.9)
- **CircuitBreaker (top-level legacy class)** — not
  deprecated; out of scope
- **Historical ADRs** — terminology updates deferred

If during any sub-step a Phase B element seems unavoidable
to make progress (e.g., a `Wrapper` consumer that needs a
stack-info adapter that doesn't exist yet), surface as a
finding and pause. The clean separation between Phase A
(delete legacy) and Phase B (introduce ADR-039 types) is
worth preserving.

## When unsure

- The codebase is the ground truth. Use `grep` and `find` to
  verify counts and locations.
- Sub-step decisions that look ambiguous are pause-and-ask
  moments. The plan delegates execution detail; the maintainer
  delegates broad direction.
- ADR-035 is the authoritative reference for the new proxy
  architecture; ADR-039 is the authoritative reference for
  Phase B work. ADR cross-references in code may need
  updates per the sub-step.

## Approach for new findings

If during execution of any sub-step a previously-unknown
dependency surfaces:

1. **Trivial fix in scope** → fix it as part of the current
   sub-step, document in the report.
2. **Non-trivial but in Phase A scope** → split into a
   pause-and-ask + extension of the sub-step (e.g., A.2.5
   informal sub-step).
3. **Phase B work** → stop, surface, decide whether to:
   (a) defer the consumer until Phase B,
   (b) accept a temporary intermediate form in Phase A.

The default for case 3 is "defer" — Phase A's purpose is
deletion, not introduction of new architecture.
