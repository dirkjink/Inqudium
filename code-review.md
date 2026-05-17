# Inqudium-Proxy — Final Code Review

**Reviewer:** Claude (final-pass after 3.14 + BOM cleanup)
**Date:** 2026-05-17
**Scope:** Complete `inqudium-proxy` module sources (42 main files,
2433 LOC) plus docs and pom.

## Executive summary

The module is in **good shape**. The proxy rewrite delivered a
clean architecture with consistent code style, strong separation
between hot path and cold path, and explicit class-loading
discipline that's empirically guarded.

I found **18 issues** in total:

| Severity | Count | Examples |
|---|---|---|
| Real correctness bug | 1 | `ElementResolver` duplicate-key crash on legal pipeline |
| Dead code | 1 | `MethodInvoker.invokeAsync` (contradicts docs) |
| Subtle correctness | 3 | Null-returning target, missing null-checks in records, no cycle guard in unwrapper |
| Inconsistency | 2 | Sync vs async entries class shape, supports/inspect null handling |
| Stale docs | 3 | References to deleted plan file, false forward-looking Javadoc |
| Perf opportunity | 4 | Lambda hoisting, property caching, sizing, resolver caching |
| Robustness | 4 | classpath filter, accessibility on JPMS, toString format, FQN robustness |

None of them are showstoppers. Most are either polish (perf opps,
stale Javadoc) or defensive hardening. The single real correctness
bug (`#1`) requires an unusual configuration to trigger but is
worth fixing.

---

## 1. Real correctness issues

### 1.1 🐛 `ElementResolver` assumes globally unique element names

**File:** `construction/ElementResolver.java`, lines ~50-52

```java
Map<String, InqElement> byName = pipeline.elements().stream()
        .collect(Collectors.toMap(InqElement::name, Function.identity()));
```

`Collectors.toMap()` with two-arg form throws
`IllegalStateException: Duplicate key` on the first duplicate.

