package eu.inqudium.core.element.bulkhead.event;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Immutable configuration that controls which {@link BulkheadEventCategory categories}
 * of bulkhead events are enabled.
 *
 * <p>The enabled state is resolved at construction time into plain {@code boolean}
 * fields — one per category. This makes the hot-path guard a single field read
 * with no set lookup, no virtual dispatch, and no allocation.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Full telemetry (default)
 * BulkheadEventConfig full = BulkheadEventConfig.allEnabled();
 *
 * // Minimal: only rejections
 * BulkheadEventConfig minimal = BulkheadEventConfig.of(BulkheadEventCategory.REJECTION);
 *
 * // Custom: rejections + trace, no lifecycle
 * BulkheadEventConfig custom = BulkheadEventConfig.of(
 *     BulkheadEventCategory.REJECTION,
 *     BulkheadEventCategory.TRACE
 * );
 *
 * // Check in the facade (single boolean read — zero overhead)
 * if (eventConfig.isLifecycleEnabled()) {
 *     eventPublisher.publish(new BulkheadOnAcquireEvent(...));
 * }
 * }</pre>
 *
 * @since 0.3.0
 */
public final class BulkheadEventConfig {

  private static final BulkheadEventConfig DISABLED = new BulkheadEventConfig(Collections.emptySet());

  private static final BulkheadEventConfig ALL_ENABLED = new BulkheadEventConfig(
      EnumSet.allOf(BulkheadEventCategory.class));

  private static final BulkheadEventConfig REJECTION_ONLY = new BulkheadEventConfig(
      EnumSet.of(BulkheadEventCategory.REJECTION));

  private final boolean lifecycleEnabled;
  private final boolean rejectionEnabled;
  private final boolean traceEnabled;

  private BulkheadEventConfig(Set<BulkheadEventCategory> enabled) {
    this.lifecycleEnabled = enabled.contains(BulkheadEventCategory.LIFECYCLE);
    this.rejectionEnabled = enabled.contains(BulkheadEventCategory.REJECTION);
    this.traceEnabled = enabled.contains(BulkheadEventCategory.TRACE);
  }

  /**
   * All event categories enabled. This is the default.
   */
  public static BulkheadEventConfig disabled() {
    return DISABLED;
  }

  /**
   * All event categories enabled. This is the default.
   */
  public static BulkheadEventConfig allEnabled() {
    return ALL_ENABLED;
  }

  /**
   * Only rejection events enabled — zero happy-path allocation overhead.
   *
   * <p>This is the recommended configuration for latency-sensitive paths where
   * metrics are derived from strategy introspection rather than per-call events.
   */
  public static BulkheadEventConfig rejectionsOnly() {
    return REJECTION_ONLY;
  }

  /**
   * Custom set of enabled categories.
   *
   * @param first      the first enabled category
   * @param remaining  additional enabled categories
   * @return an immutable event configuration
   */
  public static BulkheadEventConfig of(BulkheadEventCategory first,
                                       BulkheadEventCategory... remaining) {
    return new BulkheadEventConfig(EnumSet.of(first, remaining));
  }

  /**
   * {@code true} if acquire and release events should be created and published.
   * Checked on every successful call — must be a plain field read.
   */
  public boolean isLifecycleEnabled() {
    return lifecycleEnabled;
  }

  /**
   * {@code true} if rejection events should be created and published.
   */
  public boolean isRejectionEnabled() {
    return rejectionEnabled;
  }

  /**
   * {@code true} if trace-level diagnostic events should be created and published.
   */
  public boolean isTraceEnabled() {
    return traceEnabled;
  }
}
