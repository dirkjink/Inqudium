# Refactoring plan: Legacy proxy stack removal (Phase A)

**Plan reference:** Phase A of the two-phase post-polish
refactor sequence. Phase B (ADR-039 implementation +
chainId rename) is planned and executed separately after
this plan completes.

**Goal:** Decommission the legacy proxy stack —
`eu.inqudium.core.pipeline.proxy.*`, the legacy
`eu.inqudium.core.pipeline.InqPipeline` concrete class,
and the legacy `Wrapper` hierarchy — in one atomic
sequence. The migration strategy is **pragmatic
silencing**: modules that need to be rewritten in Phase B
anyway (notably `inqudium-aspect` and `inqudium-spring`)
are reduced to compilable-but-empty stubs rather than
incrementally migrated to the new pipeline API.

**Status:** Audit completed (2026-05-19). Plan approved
by maintainer. Pragmatic strategy: silence consumer
modules; do not extend the new `InqPipeline` interface;
delete legacy types atomically once consumers are silent.

## Background

### Why pragmatic silencing rather than incremental migration

The original audit found that consumers of the legacy
`InqPipeline` use methods (`depth()`, `ordering()`,
`isEmpty()`, `chain(seed, folder)`) that **do not exist**
on the new `InqPipeline` interface. The new interface is
deliberately minimal — `elements()`, `protect()`,
`builder()` only — to keep the contract tight.

Three possible reactions surfaced:

1. **Extend the new interface** with default methods for
   the missing operations — would make migration trivial
   but expands the public contract.
2. **Refactor each consumer** to derive the operations
   from `elements()` directly — preserves the minimal
   contract but multiplies the work across 7+ consumer
   files.
3. **Silence the consumers** that need to be rewritten
   anyway in Phase B (or beyond), accepting that those
   modules carry no working implementation for now.

**Maintainer's choice: option 3.** The consumers that
exercise the missing API surface are exactly the modules
that need full rewrites in Phase B:

- `inqudium-aspect` — needs ADR-039 introspection adapters
  (`AspectJStackAdapter`, etc.) and the new pipeline
  contract
- `inqudium-spring` — needs ADR-039 introspection adapters
  (`SpringAspectStackAdapter`) and the new pipeline
  contract

Silencing them now removes the legacy consumers; Phase B
(or a later refactor) rebuilds them against the new stack.

### What's in scope of Phase A

Phase A reduces the codebase to a state where:

1. The legacy `eu.inqudium.core.pipeline.proxy.*` package
   is **deleted entirely** (8 classes, ~2,062 LOC).
2. The legacy `eu.inqudium.core.pipeline.InqPipeline`
   concrete class is **deleted**.
3. The legacy `Wrapper`, `BaseWrapper`,
   `AbstractBaseWrapper`, and function-specific wrapper
   types in `eu.inqudium.core.pipeline.*` are **deleted**.
4. Five modules become **stubs** (POM stays, `src/main/java`
   reduced to a single `package-info.java` marker):
   - `inqudium-aspect`
   - `inqudium-spring`
   - `inqudium-bulkhead-integration-aspectj`
   - `inqudium-bulkhead-integration-function`
   - `inqudium-bulkhead-integration-spring-framework`
5. One module is **deleted entirely**:
   - `inqudium-aspect-integration-tests`
6. Three modules need **small targeted adjustments**:
   - `inqudium-spring-boot` —
     `InqAutoConfiguration.java` imports
     `InqShieldAspect` from the soon-stubbed
     `inqudium-spring`. Either remove the import + its
     usages or disable the affected configuration.
   - `inqudium-bulkhead-library-tests` — test files
     import `AspectPipelineTerminal` and
     `ElementLayerProvider` from the soon-stubbed
     `inqudium-aspect`. Tests get `@Disabled` or are
     deleted.
   - `inqudium-imperative` — `AsyncPipelineTerminal.java`
     and the legacy `proxy/*` subpackage import legacy
     types. These are deleted along with the legacy
     stack itself.
7. The legacy annotation evaluator
   (`AnnotationEvaluator`, `DefaultAnnotationEvaluator`,
   `DefaultOrderingResolver` in `inqudium-annotation`,
   plus `PipelineFactory` in
   `inqudium-annotation-support`) — **stays** because the
   transitional `InqPipelineAnnotationEvaluator` in
   `inqudium-pipeline` still bridges to it. Their legacy
   `InqPipeline` import gets handled when those
   transitional types are deleted (Phase B or later).