**The latent bug:** ADR-040 documents pair-uniqueness on
`(elementType, name)`. Two elements of different types CAN share a
name — e.g., a `Bulkhead("foo")` and a `Retry("foo")`. Such a
pipeline would crash `ProxyBuilder.build(...)` at the
`Collectors.toMap` line with a confusing message ("Duplicate key:
foo") rather than at a clear validation site.

**Why this is real:** Users naming related elements consistently
("orderProcessing" for both the retry and the bulkhead on the
order-processing path) is a perfectly reasonable pattern.

**Severity:** Real but low-frequency. Triggered only by name
collisions across element types — most users avoid this naturally.

**Two possible fixes:**

(a) Tighten ADR-040 to require globally-unique names. The
`InqPipelineBuilder` would then reject the duplicate at build time
with a clear message. (Behavioural change for users.)

(b) Make `ElementResolver` tolerate same-name duplicates by
keying on `(elementType, name)` instead of `name`. The annotation
evaluator's plan would need to carry element-type information
too — bigger refactor.

(c) Keep current code but document the restriction explicitly in
`ElementResolver`'s Javadoc and add a clearer error path: use
`Collectors.toMap(InqElement::name, Function.identity(), (a, b) -> a, ...)`
to silently keep one (bad) OR use a manual loop that throws an
explicit `InqAnnotationConfigurationException` (better).

Worth a follow-up ADR/PR.

---

## 2. Dead code

### 2.1 🐛 `MethodInvoker.invokeAsync` is dead and contradicts the architecture

**Files:**
- `invocation/MethodInvoker.java` lines 42-50 (interface)
- `invocation/MethodHandleInvoker.java` lines 38-41 (impl)
- `invocation/ReflectiveInvoker.java` lines 43-46 (impl)

The interface declares `Object invokeAsync(Object[] args)`. Both
implementations override it as a trivial `return invoke(args);`.
**Nobody calls it.** Verified by `grep -rn invokeAsync` — three
hits, all definitions, zero callers.

ARCHITECTURE.md §11 explicitly states this method should not exist:

> Async invocation does not use a separate `invokeAsync(...)`
> method on `MethodInvoker`; the same synchronous `invoke(...)`
> returns a `CompletionStage` for async methods (the return type
> is decided by the target's method signature, not the invoker).

The `MethodInvoker` Javadoc on `invokeAsync` is also forward-
looking-and-false: "sub-step 3.11 (async dispatch) may specialise
it" — 3.11 happened, didn't specialise.

**Fix:** Remove `invokeAsync` from interface and both
implementations. One short PR; no behavior change.

---

## 3. Subtle correctness issues

### 3.1 ⚠ `AsyncChainFolder` terminal returns `null` if the target returns `null`

**File:** `folding/AsyncChainFolder.java`, lines 96-105

```java
return (stackId, callId, args) -> {
    try {
        @SuppressWarnings("unchecked")
        CompletionStage<Object> stage =
                (CompletionStage<Object>) invoker.invoke(args);
        return stage;   // returns null if invoke() returned null
    } catch (Throwable t) {
        return CompletableFuture.failedFuture(t);
    }
};
```

If the target method's body returns `null` instead of a real
`CompletionStage` (poorly-written target code, but legal Java),
the cast on null succeeds silently. The terminal then returns
`null`. Downstream layer code (`.thenCompose(...)`, `whenComplete`,
etc.) would NPE deep in the layer rather than at the source.

**Severity:** Low — well-behaved async methods always return a
non-null stage. But the failure mode when this rule is violated is
confusing.

**Fix:** Guard against null:
```java
Object result = invoker.invoke(args);
if (result == null) {
    return CompletableFuture.failedFuture(
            new NullPointerException(
                    "Target method returned null CompletionStage"));
}
return (CompletionStage<Object>) result;
```

### 3.2 ⚠ Record compact constructors skip null-checks for list parameters

**Files:** `introspection/MethodLayers.java`, `introspection/ProxyStackInfo.java`

`MethodLayers`:
```java
public MethodLayers {
    Objects.requireNonNull(methodSignature, "methodSignature");
    Objects.requireNonNull(method, "method");
    layerDescriptions = List.copyOf(layerDescriptions);  // NPE-source if null
}
```

`ProxyStackInfo`:
```java
public ProxyStackInfo {
    Objects.requireNonNull(targetType, "targetType");
    elements = List.copyOf(elements);          // NPE-source if null
    methodLayers = List.copyOf(methodLayers);  // NPE-source if null
}
```

`List.copyOf(null)` throws NPE with an unhelpful message
("Cannot invoke ... because the parameter is null"). The other
params get the helpful `Objects.requireNonNull` message. The
inconsistency makes debugging callers harder.

**Severity:** Low — only affects clarity of error messages.

**Fix:** Add explicit null-checks before `List.copyOf`:
```java
layerDescriptions = List.copyOf(
        Objects.requireNonNull(layerDescriptions, "layerDescriptions"));
```

### 3.3 ⚠ `ThrowableUnwrap` has no cycle guard

**File:** `exception/ThrowableUnwrap.java`, lines 26-37

```java
Throwable current = t;
while (current instanceof InvocationTargetException
        || current instanceof UndeclaredThrowableException) {
    Throwable cause = current.getCause();
    if (cause == null) { return current; }
    current = cause;
}
return current;
```

If a malicious or buggy throwable carries a cyclic cause chain
(`a.getCause() == a`, or `a → b → a`), this loop never terminates.

JDK `Throwable.initCause()` rejects self-reference (throws
`IllegalArgumentException` for `t.initCause(t)`), but **does not
prevent cycles set via the constructor**. A two-step cycle through
custom subclasses bypassing initCause is theoretically constructible.

**Severity:** Very low — never seen in practice; pure defensive
hardening.

**Fix:** Bound the loop. 10 iterations is more than any real
unwrapping ever needs.
```java
int depth = 0;
while (depth++ < 10 && (current instanceof InvocationTargetException
        || current instanceof UndeclaredThrowableException)) {
    ...
}
return current;
```

---

## 4. Inconsistencies

### 4.1 `SyncCacheEntry` is a class; `AsyncCacheEntry` and `ObjectMethodEntry` are records

**Files:** `entries/SyncCacheEntry.java` (class), `entries/AsyncCacheEntry.java` (record), `entries/ObjectMethodEntry.java` (record)

All three entry types have the same shape: `(chain, layerDescriptions)`
or `(kind)`. `SyncCacheEntry` is a `final class` with explicit
fields, constructor, and `@Override layerDescriptions()` accessor;
the other two are records.

This is a sub-step-3.7 leftover — `SyncCacheEntry` predates the
default `layerDescriptions()` method on the sealed interface.
Today it could be a record:

```java
record SyncCacheEntry(FoldedSyncChain chain, List<String> layerDescriptions)
        implements MethodDispatchEntry {
    SyncCacheEntry {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(layerDescriptions, "layerDescriptions");
        layerDescriptions = List.copyOf(layerDescriptions);
    }
    @Override
    public Object dispatch(Object proxy, InqInvocationHandler handler, Object[] args)
            throws Throwable {
        return chain.run(handler.stackId(), handler.nextCallId(), args);
    }
}
```

The record's auto-generated `layerDescriptions()` accessor naturally
overrides the default. **Cleaner and shorter** — and matches the
other two.

**Severity:** Style only.

### 4.2 `ProxyStackAdapter.supports(null)` returns false but `inspect(null)` throws NPE

**File:** `introspection/ProxyStackAdapter.java`

```java
public static boolean supports(Object instance) {
    if (instance == null) { return false; }
    ...
}

public static ProxyStackInfo inspect(Object instance) {
    Objects.requireNonNull(instance, "instance");  // NPE for null
    if (!supports(instance)) { throw new IllegalArgumentException(...); }
    ...
}
```

Two different error surfaces for `null`: silent `false` vs explicit
`NullPointerException`. A symmetric design would be `inspect(null)`
throws `IllegalArgumentException` with the same message as the
non-supported branch ("use supports(...) to guard").

**Severity:** Very low — API ergonomics.

---

## 5. Documentation issues

### 5.1 Stale references to the deleted `REFACTORING_PROXY_REWRITE.md`

Three files reference the deleted plan:
- `pom.xml` lines 42 and 55 (two `<!-- ... REFACTORING_PROXY_REWRITE.md ... -->` blocks)
- `docs/ARCHITECTURE.md` line 5 (in the header)
- `docs/ADR-037-DISCIPLINE-FINDING.md` lines 4 and 5 (Surfaced/Resolved metadata)

After 3.14's plan deletion, these are dangling references. They
don't break builds but they confuse readers who try to follow them.

**Fix:** Replace mentions with either git-history-equivalent text
("delivered during the proxy rewrite, completed YYYY-MM-DD") or
specific PR-number references.

### 5.2 `MethodInvoker.invokeAsync` Javadoc is forward-looking-and-false

**File:** `invocation/MethodInvoker.java` lines 42-44

```java
/**
 * Async-context invocation. At sub-step 3.5 the implementation is
 * identical to {@link #invoke}; sub-step 3.11 (async dispatch) may
 * specialise it.
 * ...
 */
Object invokeAsync(Object[] args) throws Throwable;
```

Predicts a future that didn't happen — and is now dead code
(see issue 2.1). Self-evident once the method is removed.

### 5.3 Sub-step references in production Javadocs

Twelve sub-step-N.NN references survive in main sources:

```
handler/ObjectMethodHandler.java:28      "sub-step 3.10"
construction/ProxyBuilder.java:23,49     "sub-step 3.3", "sub-step 3.9"
construction/SyncParadigmValidator.java:20 "sub-step 3.11"
construction/AsyncEntryBuilder.java:25   "sub-step 3.13's PR"
entries/MethodDispatchEntry.java:24,26,27,30,31 (five marks)
invocation/MethodInvoker.java:42,43      "sub-step 3.5", "sub-step 3.11"
```

These point to a document that no longer exists. They're not
broken in the sense of "won't compile" — they're textual
descriptions of historical decisions. A reader looking up
"sub-step 3.10" today finds nothing.

**Severity:** Low — historical references. Could be left as-is
(history captured in git log) or replaced with timeless prose.

**Suggestion:** Bulk substitute pattern, e.g.,
`s/sub-step 3\.10/the proxy rewrite (ARCHITECTURE.md §10)/`. Drop
the specific sub-step IDs; keep the substantive content.

---

## 6. Performance opportunities (all cold-path or near-cold)

### 6.1 Inner lambda allocation in folders is per-call, not per-fold

**Files:** `folding/SyncChainFolder.java` lines 99-109,
`folding/AsyncChainFolder.java` lines 110-114

In `SyncChainFolder.foldRecursive`:
```java
return (stackId, callId, args) -> {
    LayerTerminal<Object[], Object> nextForHead = (s, c, a) -> {  // allocated per CALL
        try { return tail.run(s, c, a); }
        catch (Throwable t) { throw Throws.rethrow(t); }
    };
    return head.execute(stackId, callId, args, nextForHead);
};
```

The `nextForHead` LayerTerminal is allocated on **every dispatch**.
For an N-layer chain, that's N allocations per method call.

The Javadoc claims this is "essential for retry semantics" — but
on inspection that's not true. `nextForHead` is stateless; it just
delegates to `tail.run(...)`. Hoisting it out of the outer lambda
to fold time wouldn't change retry semantics, because each call to
`tail.run(...)` is independent and re-enters its own inner chain.

```java
LayerAction<Object[], Object> head = layers.get(idx);
FoldedSyncChain tail = foldRecursive(layers, idx + 1, invoker);

// Allocated ONCE at fold time, captured into the outer lambda below.
LayerTerminal<Object[], Object> nextForHead = (s, c, a) -> {
    try { return tail.run(s, c, a); }
    catch (Throwable t) { throw Throws.rethrow(t); }
};

return (stackId, callId, args) -> head.execute(stackId, callId, args, nextForHead);
```

**Severity:** Low. JIT escape analysis often eliminates short-lived
lambdas, so the runtime impact is typically negligible. But the
hoisted form is clearer AND eliminates the allocation
unconditionally — wins regardless of JIT behavior.

Apply the same hoist to `AsyncChainFolder`.

**Recommendation:** validate with a JMH micro-benchmark; if
escape-analysis evidence shows JIT eliminates the allocations, leave
as-is. Otherwise hoist.

### 6.2 `System.getProperty` lookup per `MethodInvoker.create` call

**File:** `invocation/MethodInvoker.java` line 66

```java
static MethodInvoker create(Object target, Method method) {
    ...
    String type = System.getProperty("inqudium.proxy.invoker", "mh");  // per call
    return switch (type) { ... };
}
```

Called once per service-interface method during proxy construction.
For a 15-method service, 15 property lookups.

**Fix:** Cache as `private static final` enum/boolean at class load.

**Severity:** Trivial. Cold path, microseconds.

### 6.3 `HashMap` size hint in `ProxyBuilder.build` undersizes by 3

**File:** `construction/ProxyBuilder.java` line 104

```java
Map<Method, MethodDispatchEntry> entries = new HashMap<>(plans.size());
// then adds 3 entries for equals/hashCode/toString
```

The map is initialized for `plans.size()` entries, then 3 Object-
method entries are added. Default load factor 0.75 means the map
resizes once at `plans.size() * 0.75`. The +3 entries push past
that for most interface sizes (5-20 methods).

**Fix:** `new HashMap<>(plans.size() + 3)`.

**Severity:** Trivial.

### 6.4 `ElementResolver.resolveNames` builds name→element map per call

**File:** `construction/ElementResolver.java` lines 50-52

The `Map<String, InqElement> byName` is built fresh on every
`resolveNames(...)` call. For an N-method service, that's N builds.

**Fix:** Hoist to `ProxyBuilder.build` — build once, pass into
`MethodDispatchEntryFactory.createEntry(...)`.

**Severity:** Trivial. Cold path.

---

## 7. Robustness / edge cases

### 7.1 `ModuleLoadingDisciplineTest` classpath filter is fragile

**File:** `test/.../discipline/ModuleLoadingDisciplineTest.java` line 207

```java
.filter(p -> !excludeImperative || !p.contains("inqudium-imperative"))
```

Filters out classpath entries whose path string contains the
literal "inqudium-imperative". Works for typical Maven layouts
(`.m2/repository/eu/inqudium/inqudium-imperative/...`). Would fail
in unusual setups:
- IDE-shadow JARs renamed without the original artifact ID
- CI build paths containing "inqudium-imperative" as a parent dir
  for some other reason

**Severity:** Very low. Test-only.

**Suggested hardening:** match jar-name endings more precisely
(`p.endsWith("inqudium-imperative.jar") || p.contains("/inqudium-imperative/")`).

### 7.2 `MethodHandleInvoker.unreflect` uses local-class lookup

**File:** `invocation/MethodHandleInvoker.java` lines 22-26

```java
MethodHandle raw = MethodHandles.lookup().unreflect(method);
```

`MethodHandles.lookup()` returns the lookup context of the
calling class — `eu.inqudium.proxy.invocation`. This sees public
methods on public types only across packages. Non-public
interfaces or methods would fail with `IllegalAccessException`.

For typical user code (public service interface with public
methods on a public impl class), this works. For non-public test
fixtures, the user would need to make them public — a documented
lesson from 3.5.

**Severity:** Low. The IllegalAccessException case at construction
time produces a clear IllegalStateException with the wrapped cause.

**Future-proofing:** `MethodHandles.privateLookupIn(declaringClass, MethodHandles.lookup())`
would honor JPMS open-package rules — only needed if users start
hitting JPMS-strict deployment.

### 7.3 `ReflectiveInvoker.setAccessible(true)` can fail under JPMS

**File:** `invocation/ReflectiveInvoker.java` line 27

For users running a JPMS deployment without `--add-opens` to the
target module, `setAccessible(true)` throws
`InaccessibleObjectException`. The constructor would propagate it.

**Severity:** Low. Affects only `inqudium.proxy.invoker=reflective`
profile, which is benchmark-only per ARCHITECTURE.md §11. The
default `mh` path is unaffected.

### 7.4 `ObjectMethodHandler.toString` format is ugly

**File:** `handler/ObjectMethodHandler.java` line 109

```java
return proxy.getClass().getSimpleName() + "[" + realTarget + "]";
```

`proxy.getClass().getSimpleName()` on a JDK proxy returns
something like `$Proxy12` — the JDK-internal synthesized name.
The example output we saw was `$Proxy12[Target#7]`.

A nicer alternative: read the service interface from the handler
and use **its** simple name:
```java
private static String toStringImpl(Object proxy, Object realTarget) {
    InvocationHandler h = Proxy.getInvocationHandler(proxy);
    if (h instanceof InqInvocationHandler ih) {
        return ih.serviceInterface().getSimpleName() + "[" + realTarget + "]";
    }
    return proxy.getClass().getSimpleName() + "[" + realTarget + "]";
}
```

Would render as `OrderService[Target#7]` — much more informative.

**Severity:** Cosmetic. Affects log output and `Object.toString` calls.

---

## 8. Things I checked and found clean

Positive findings — for confidence:

- ✅ **No `synchronized` or `volatile`** anywhere in main sources
  (verified — matches CLAUDE.md ADR-008).
- ✅ **No `Thread.sleep`, `System.nanoTime`, or `Instant.now`** in
  main sources.
- ✅ **No new internal `chainId → stackId` renames** — Option-B
  scope discipline holds.
- ✅ **`BootstrapMethods` discipline correctly maintained** — no
  `eu.inqudium.imperative.*` import in
  `MethodDispatchEntryFactory.java` (the file that caused the
  3.13 violation). Verified by grep — clean.
- ✅ **Sealed family fully bounded** — `MethodDispatchEntry`
  permits exactly 5 records.
- ✅ **`@SuppressWarnings` count is actually 4 in proxy** (not 5
  as some earlier reports said) — 3.14's optional cleanup of
  `toLayerAction` did apply. Locations:
  - `folding/SyncChainFolder.java:80`
  - `folding/AsyncChainFolder.java:83` (list cast)
  - `folding/AsyncChainFolder.java:98` (terminal CompletionStage cast)
  - `construction/ProxyBuilder.java:97` (implClass cast)
- ✅ **Public/internal API separation clear** via "Internal API"
  Javadoc tag — every class that's `public` for cross-package
  reason is so marked.
- ✅ **Annotation-driven impl-class scan honored** — no annotation
  read off interfaces.
- ✅ **Lazy-initialization patterns correct** — `DetectionAsync`'s
  static init uses `Class.forName(..., false, loader)` so the
  probed class isn't initialized.
- ✅ **`Map.copyOf(...)` and `List.copyOf(...)` used consistently**
  for defensive immutability.
- ✅ **No `Optional` field types** that could be null
  (anti-pattern) — `Optional<Method>` is only in record components,
  which is the correct usage.
- ✅ **Sealed family pattern-match exhaustive** — the switch in
  `MethodDispatchEntryFactory.createEntry` covers both
  `MethodPlan` permits, no default arm needed.

---

## 9. Recommended actions, prioritized

Rough ranking. None of these are urgent.

| # | Action | Effort |
|---|---|---|
| 1 | Remove dead `MethodInvoker.invokeAsync` (issue 2.1) | 10 min |
| 2 | Fix or document `ElementResolver` name-uniqueness assumption (issue 1.1) | 1–4 hours depending on chosen fix |
| 3 | Add null-checks to record compact constructors (issue 3.2) | 10 min |
| 4 | Sweep stale `REFACTORING_PROXY_REWRITE.md` references (issue 5.1) | 15 min |
| 5 | Guard `AsyncChainFolder` against null target return (issue 3.1) | 15 min |
| 6 | Convert `SyncCacheEntry` to a record (issue 4.1) | 15 min |
| 7 | Add cycle guard to `ThrowableUnwrap` (issue 3.3) | 10 min |
| 8 | Improve `ObjectMethodHandler.toString` (issue 7.4) | 30 min incl. test |
| 9 | Symmetrize `ProxyStackAdapter.supports/inspect` null (issue 4.2) | 10 min |
| 10 | Sub-step Javadoc sweep (issue 5.3) | 30 min |
| 11 | Lambda hoisting in folders (issue 6.1) | 1 hour incl. JMH check |
| 12 | Cache `inqudium.proxy.invoker` property (issue 6.2) | 10 min |
| 13 | HashMap sizing fix (issue 6.3) | 5 min |
| 14 | Hoist `ElementResolver` map (issue 6.4) | 20 min |
| 15 | Harden `ModuleLoadingDisciplineTest` classpath filter (issue 7.1) | 15 min |

A reasonable "polish PR" could bundle: 1, 3, 4, 5, 6, 7, 9, 12, 13.
Probably an afternoon's work, no behavioral change visible to
users beyond cleaner error messages.

Issue 2 (`ElementResolver`) deserves its own ADR-level discussion
before fixing — three plausible approaches with different
trade-offs. Don't bundle.

Issue 11 (folder hoisting) deserves its own perf-focused PR with
JMH evidence.

---

## 10. Overall assessment

After 14 sub-steps + plan deletion + BOM cleanup, **the module is
production-ready**. The issues I found are typical of any
non-trivial codebase: dead code from forward-planning, a couple of
subtle null-handling gaps, some stale documentation references,
and minor perf opportunities. Nothing structural is wrong.

The architecture is sound — the ADR-037 §6 discipline (which was
the trickiest invariant to maintain) is empirically guarded by
tests. The sealed family design is clean. Hot-path code is small
and focused. The introspection adapter is well-designed for future
ADR-039 integration without breaking changes.

Good work overall.
