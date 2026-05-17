# REFACTORING_PROXY_POLISH.md

**Status:** Draft
**Date:** 2026-05-17
**Predecessor:** `REFACTORING_PROXY_REWRITE.md` (deleted with
sub-step 3.14, kept in git history)

## Background

After the proxy rewrite (sub-steps 3.0–3.14), the integration-
example migration (PR #77), and the BOM cleanup (PR #78) landed, a
final code review of `inqudium-proxy` identified **18 findings**.
None are showstoppers; the module is production-ready. But several
warrant cleanup before the next feature cycle:

- one real correctness gap with low trigger probability,
- one piece of dead code that contradicts the documentation,
- a handful of subtle null-handling and inconsistency issues,
- stale documentation references to the deleted plan,
- a few cold-path performance opportunities, and
- a couple of test/edge-case robustness items.

Full review document: see `code-review.md` (separate artefact, not
checked into the repo). This plan bundles the recommended actions
into sub-steps for sequential execution following the same workflow
established by `REFACTORING_PROXY_REWRITE.md`.

## Working principle

**Each sub-step lands as its own PR**, reviewed and merged before
the next sub-step begins. Each sub-step:

1. Touches the smallest set of files needed.
2. Is mechanical enough that another engineer (or another Claude
   instance) can execute it from this plan.
3. Surfaces design questions to the maintainer before deviating.

The completion log at the bottom tracks merged sub-steps. The plan
document itself is deleted in the final sub-step (P.6) once
everything is merged.

## Scope discipline

This plan addresses only the **proxy-module-internal** findings
from `code-review.md`. Out of scope here:

- The library-wide `chainId → stackId` rename (Option-B from
  sub-step 3.12; separate refactor).
- Full ADR-039 implementation (`InqIntrospector`, `InqStackInfo`
  sealed hierarchy, etc.; separate refactor).
- Legacy proxy removal in `eu.inqudium.core.pipeline.proxy`
  (separate refactor).
- Migrations of other integration examples (AspectJ, Spring) to
  the new proxy.

If any of these come into scope mid-refactor, surface to the
maintainer and decide before deviating.

## Sub-steps

### P.0 — Audit (no commit)

**Goal:** Re-verify each of the 18 findings still applies on
`main` HEAD; gather current line numbers (the rewrite is now
several PRs old); produce an audit report mapping each finding to
a concrete location in the codebase.

**Tasks:**

1. For each finding in `code-review.md` §1-§7, run a targeted grep
   or view to confirm the issue is present in the current codebase.
2. Note any finding that no longer applies (false positive or
   already fixed in a subsequent PR).
3. Note any finding whose specifics drifted (e.g., a line moved
   from `MethodDispatchEntryFactory.java:187` to `:195`).
4. Produce `audit-P.0-report.md` with the validated finding list.

**No commit.** This sub-step's output is a working document for the
subsequent sub-steps.

**Report back:** the audit report, the count of confirmed vs.
no-longer-applicable findings, and any new findings discovered
during the audit pass.

---

### P.1 — Polish pass: bundled small fixes

**Goal:** Bundle the small-effort, low-risk fixes into one PR.

**Findings addressed:** 2.1, 3.1, 3.2, 3.3, 4.1, 4.2, 6.2, 6.3,
6.4, 7.4.

**Tasks (in suggested commit order, but all bundled in one PR):**

#### P.1.1 Remove dead `MethodInvoker.invokeAsync` (finding 2.1)

- Remove the `invokeAsync(Object[])` method declaration from
  `invocation/MethodInvoker.java`.
- Remove the override from
  `invocation/MethodHandleInvoker.java`.
- Remove the override from
  `invocation/ReflectiveInvoker.java`.
- Verify no test references `invokeAsync` (none should, per
  the review's grep).

#### P.1.2 Guard `AsyncChainFolder` against null target return (finding 3.1)

In `folding/AsyncChainFolder.java`'s terminal closure (lines ~96-105
in current code), check for `null` before the cast:

```java
if (idx == layers.size()) {
    return (stackId, callId, args) -> {
        Object result;
        try {
            result = invoker.invoke(args);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
        if (result == null) {
            return CompletableFuture.failedFuture(
                    new NullPointerException(
                            "Target method returned null instead of "
                                    + "a CompletionStage"));
        }
        @SuppressWarnings("unchecked")
        CompletionStage<Object> stage = (CompletionStage<Object>) result;
        return stage;
    };
}
```

The suppression count stays at 4 — the unchecked cast moves but
the count is unchanged.

#### P.1.3 Tighten record compact constructors (finding 3.2)

Edit `introspection/MethodLayers.java`:

```java
public MethodLayers {
    Objects.requireNonNull(methodSignature, "methodSignature");
    Objects.requireNonNull(method, "method");
    layerDescriptions = List.copyOf(
            Objects.requireNonNull(layerDescriptions, "layerDescriptions"));
}
```

Edit `introspection/ProxyStackInfo.java`:

```java
public ProxyStackInfo {
    Objects.requireNonNull(targetType, "targetType");
    elements = List.copyOf(
            Objects.requireNonNull(elements, "elements"));
    methodLayers = List.copyOf(
            Objects.requireNonNull(methodLayers, "methodLayers"));
}
```

#### P.1.4 Add cycle guard to `ThrowableUnwrap` (finding 3.3)

Edit `exception/ThrowableUnwrap.java`:

```java
private static final int MAX_UNWRAP_DEPTH = 10;

static Throwable unwrap(Throwable t) {
    Objects.requireNonNull(t, "t");
    Throwable current = t;
    int depth = 0;
    while (depth++ < MAX_UNWRAP_DEPTH
            && (current instanceof InvocationTargetException
            || current instanceof UndeclaredThrowableException)) {
        Throwable cause = current.getCause();
        if (cause == null) {
            return current;
        }
        current = cause;
    }
    return current;
}
```

Replace the existing manual `throw new NullPointerException("t")`
with `Objects.requireNonNull(t, "t")` for consistency.

#### P.1.5 Convert `SyncCacheEntry` to a record (finding 4.1)

Replace `entries/SyncCacheEntry.java` with the record form:

```java
package eu.inqudium.proxy.entries;

import eu.inqudium.proxy.folding.FoldedSyncChain;
import eu.inqudium.proxy.handler.InqInvocationHandler;

import java.util.List;
import java.util.Objects;

/**
 * ... (existing Javadoc unchanged) ...
 */
record SyncCacheEntry(FoldedSyncChain chain, List<String> layerDescriptions)
        implements MethodDispatchEntry {

    SyncCacheEntry {
        Objects.requireNonNull(chain, "chain");
        layerDescriptions = List.copyOf(
                Objects.requireNonNull(layerDescriptions, "layerDescriptions"));
    }

    @Override
    public Object dispatch(Object proxy, InqInvocationHandler handler, Object[] args)
            throws Throwable {
        return chain.run(handler.stackId(), handler.nextCallId(), args);
    }
}
```

The record's auto-generated `layerDescriptions()` accessor
overrides the sealed interface's default method. Apply the same
explicit null-check pattern as P.1.3 to the list param.

#### P.1.6 Symmetrize `ProxyStackAdapter.supports/inspect` null handling (finding 4.2)

Edit `introspection/ProxyStackAdapter.java`:

```java
public static ProxyStackInfo inspect(Object instance) {
    if (!supports(instance)) {
        throw new IllegalArgumentException(
                "instance is not a proxy produced by ProxyDispatcher; "
                        + "use supports(...) to guard before calling inspect(...). "
                        + "Got: " + (instance == null ? "null"
                                : instance.getClass().getName()));
    }
    InqInvocationHandler handler =
            (InqInvocationHandler) Proxy.getInvocationHandler(instance);
    return new ProxyStackInfo(...);
}
```

`null` now flows through `supports(...) == false → IllegalArgumentException`
with a helpful message identifying the input.

#### P.1.7 Cache `inqudium.proxy.invoker` property (finding 6.2)

In `invocation/MethodInvoker.java`, replace the per-call lookup:

```java
private enum InvokerType { MH, REFLECTIVE }

private static final InvokerType DEFAULT_TYPE = readDefaultType();

private static InvokerType readDefaultType() {
    String prop = System.getProperty("inqudium.proxy.invoker", "mh");
    return switch (prop) {
        case "mh" -> InvokerType.MH;
        case "reflective" -> InvokerType.REFLECTIVE;
        default -> throw new IllegalStateException(
                "Unknown invoker type '" + prop + "' for property "
                        + "inqudium.proxy.invoker (expected 'mh' or 'reflective')");
    };
}

static MethodInvoker create(Object target, Method method) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(method, "method");
    return switch (DEFAULT_TYPE) {
        case MH -> new MethodHandleInvoker(target, method);
        case REFLECTIVE -> new ReflectiveInvoker(target, method);
    };
}
```

Class-load failure if the property is malformed (was previously
per-call failure). This is a slight behavioral change: a malformed
property crashes class init instead of the first `create()` call.
Document in the Javadoc.

#### P.1.8 Fix `ProxyBuilder` HashMap sizing (finding 6.3)

In `construction/ProxyBuilder.java`:

```java
Map<Method, MethodDispatchEntry> entries =
        new HashMap<>(plans.size() + OBJECT_METHOD_KINDS.size());
```

#### P.1.9 Hoist `ElementResolver` name→element map (finding 6.4)

Refactor `construction/ElementResolver.java` to allow callers to
build the map once and pass it in:

```java
public static Map<String, InqElement> indexByName(InqPipeline pipeline) {
    return pipeline.elements().stream()
            .collect(Collectors.toMap(InqElement::name, Function.identity()));
}

public static List<InqElement> resolve(
        List<String> names, Map<String, InqElement> byName) {
    Objects.requireNonNull(names, "names");
    Objects.requireNonNull(byName, "byName");
    return names.stream().map(name -> {
        InqElement element = byName.get(name);
        if (element == null) {
            throw new IllegalStateException(/* same message as before */);
        }
        return element;
    }).toList();
}
```

Keep the existing `resolveNames(...)` as a convenience overload
that builds the map internally (for callers who don't have a shared
map). Update `MethodDispatchEntryFactory` and `AsyncEntryBuilder`
to build the map once in `ProxyBuilder.build(...)` and pass it
down through the factory.

**Note:** The `Collectors.toMap` two-arg form is still the
duplicate-key crash from finding 1.1. P.3 addresses that; P.1.9
doesn't change semantics, only structure.

#### P.1.10 Improve `ObjectMethodHandler.toString` format (finding 7.4)

Edit `handler/ObjectMethodHandler.java`'s `toStringImpl`:

```java
private static String toStringImpl(Object proxy, Object realTarget) {
    InvocationHandler h = Proxy.getInvocationHandler(proxy);
    String prefix = (h instanceof InqInvocationHandler ih)
            ? ih.serviceInterface().getSimpleName()
            : proxy.getClass().getSimpleName();
    return prefix + "[" + realTarget + "]";
}
```

Update the corresponding `ObjectMethodHandlerTest.ToStringKind`
test to assert against the service-interface name format
(`"OrderService[target]"` instead of `"$Proxy12[target]"`).

**Tests:** Each task ships its own focused tests where applicable.
P.1.4 is the only one that needs a genuinely new test — for the
cycle guard, construct a Throwable with a self-referential cause
chain and verify `unwrap(...)` terminates.

**Verification gates:**

- [ ] `mvn verify` from repo root green.
- [ ] `@SuppressWarnings` count: 4 in
      `inqudium-proxy/src/main/java`, 1 in
      `inqudium-pipeline/src/main/java` (unchanged).
- [ ] `grep -rn invokeAsync inqudium-proxy/src/main/java` returns
      no hits.
- [ ] Test-count delta: report exactly; expect 0–5 net.
- [ ] `git diff --stat main...HEAD` shows changes only inside
      `inqudium-proxy/`.

**Branch / commit:**

Branch `polish/proxy-bundled-small-fixes`.
Commit subject: `chore(proxy): bundled polish — kill dead code, tighten edges`

---

### P.2 — Documentation sweep

**Goal:** Resolve stale documentation references that survived the
proxy rewrite. Pure Markdown + Javadoc changes; no Java code edits.

**Findings addressed:** 5.1, 5.2 (subsumed by P.1.1), 5.3.

**Tasks:**

#### P.2.1 Stale `REFACTORING_PROXY_REWRITE.md` references (finding 5.1)

Three files reference the deleted plan:

1. **`inqudium-proxy/pom.xml`** — two XML comments at lines ~42
   and ~55. Suggested replacement: drop the sub-step reference,
   keep the substantive content:
   ```xml
   <!--
     Optional dependency: enables the async dispatch path
     (ADR-037 §6). No class inside inqudium-proxy references
     inqudium-imperative on the sync-only loading path.
   -->
   ```
   ```xml
   <!--
     Test-scope only: enables building real InqBulkhead instances
     for the end-to-end smoke tests in
     eu.inqudium.proxy.smoke.RealBulkheadSmokeTest. Not visible
     to production code.
   -->
   ```

2. **`inqudium-proxy/docs/ARCHITECTURE.md`** line ~5 (header).
   The header's "Status: Stable" wording can drop the
   sub-step-deletion parenthesis cleanly.

3. **`inqudium-proxy/docs/ADR-037-DISCIPLINE-FINDING.md`** lines
   4-5 (metadata block). Replace with PR-number references:
   ```
   **Surfaced:** 2026-05-17 (PR #74)
   **Resolved:** 2026-05-17 (PR #75)
   ```

#### P.2.2 Sub-step-N.NN references in production Javadocs (finding 5.3)

Twelve such references survive in main sources. Sweep them:

- `handler/ObjectMethodHandler.java:28`
- `construction/ProxyBuilder.java:23, 49`
- `construction/SyncParadigmValidator.java:20`
- `construction/AsyncEntryBuilder.java:25`
- `entries/MethodDispatchEntry.java:24, 26, 27, 30, 31`
- `invocation/MethodInvoker.java:42, 43` (likely subsumed by
  P.1.1's `invokeAsync` deletion — verify after P.1 lands)

For each, drop the specific sub-step ID and keep the substantive
context. Examples:

- "sub-step 3.10 corrected the seven-value enumeration" →
  "the seven-value enumeration was corrected during the proxy rewrite"
- "sub-step 3.3's transitional bridge" →
  "the transitional `InqPipelineAnnotationEvaluator` bridge"
- "delivered as sub-step 3.6" → drop entirely if the sentence
  works without it

**Don't replace** sub-step refs that point to genuinely useful
historical context (e.g., the `AsyncEntryBuilder` Javadoc's
explanation of the 3.13a discipline finding might be worth keeping
as "the AsyncEntryBuilder extraction in PR #75"). Use judgment.

**Verification gates:**

- [ ] `mvn verify` green (sanity — Javadoc-only changes).
- [ ] `grep -rl REFACTORING_PROXY_REWRITE
      inqudium-proxy/ pom.xml` returns no hits.
- [ ] `grep -rn "sub-step [0-9]\." inqudium-proxy/src/main/java`
      returns no hits (all swept).

**Branch / commit:**

Branch `docs/proxy-stale-reference-sweep`.
Commit subject: `docs(proxy): sweep stale plan + sub-step references`

---

### P.3 — `ElementResolver` name-uniqueness — ADR + fix

**Goal:** Resolve finding 1.1. This is the only finding that
deserves architectural discussion before implementation. Three
possible approaches, none free of trade-offs.

**Finding addressed:** 1.1.

**Three options sketched:**

#### Option A — Tighten ADR-040 to require globally-unique names

ADR-040 currently says `(elementType, name)` is the unique key.
Change to: `name` is globally unique across the pipeline.
`InqPipelineBuilder` rejects duplicates at build time with a clear
message.

**Pros:** Simplest implementation. `ElementResolver` is correct
as-is. All consumers benefit from the simpler uniqueness invariant.

**Cons:** Backwards-incompatible for any user currently relying on
same-named different-typed elements. Probably nobody, but verify
across the codebase first.

#### Option B — Make `ElementResolver` key on `(elementType, name)`

The annotation evaluator's plan would need to carry element-type
information per layer reference (currently it carries names only).
`MethodPlan.Decorated.elementNamesOuterToInner` becomes
`elementsOuterToInner` carrying `(type, name)` pairs.

**Pros:** Pure additive change; no user impact.

**Cons:** Bigger refactor. Touches `inqudium-annotation`'s
evaluator, the `MethodPlan` types, and the proxy module. Likely a
multi-PR effort.

#### Option C — Throw a clearer error from the resolver

Keep the current uniqueness assumption but replace the
`Collectors.toMap` two-arg form with a manual loop that throws
`InqAnnotationConfigurationException` with a precise message:

```java
Map<String, InqElement> byName = new LinkedHashMap<>();
for (InqElement el : pipeline.elements()) {
    InqElement prev = byName.put(el.name(), el);
    if (prev != null) {
        throw new InqAnnotationConfigurationException(
                "Pipeline element name '" + el.name() + "' is used by both "
                        + "'" + prev.elementType() + "' and "
                        + "'" + el.elementType() + "'. "
                        + "The proxy's annotation-driven dispatch resolves "
                        + "elements by name only; either rename one of the "
                        + "elements or wait for the planned `(type, name)` "
                        + "evaluator extension (Option B in the polish plan).");
    }
}
```

**Pros:** Minimal change. User-friendly error. Doesn't require
deciding the architecture question now.

**Cons:** Doesn't actually fix the limitation; punts the question.

**Recommended path:**

1. **First:** maintainer decides which option. The decision is an
   architectural question that affects ADR-040 and possibly
   ADR-036.
2. **Then:** implementation follows the chosen path.

If maintainer prefers **Option A**, the implementation lives in
`inqudium-pipeline` (the builder), not `inqudium-proxy`. The
plan's scope expands beyond the proxy module — surface and decide.

If **Option B**, plan a multi-PR sequence:
- PR 1: `MethodPlan.Decorated` carries `(type, name)` pairs.
  Evaluator updated. Backwards-compatible (existing call sites get
  a default-typed entry).
- PR 2: Proxy's `ElementResolver` and factories consume the new
  shape.
- PR 3: Cleanup of any transitional adapters.

If **Option C** (the minimal punt), one PR in the proxy module.

**Verification gates:** depend on the chosen option. At minimum:

- [ ] A test case with two same-named elements of different types
      produces a clear error message (Option A or C) or works
      correctly (Option B).
- [ ] All existing tests pass.

**Branch / commit:**

Depends on chosen option. Recommended branches:
- Option A: `feat/element-name-global-uniqueness`
- Option B: `feat/plan-typed-element-references`
- Option C: `fix/element-resolver-duplicate-name-message`

**Report back:** the option chosen with rationale; the
implementation diff; the test case demonstrating the fix.

---

### P.4 — Folder lambda hoisting (JMH-validated decision)

**Goal:** Decide finding 6.1 based on data, not intuition. The
inner-LayerTerminal lambdas in `SyncChainFolder` and
`AsyncChainFolder` are currently allocated per-call; the review
proposed hoisting them to fold-time. Whether this is worth the
change depends on JIT escape-analysis behaviour in real workloads.

**Finding addressed:** 6.1.

**Tasks:**

#### P.4.1 Set up a JMH micro-benchmark

If the project doesn't already have a JMH module, decide where to
put it. Options:
- New module `inqudium-proxy-benchmarks` (preferred for
  isolation).
- Existing test-scope sub-tree.

Benchmark surface (minimum):
- N-layer chain (N = 1, 3, 6) of trivial sync layers wrapping a
  no-op target.
- Same shape async.
- Measure per-call time, allocation rate, and GC pressure.

#### P.4.2 Run benchmarks against current code

Baseline measurement on `main` HEAD before any code change. Pin the
results in a `before/` directory under the benchmark module.

#### P.4.3 Apply the hoisting refactor (only the code change)

In `folding/SyncChainFolder.foldRecursive`:

```java
LayerAction<Object[], Object> head = layers.get(idx);
FoldedSyncChain tail = foldRecursive(layers, idx + 1, invoker);

// Hoisted: allocated once at fold time, captured into the outer lambda.
LayerTerminal<Object[], Object> nextForHead = (s, c, a) -> {
    try { return tail.run(s, c, a); }
    catch (Throwable t) { throw Throws.rethrow(t); }
};

return (stackId, callId, args) ->
        head.execute(stackId, callId, args, nextForHead);
```

Same for `AsyncChainFolder`.

#### P.4.4 Re-run benchmarks; commit decision

Compare before/after:
- **Significant improvement** (>5% throughput or measurable
  allocation reduction): keep the hoisted version, ship the PR
  with the benchmark module.
- **No measurable improvement** (JIT successfully eliminates the
  per-call lambdas): revert the code change, keep the benchmark
  module, document the finding ("hoisting verified
  unnecessary — JIT handles it"). The benchmark itself is a
  permanent regression guard.

**Verification gates:**

- [ ] Benchmark module exists and runs.
- [ ] Before/after numbers documented in the PR description (or
      a markdown file alongside the benchmark module).
- [ ] If hoisted: all existing tests still pass (retry semantics
      unchanged — the closures-per-depth structure is preserved).

**Branch / commit:**

Branch `perf/proxy-folder-lambda-hoisting`.

Commit subject depends on outcome:
- Kept: `perf(proxy): hoist per-call LayerTerminal closures to fold time`
- Reverted-with-benchmark: `test(proxy): JMH benchmark for chain dispatch`

**Report back:** before/after numbers, the decision, and the
diff (if changed).

---

### P.5 — Test-infrastructure hardening (optional)

**Goal:** Address finding 7.1 (classpath filter robustness) and
optionally document findings 7.2 and 7.3 (JPMS access edge cases)
in the appropriate Javadoc.

**Findings addressed:** 7.1, 7.2 (docs only), 7.3 (docs only).

**Tasks:**

#### P.5.1 Tighten `ModuleLoadingDisciplineTest` classpath filter (finding 7.1)

In
`test/.../discipline/ModuleLoadingDisciplineTest.currentClasspathURLs`,
replace:

```java
.filter(p -> !excludeImperative || !p.contains("inqudium-imperative"))
```

With a stricter pattern:

```java
.filter(p -> !excludeImperative
        || !(p.contains("/inqudium-imperative/")
            || p.contains("\\inqudium-imperative\\")  // Windows
            || p.endsWith("inqudium-imperative.jar")
            || p.matches(".*inqudium-imperative-[\\d.]+(-SNAPSHOT)?\\.jar")))
```

Run the test multiple times to ensure no false negative (the JAR
still gets excluded under standard Maven layouts).

#### P.5.2 Document JPMS edge cases (findings 7.2, 7.3)

Add a short paragraph to `invocation/MethodHandleInvoker`'s
Javadoc (finding 7.2):

```
<p><strong>JPMS note.</strong> The reflect-and-bind step uses
{@link MethodHandles#lookup()}, which sees public methods on
public types only. For service interfaces or implementation
classes that are non-public or non-exported in a JPMS deployment,
construction will fail with IllegalAccessException. Application
authors deploying under strict JPMS should ensure their service
interfaces are exported.</p>
```

Add a similar paragraph to `invocation/ReflectiveInvoker` Javadoc
(finding 7.3):

```
<p><strong>JPMS note.</strong> The constructor calls
{@code setAccessible(true)} on the target method, which under
strict JPMS configurations requires the target's module to be
open to {@code inqudium.proxy}. Users running with
{@code inqudium.proxy.invoker=reflective} on a strict JPMS
deployment may need an {@code --add-opens} JVM flag. The default
{@code mh} path is not affected.</p>
```

**Verification gates:**

- [ ] `mvn verify` green.
- [ ] Discipline test still passes under standard layout.

**Branch / commit:**

Branch `test/proxy-discipline-classpath-hardening`.
Commit subject: `test(proxy): tighten classpath filter, document JPMS notes`

---

### P.6 — Plan deletion + final architecture polish

**Goal:** After all polish PRs have merged, delete this plan
document. Sanity-check `ARCHITECTURE.md` for any remaining drift.

**Tasks:**

1. **Verify:** all sub-steps P.0 through P.5 are merged into main.
2. **Run a final consistency check** on `ARCHITECTURE.md`:
   - All file paths and class names match the current code
   - `@SuppressWarnings` count statement is accurate
   - No "(planned)" markers remain
   - Status line still says "Stable"
3. **Delete `REFACTORING_PROXY_POLISH.md`.** Git history preserves it.

**Verification gates:**

- [ ] `REFACTORING_PROXY_POLISH.md` no longer in the working tree.
- [ ] No grep hits for `REFACTORING_PROXY_POLISH` or
      `polish plan` anywhere in the codebase.

**Branch / commit:**

Branch `chore/polish-plan-deletion`.
Commit subject: `chore: complete proxy polish, delete plan`

---

## Completion log

* [ ] P.0 — Audit (no commit)
* [ ] P.1 — Polish pass: bundled small fixes
* [ ] P.2 — Documentation sweep
* [ ] P.3 — `ElementResolver` name-uniqueness — ADR + fix
* [ ] P.4 — Folder lambda hoisting (JMH-validated decision)
* [ ] P.5 — Test-infrastructure hardening (optional)
* [ ] P.6 — Plan deletion + final architecture polish

---

## Estimated effort

A rough estimate for someone familiar with the codebase:

| Sub-step | Effort |
|---|---|
| P.0 | 30 minutes |
| P.1 | half a day (one focused afternoon) |
| P.2 | 1 hour |
| P.3 | depends on option: Option C ~1 hour, Option A ~4 hours, Option B ~1-2 days across multiple PRs |
| P.4 | 1 day (JMH setup is the biggest unknown) |
| P.5 | 1-2 hours |
| P.6 | 15 minutes |

Total range: 1 day (P.1 + P.2 + P.5 + P.6 if P.3 picks Option C
and P.4 stays in-repo as a benchmark only) to about 1 week (if
P.3 picks Option B and P.4 ends up applying the hoist with full
JMH validation).

P.1 and P.2 are the highest-value-per-hour. P.3 deserves a real
architecture discussion. P.4 is a "do it when there's an
afternoon to spare" item — the perf impact is theoretical.

## When unsure

Search the code first. The audit report from P.0 is the source of
truth for current finding locations. When the audit and
`code-review.md` disagree, the audit wins (it's based on current
HEAD; the review is from 2026-05-17).