### What's out of scope

- **chainId → stackId rename** (Phase B, ADR-039)
- **`InqStackInfo` sealed hierarchy** (Phase B)
- **AspectJ / Spring AOP adapters** (Phase B)
- **Rewriting `inqudium-aspect` and `inqudium-spring`**
  (Phase B or a separate refactor)
- **Deleting `inqudium-annotation`'s legacy InqPipeline
  reference** — handled when `InqPipelineAnnotationEvaluator`
  goes away in Phase B
- **Top-level legacy `Bulkhead`, `ImperativeBulkhead`,
  `Bulkhead.of()`** — separate "legacy resilience surface"
  cleanup
- **CircuitBreaker (top-level class)** — not deprecated

## Audit findings

### A.0.1 Modules grouped by Phase-A treatment

**Stub (replaced with empty `src/main/java/package-info.java`):**

| Module | Reason |
|---|---|
| `inqudium-aspect` | 13 main + 10 test files; uses missing API (`chain`, `depth`) |
| `inqudium-spring` | 2 main + 9 test files; uses missing API (`chain`, `isEmpty`, `depth`) |
| `inqudium-bulkhead-integration-aspectj` | Demo integration; 1 file imports legacy InqPipeline |
| `inqudium-bulkhead-integration-function` | Demo integration; 1 file imports legacy `Wrapper` |
| `inqudium-bulkhead-integration-spring-framework` | Demo integration; transitively depends on inqudium-spring |

**Delete entirely:**

| Module | Reason |
|---|---|
| `inqudium-aspect-integration-tests` | Test-only module; supports the to-be-stubbed `inqudium-aspect` |

**Targeted adjustment:**

| Module | Adjustment |
|---|---|
| `inqudium-spring-boot` | Remove the `InqShieldAspect` import + its usages in `InqAutoConfiguration` |
| `inqudium-bulkhead-library-tests` | Disable or delete tests that import `AspectPipelineTerminal` / `ElementLayerProvider` |

**Delete along with legacy stack:**

| Module / Package | Reason |
|---|---|
| `inqudium-core/src/main/java/eu/inqudium/core/pipeline/proxy/*` | Legacy proxy (8 classes) |
| `inqudium-core/src/main/java/eu/inqudium/core/pipeline/InqPipeline.java` | Legacy pipeline class |
| `inqudium-core/src/main/java/eu/inqudium/core/pipeline/Wrapper.java` + `BaseWrapper.java` + `AbstractBaseWrapper.java` + wrapper subtypes | Legacy wrapper hierarchy |
| `inqudium-imperative/src/main/java/eu/inqudium/imperative/core/pipeline/proxy/*` | Legacy async proxy bridge |
| `inqudium-imperative/.../core/pipeline/AsyncPipelineTerminal.java` | Legacy async terminal |
| Various legacy support types (`LayerTerminal`, `LayerAction`, `Throws`, `JoinPointExecutor`, `JoinPointWrapper`, etc.) | Used only by legacy stack |

**Stays (not Phase-A scope):**

| Module | Reason |
|---|---|
| `inqudium-annotation` (AnnotationEvaluator etc.) | Bridged by `InqPipelineAnnotationEvaluator`; cleaned up in Phase B |
| `inqudium-annotation-support` (`PipelineFactory`) | Same — supports the transitional bridge |
| `inqudium-pipeline` (`InqPipelineAnnotationEvaluator`) | Transitional bridge; deleted in Phase B |

### A.0.2 Audit baselines

Current main, verified at audit time:

- Java files in legacy proxy package: **8 classes**
- Java files in legacy `inqudium-core/pipeline`: **~14 classes** (including Wrapper hierarchy + supporting types)
- Java files in new proxy module: **41 classes**
- `@SuppressWarnings` (per-instance count): **70 main / 181 total** (P.9's baseline)
- chainId references: **769 occurrences** across **131 files** (Phase B will rename; Phase A preserves unchanged)
- `mvn verify` on current main: **green across 24 modules**

## Sub-step structure

Phase A is divided into four sub-steps. Each sub-step is
one PR. The order matters: consumers are silenced before
the legacy types they depend on get deleted, so main stays
green at each merge point.

```
A.0 — Audit (this document). No commit.
A.1 — Silence consumer modules (5 stubs + 1 deletion + adjustments)
A.2 — Silence cascading consumers (inqudium-spring-boot, bulkhead-library-tests)
A.3 — Delete legacy stack (proxy, InqPipeline, Wrapper, supporting types)
A.4 — Final cleanup (ADR-035 update, package-info sweeps, test inventory)
```

Each sub-step preserves green-main. After A.1, the
silenced modules compile but do nothing useful — that's
intentional. After A.2, no module references the
about-to-be-deleted types. After A.3, the legacy stack is
gone. A.4 polishes the result.

## Sub-step A.1 — Silence consumer modules

**Goal:** Reduce the five consumer modules to empty stubs
and delete the one test-only consumer module. After A.1,
no production code in these modules references the legacy
pipeline.

### A.1.1 Stub `inqudium-aspect`

Strategy:
1. Delete every `*.java` file under
   `inqudium-aspect/src/main/java/eu/inqudium/aspect/`
   except a new `package-info.java` marker.
2. Delete every `*.java` file under
   `inqudium-aspect/src/test/java/`.
3. The new `package-info.java`:

   ```java
   /**
    * Aspect-based integration for Inqudium. This module is
    * intentionally empty in 0.10.x — its previous content
    * depended on the legacy {@code eu.inqudium.core.pipeline}
    * stack, which was removed in Phase A. A future Phase B
    * (ADR-039) rebuilds this module against the new pipeline
    * interface.
    *
    * <p>The POM stays in place so dependent modules can keep
    * the dependency declaration; the artifact ships as an
    * empty jar.</p>
    */
   package eu.inqudium.aspect;
   ```

4. POM stays unchanged.

### A.1.2 Stub `inqudium-spring`

Same approach:
1. Delete every `*.java` file under
   `inqudium-spring/src/main/java/eu/inqudium/spring/`
   except a new `package-info.java` marker.
2. Delete every `*.java` file under
   `inqudium-spring/src/test/java/`.
3. New `package-info.java` mirroring A.1.1's text,
   adjusted for Spring.
4. POM stays.

### A.1.3 Stub `inqudium-bulkhead-integration-aspectj`

Same approach:
1. Delete every `*.java` under
   `inqudium-bulkhead-integration-aspectj/src/main/java/`.
2. Delete every `*.java` under
   `inqudium-bulkhead-integration-aspectj/src/test/java/`
   if any.
3. New `package-info.java` marker.

### A.1.4 Stub `inqudium-bulkhead-integration-function`

Same approach.

### A.1.5 Stub `inqudium-bulkhead-integration-spring-framework`

Same approach.

### A.1.6 Delete `inqudium-aspect-integration-tests`

Strategy:
1. Delete the entire module directory:
   ```bash
   git rm -r inqudium-aspect-integration-tests
   ```
2. Remove the `<module>` entry from the root `pom.xml`.

### A.1.7 Verification gates for A.1

- [ ] `mvn verify` green across all remaining modules.
- [ ] Each of the 5 stubbed modules contains exactly one
      file under `src/main/java`: a `package-info.java`
      marker. No `*.java` files under `src/test/java`.
- [ ] `inqudium-aspect-integration-tests` no longer exists
      in the working tree or in the root POM's `<modules>`
      list.
- [ ] **Note:** at this point, `inqudium-spring-boot`'s
      `InqAutoConfiguration` will likely fail to compile
      because it imports `InqShieldAspect`. This is
      expected — it's the next sub-step's task. The
      verification gate runs **after** A.2 completes.

**Wait — gate ordering matters.** A.1 alone leaves
`inqudium-spring-boot` and `inqudium-bulkhead-library-tests`
broken. Therefore A.1 should be merged together with A.2
**as a single PR** to keep main green.

**Revised plan:** A.1 and A.2 are bundled into a single
PR with the silencing happening atomically. The
sub-step numbering in this plan stays for clarity, but
execution treats them as one unit.

**Estimated effort:** 2-3 hours.

## Sub-step A.2 — Silence cascading consumers

**Goal:** Fix the two modules that transitively depend on
the silenced modules. Bundled with A.1 into one PR per the
ordering constraint above.

### A.2.1 Adjust `inqudium-spring-boot`

File:
`inqudium-spring-boot/src/main/java/eu/inqudium/spring/boot/InqAutoConfiguration.java`

The file imports `InqShieldAspect` from `inqudium-spring`,
which is now stubbed. Options:

**Option (a):** Remove the import and all references to
`InqShieldAspect`. The `@Bean` definition that registers
it goes; any related configuration goes; document the
removal in Javadoc as "rebuilt in Phase B".

**Option (b):** Comment out the import + bean definition
with `// TODO Phase B: re-add when InqShieldAspect rebuilt`.

Maintainer's pragmatic strategy points at option (a) —
the file is honest about what works.

If `InqAutoConfiguration` has other functionality unrelated
to `InqShieldAspect`, that stays. If `InqShieldAspect` was
its entire reason to exist, the whole file may also become
a stub.

**Pause and ask** which form `InqAutoConfiguration` should
take after the silencing.

### A.2.2 Adjust `inqudium-bulkhead-library-tests`

Test files in this module import:
- `eu.inqudium.aspect.pipeline.AspectPipelineTerminal`
- `eu.inqudium.aspect.pipeline.ElementLayerProvider`

Options per test:
1. Delete the test if it solely exists to exercise the
   silenced aspect/spring functionality.
2. Add `@Disabled` with a comment referring to Phase B if
   the test has a chance of being relevant after rewrite.

Concrete inventory at A.2 start:
```bash
grep -rln "eu\.inqudium\.aspect\.\|eu\.inqudium\.spring\." \
    inqudium-bulkhead-library-tests/src/test --include="*.java"
```

### A.2.3 Verification gates for A.1+A.2 bundled PR

- [ ] `mvn verify` green across all 23 modules
      (24 minus the deleted `inqudium-aspect-integration-tests`).
- [ ] The 5 stubbed modules contain only `package-info.java`
      under `src/main/java`.
- [ ] `inqudium-aspect-integration-tests` deleted.
- [ ] `inqudium-spring-boot` compiles cleanly.
- [ ] `inqudium-bulkhead-library-tests` compiles; affected
      tests are deleted or `@Disabled`.
- [ ] `@SuppressWarnings` baseline: 70/181 ± 5 (deletions
      will reduce the count; document the delta).
- [ ] **NOT YET:** the legacy
      `eu.inqudium.core.pipeline.InqPipeline` and friends
      still exist. They're deleted in A.3.

**Estimated effort:** 1-2 hours for A.2 part. Combined
A.1+A.2 PR: 4-5 hours total.

**Branch:** `refactor/silence-legacy-pipeline-consumers`.

## Sub-step A.3 — Delete legacy stack

**Goal:** Delete the legacy pipeline + proxy + wrapper
hierarchy in one atomic step. After A.2's silencing, no
remaining consumer outside the legacy stack itself uses
these types.

### A.3.1 Delete legacy proxy package

```bash
git rm -r inqudium-core/src/main/java/eu/inqudium/core/pipeline/proxy/
git rm -r inqudium-core/src/test/java/eu/inqudium/core/pipeline/proxy/
```

Affected: 8 main classes + ~12 test classes.

### A.3.2 Delete legacy imperative proxy bridge

```bash
git rm -r inqudium-imperative/src/main/java/eu/inqudium/imperative/core/pipeline/proxy/
git rm -r inqudium-imperative/src/test/java/eu/inqudium/imperative/core/pipeline/proxy/
```

This also deletes `InqAsyncProxyFactory`,
`AsyncDispatchExtension`, `AsyncPipelineDispatchExtension`.

### A.3.3 Delete legacy `InqPipeline` and Wrapper hierarchy

Files to delete from
`inqudium-core/src/main/java/eu/inqudium/core/pipeline/`:

- `InqPipeline.java`
- `Wrapper.java`
- `BaseWrapper.java`
- `AbstractBaseWrapper.java`
- `RunnableWrapper.java`, `SupplierWrapper.java`,
  `FunctionWrapper.java`, `CallableWrapper.java`
  (function-specific wrappers — verify 0 main importers
  before deletion)
- `LayerAction.java`, `LayerTerminal.java`,
  `ResolvedPipelineState.java`,
  `PipelineOrdering.java`, `PipelineIds.java`,
  `PipelineValidator.java`,
  `SyncPipelineTerminal.java`, `Throws.java`,
  `InqDecorator.java`
- The `function/` sub-package
  (`JoinPointExecutor`, `JoinPointWrapper`,
  `JoinPointConsumer`, etc. — verify scope at A.3
  start)

Plus corresponding test files under
`inqudium-core/src/test/java/eu/inqudium/core/pipeline/`.

### A.3.4 Delete legacy imperative pipeline terminal

```bash
git rm inqudium-imperative/src/main/java/eu/inqudium/imperative/core/pipeline/AsyncPipelineTerminal.java
git rm inqudium-imperative/src/main/java/eu/inqudium/imperative/core/pipeline/InqAsyncDecorator.java
```

(Verify the full scope of the `core/pipeline/` subpackage
in `inqudium-imperative` at A.3 start.)

### A.3.5 Update `inqudium-core/pipeline/package-info.java`

The package now contains very little — possibly nothing
beyond the package-info itself. Either:
- Delete the package-info if nothing useful remains.
- Rewrite to reflect the post-Phase-A state ("legacy
  pipeline types removed; new types live in
  `eu.inqudium.pipeline.*`").

### A.3.6 Sweep verification

```bash
# Should return zero hits
grep -rln "eu\.inqudium\.core\.pipeline\.InqPipeline\b\|eu\.inqudium\.core\.pipeline\.Wrapper\b\|eu\.inqudium\.core\.pipeline\.proxy" \
    inqudium-*/src --include="*.java"
```

If anything remains, it's a missed import in
`inqudium-annotation` / `inqudium-annotation-support` /
`inqudium-pipeline`. These are **deliberately not
migrated** (they bridge through the transitional
`InqPipelineAnnotationEvaluator`). Surface and verify.

### A.3.7 Verification gates for A.3

- [ ] `mvn verify` green across all 23 modules.
- [ ] Legacy proxy package deleted (working tree + Git).
- [ ] Legacy `InqPipeline`, `Wrapper`, etc. deleted.
- [ ] Legacy imperative proxy bridge deleted.
- [ ] `grep -rln "core\.pipeline\.proxy\|InqProxyFactory\|InqAsyncProxyFactory" inqudium-*/src` returns zero hits.
- [ ] `@SuppressWarnings` baseline: documented delta
      (deletions will reduce by ~10-15).
- [ ] **Phase-B-bridge note:** the 3 files in
      `inqudium-annotation` and 1 in
      `inqudium-annotation-support` still import legacy
      `eu.inqudium.core.pipeline.InqPipeline` —
      **intentionally**, via the transitional bridge in
      `inqudium-pipeline/InqPipelineAnnotationEvaluator`.
      This stays until Phase B.

**Pause and ask the maintainer** if:

- A deletion target turns out to have remaining importers
  (in addition to the silenced modules) — indicates the
  audit missed a consumer.
- The `function/` sub-package contains types that have
  unanticipated dependents.
- The Wrapper subtype usage shows differently than the
  audit's "0 main importers" — surface before deleting.

**Estimated effort:** 3-4 hours.

**Branch:** `refactor/delete-legacy-pipeline-stack`.

## Sub-step A.4 — Final cleanup

**Goal:** Polish the post-deletion state. Update ADRs,
clean up empty packages, confirm the result is
self-consistent.

### A.4.1 Update ADR-035

The "legacy proxy preserved" passage in ADR-035 now
describes a historical state. Update:

```markdown
// Was:
The legacy proxy implementation in `eu.inqudium.core.pipeline.proxy`
is preserved untouched per the Strategy-G greenfield-parallel
approach taken during the rewrite. Its removal is a separate
refactor.

// Is now:
The legacy proxy implementation in `eu.inqudium.core.pipeline.proxy`
was preserved untouched during the new proxy's introduction (per the
Strategy-G greenfield-parallel approach taken during the rewrite),
then removed in Phase A of the post-polish refactor sequence
(2026-05-19). The new proxy in `eu.inqudium.proxy` is the sole
proxy implementation in the library.
```

### A.4.2 Update other ADR cross-references

Several ADRs mention the legacy stack:
- ADR-037 references `eu.inqudium.core.pipeline` in the
  module-topology discussion.
- ADR-046 may reference `Wrapper`-hierarchy types.
- ADR-039 (Proposed; awaits Phase B) may reference
  legacy `chainId` semantics.

Update each cross-reference to point at the new types
where appropriate or mark as historical.

Audit at A.4 start:
```bash
grep -rln "core\.pipeline\." docs/adr --include="*.md"
```

### A.4.3 Verify final module structure

Each of the 5 stubbed modules should contain exactly:
- `pom.xml`
- `src/main/java/.../package-info.java` (one file)
- Nothing else under `src/`

The deleted module `inqudium-aspect-integration-tests`
should be gone entirely.

### A.4.4 Final reactor verification

```bash
mvn verify
```

Expected: green across the 23 remaining modules.

### A.4.5 Tick the completion log

Edit this plan document:

```markdown
* [x] A.4 — Final cleanup (2026-05-19, PR #TBD)
```

### A.4.6 Verification gates for A.4

- [ ] `mvn verify` green.
- [ ] ADR-035 updated.
- [ ] Other ADR cross-references updated.
- [ ] 5 stubbed modules have exactly the expected file structure.
- [ ] `inqudium-aspect-integration-tests` is gone.
- [ ] `git grep -l "eu\.inqudium\.core\.pipeline\.InqPipeline\b\|eu\.inqudium\.core\.pipeline\.proxy"`
      returns only the transitional bridge files in
      `inqudium-annotation` and
      `inqudium-annotation-support` (intentional, Phase B).
- [ ] `@SuppressWarnings` baseline: final value documented.

**Estimated effort:** 1-2 hours.

**Branch:** `refactor/legacy-stack-final-cleanup`.

## Total estimated effort

| Sub-step | Estimated effort |
|---|---|
| A.0 | 1 hour (audit, no commit) |
| A.1 + A.2 (bundled) | 4-5 hours |
| A.3 | 3-4 hours |
| A.4 | 1-2 hours |

**Total range:** 9-12 hours of work, distributed over
2-3 working days at sustainable pace.

The pragmatic-silencing strategy substantially reduces
the original 19-30 hour estimate. The trade-off: 5
modules ship as empty stubs until Phase B (or a separate
rewrite) repopulates them.

## Completion log

* [x] A.0 — Audit (no commit) (2026-05-19)
* [x] A.1 + A.2 — Silence consumer modules (2026-05-19, PR #98)
* [ ] A.3 — Delete legacy pipeline stack
* [ ] A.4 — Final cleanup

## Scope discipline

These items are **out of scope** for Phase A:

- **chainId → stackId rename** (Phase B work)
- **`InqStackInfo` sealed interface and adapters** (Phase B)
- **AspectJ / Spring AOP introspection adapters** (Phase B)
- **Rewriting `inqudium-aspect` or `inqudium-spring`**
  with new implementations (Phase B or separate)
- **Cleaning up `inqudium-annotation`'s legacy
  `InqPipeline` references** (Phase B; bridged by
  `InqPipelineAnnotationEvaluator`)
- **Top-level legacy `Bulkhead`, `ImperativeBulkhead`,
  `Bulkhead.of()`** (separate "legacy resilience surface"
  refactor)
- **CircuitBreaker (top-level legacy class)** — not
  deprecated; out of scope

If during execution a Phase B element seems unavoidable,
surface as a finding and pause. The clean separation
between Phase A (delete legacy + silence consumers) and
Phase B (introduce ADR-039 types and rebuild silenced
modules) is worth preserving.

## When unsure

- The codebase is the ground truth. Use `grep` and `find`
  to verify counts and locations.
- Pause and ask if a deletion target has unanticipated
  importers, or if a sub-step's grep verification returns
  unexpected hits.
- ADR-035 is the authoritative reference for the new
  proxy architecture; cross-reference updates in code may
  need attention per sub-step.

## Approach for new findings

1. **Trivial fix in scope** → fix it as part of the
   current sub-step.
2. **Non-trivial but in Phase A scope** → split into a
   pause-and-ask + extension of the sub-step.
3. **Phase B work** → stop, surface, decide. The default
   is "defer to Phase B".
