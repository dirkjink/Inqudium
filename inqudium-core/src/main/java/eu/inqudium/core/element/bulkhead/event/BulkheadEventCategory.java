package eu.inqudium.core.element.bulkhead.event;

/**
 * Categories of bulkhead events that can be independently enabled or disabled.
 *
 * <p>Each category groups events that serve a common observability purpose.
 * Disabling a category suppresses the creation and publication of all events
 * in that group — including the allocation of their timestamp and payload
 * objects. This is a zero-cost guard: a single boolean check before any
 * object creation.
 *
 * <h2>Default configuration</h2>
 * <p>All categories are <b>enabled</b> by default. Disabling categories is
 * an explicit opt-in for latency-sensitive paths where the allocation cost
 * of happy-path events is measurable (typically &gt; 10K ops/sec).
 *
 * <h2>Categories</h2>
 *
 * <h3>{@link #LIFECYCLE}</h3>
 * <p>Controls {@link BulkheadOnAcquireEvent} and {@link BulkheadOnReleaseEvent} —
 * the two events emitted on <em>every successful call</em>. These are the primary
 * source of happy-path allocation overhead (~80 bytes/op from two {@code Instant}
 * objects and the event instances).
 *
 * <p>Disabling this category is safe when:
 * <ul>
 *   <li>Metrics are derived from rejection counts and strategy introspection
 *       ({@link eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy#concurrentCalls()})
 *       rather than per-call events.</li>
 *   <li>The application's observability stack polls metrics at intervals rather
 *       than reacting to individual acquire/release signals.</li>
 * </ul>
 *
 * <p>Disabling this category does <b>not</b> affect:
 * <ul>
 *   <li>Rejection events ({@link #REJECTION}) — always emitted.</li>
 *   <li>Trace events ({@link #TRACE}) — controlled independently.</li>
 *   <li>The rollback safety mechanism — if an acquire event <em>would</em> have
 *       been published but fails, the permit is rolled back. When lifecycle events
 *       are disabled, no publish is attempted, so no rollback can be triggered by
 *       a publisher failure.</li>
 * </ul>
 *
 * <h3>{@link #REJECTION}</h3>
 * <p>Controls {@link BulkheadOnRejectEvent}. Emitted only when a permit request
 * is denied. Cannot be disabled by default — rejections are always operationally
 * relevant. Included in the enum for completeness and for scenarios where even
 * rejection telemetry must be suppressed (e.g., load testing with expected 100%
 * rejection rates).
 *
 * <h3>{@link #TRACE}</h3>
 * <p>Controls {@link BulkheadWaitTraceEvent} and {@link BulkheadRollbackTraceEvent}.
 * These are high-detail diagnostic events that are already gated behind
 * {@code eventPublisher.isTraceEnabled()}. This category provides an additional,
 * coarser-grained toggle at the bulkhead configuration level.
 *
 * @since 0.3.0
 */
public enum BulkheadEventCategory {

  /**
   * Acquire and release events — emitted on every successful call.
   * Primary source of happy-path allocation overhead.
   */
  LIFECYCLE,

  /**
   * Rejection events — emitted when a permit request is denied.
   * Operationally critical; enabled by default.
   */
  REJECTION,

  /**
   * Trace-level diagnostic events (wait times, rollback details).
   * High-detail events for debugging.
   */
  TRACE
}
