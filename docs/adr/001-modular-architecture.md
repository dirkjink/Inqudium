# ADR-001: Modular architecture

**Status:** Accepted  
**Date:** 2026-03-22  
**Deciders:** Core team

## Context

Resilience libraries tend to ship as a single artifact or a small set of large modules. This forces consumers to pull in code they don't need — a project that only wants a Circuit Breaker still gets Rate Limiter, Bulkhead, and Cache on the classpath.

We need a module strategy that balances granularity with practicality across two very different use cases: imperative Java projects that may only need one or two patterns, and reactive/coroutine projects that typically want the full set in a single paradigm.

## Decision

We adopt a **hybrid module strategy**:

### Imperative Java — fine-grained, one element per module

Each resilience element is its own Maven module with its own artifact:

- `inqudium-circuitbreaker`
- `inqudium-retry`
- `inqudium-ratelimiter`
- `inqudium-bulkhead`
- `inqudium-timelimiter`
- `inqudium-cache`

Each module depends only on `inqudium-core` and has zero transitive dependencies to other elements.

### Paradigm modules — one module per paradigm, all elements included

For Kotlin Coroutines, Project Reactor, and RxJava 3, all element implementations ship in a single module:

- `inqudium-kotlin` — all elements, native coroutine implementations
- `inqudium-reactor` — all elements, native Reactor implementations
- `inqudium-rxjava3` — all elements, native RxJava 3 implementations

The rationale: a project that adopts a reactive paradigm typically wants the full resilience toolkit in that paradigm. Splitting into `inqudium-kotlin-circuitbreaker`, `inqudium-kotlin-retry`, etc. would create 18+ additional modules with minimal real-world benefit.

### BOM for version alignment

`inqudium-bom` provides a Bill of Materials so that consumers can align versions across modules without specifying individual versions.

## Consequences

**Positive:**
- Imperative Java consumers pay only for what they use — minimal classpath.
- Reactive/coroutine consumers get a single dependency for their paradigm.
- BOM ensures version consistency across multi-module adoption.
- Each imperative module can be versioned and released independently if needed.

**Negative:**
- More modules to maintain than a monolithic approach.
- Paradigm modules are larger artifacts since they bundle all elements.
- Build configuration (parent POM, plugin management) has higher upfront complexity.

**Neutral:**
- `inqudium-core` becomes the only transitive dependency shared by all modules. It must remain minimal and stable.
