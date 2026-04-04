/**
 * Inqudium Core — the foundation of all paradigm implementations.
 *
 * <p>This package contains the base abstractions that every Inqudium module depends on:
 *
 * <ul>
 *   <li>{@code InqElement} — base interface implemented by all resilience elements
 *       (circuit breakers, retries, rate limiters, bulkheads, time limiters, caches).</li>
 *   <li>{@code InqElementType} — enum identifying the six element kinds. Used in events
 *       (ADR-003), exceptions (ADR-009), context propagation (ADR-011), and pipeline
 *       ordering (ADR-017).</li>
 *   <li>{@code InqConfig} — base interface for all element configurations. Configurations
 *       are immutable and carry an {@code InqCompatibility} reference (ADR-013).</li>
 *   <li>{@code InqRegistry} — contract for named instance management. Thread-safe,
 *       first-registration-wins, default config for on-demand creation (ADR-015).</li>
 *   <li>{@code InqClock} — functional interface for injectable time. Every time-dependent
 *       algorithm in core uses this instead of {@code Instant.now()}, ensuring deterministic
 *       testability (ADR-016).</li>
 *   <li>{@code InqCallIdGenerator} — functional interface for generating unique call
 *       identifiers. Override for deterministic tests, trace ID integration, or custom
 *       formats. Default: UUID.</li>
 *   <li>{@code InqCall} — context-carrying wrapper for pipeline calls. Carries the
 *       {@code callId} through the decoration chain without thread-local state.
 *       Used by element implementations in pipeline mode; standalone calls generate
 *       their own callId via {@code InqCallIdGenerator}.</li>
 * </ul>
 *
 * <h2>Design principles</h2>
 * <ul>
 *   <li><strong>Zero runtime dependencies</strong> — this module depends only on the JDK (ADR-005).</li>
 *   <li><strong>Pure algorithms</strong> — no threading, no blocking, no sleeping. The paradigm
 *       module provides concurrency control (ADR-004, ADR-005).</li>
 *   <li><strong>Virtual-thread ready</strong> — no {@code synchronized}, no carrier-thread
 *       pinning (ADR-008).</li>
 * </ul>
 *
 * @see eu.inqudium.core.event
 * @see eu.inqudium.core.context
 * @see eu.inqudium.core.exception
 */
package eu.inqudium.core;
