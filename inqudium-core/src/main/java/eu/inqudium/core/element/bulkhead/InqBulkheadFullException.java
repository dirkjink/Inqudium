package eu.inqudium.core.element.bulkhead;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.strategy.RejectionContext;
import eu.inqudium.core.element.bulkhead.strategy.RejectionReason;
import eu.inqudium.core.exception.InqException;

import java.util.Locale;

/**
 * Thrown when a bulkhead rejects a call because the maximum number of concurrent
 * calls has been reached, the wait timeout has expired, or the CoDel algorithm
 * determined that the request waited too long.
 *
 * <p>No call was made to the downstream service — the bulkhead rejected the
 * request to prevent resource exhaustion (ADR-009, ADR-020).
 *
 * <p>The {@link #getRejectionContext()} provides the exact state at the moment of
 * rejection, captured inside the strategy's decision logic. Unlike post-hoc snapshots,
 * these values are guaranteed to reflect the true cause.
 *
 * @since 0.1.0
 */
public class InqBulkheadFullException extends InqException {

  /**
   * Call rejected — max concurrent calls reached.
   */
  public static final String CODE = InqElementType.BULKHEAD.errorCode(1);

  private final RejectionContext rejectionContext;

  /**
   * Creates a new exception with a {@link RejectionContext} captured at the moment of rejection.
   *
   * <p>This is the preferred constructor. The rejection context contains the reason,
   * the limit, the active call count, and timing information — all captured inside the
   * strategy's decision logic where they are guaranteed accurate.
   *
   * @param callId           the call identifier
   * @param elementName      the bulkhead instance name
   * @param rejectionContext the rejection snapshot from the strategy
   */
  public InqBulkheadFullException(String callId, String elementName, RejectionContext rejectionContext) {
    super(callId, CODE, elementName, InqElementType.BULKHEAD,
        String.format(Locale.ROOT, "Bulkhead '%s' rejected: %s", elementName, rejectionContext));
    this.rejectionContext = rejectionContext;
  }

  /**
   * Creates a new exception with explicit concurrency values.
   *
   * @param callId             the call identifier
   * @param elementName        the bulkhead instance name
   * @param concurrentCalls    the current number of in-flight calls
   * @param maxConcurrentCalls the configured maximum
   * @deprecated Use {@link #InqBulkheadFullException(String, String, RejectionContext)} instead.
   *             The explicit values are typically read after the rejection decision and may
   *             already be stale. The RejectionContext captures them at the exact moment
   *             of rejection.
   */
  @Deprecated(since = "0.3.0", forRemoval = true)
  public InqBulkheadFullException(String callId, String elementName,
                                  int concurrentCalls, int maxConcurrentCalls) {
    super(callId, CODE, elementName, InqElementType.BULKHEAD,
        String.format(Locale.ROOT, "Bulkhead '%s' is full (%d/%d concurrent calls)",
            elementName, concurrentCalls, maxConcurrentCalls));
    this.rejectionContext = RejectionContext.capacityReached(maxConcurrentCalls, concurrentCalls);
  }

  /**
   * Returns the rejection context captured at the exact moment of rejection.
   *
   * @return the immutable rejection snapshot, never {@code null}
   */
  public RejectionContext getRejectionContext() {
    return rejectionContext;
  }

  /**
   * Returns the reason the bulkhead rejected the request.
   *
   * <p>Convenience accessor — equivalent to {@code getRejectionContext().reason()}.
   *
   * @return the rejection reason
   */
  public RejectionReason getRejectionReason() {
    return rejectionContext.reason();
  }

  /**
   * Returns the concurrency limit that was enforced at the moment of rejection.
   *
   * @return the limit at decision time
   */
  public int getLimitAtDecision() {
    return rejectionContext.limitAtDecision();
  }

  /**
   * Returns the number of concurrent calls at the moment of rejection.
   *
   * @return the active call count at decision time
   * @deprecated Use {@link #getRejectionContext()} for the full snapshot.
   */
  @Deprecated(since = "0.3.0", forRemoval = true)
  public int getConcurrentCalls() {
    return rejectionContext.activeCallsAtDecision();
  }

  /**
   * Returns the concurrency limit at the moment of rejection.
   *
   * @return the limit at decision time
   * @deprecated Use {@link #getLimitAtDecision()} or {@link #getRejectionContext()}.
   */
  @Deprecated(since = "0.3.0", forRemoval = true)
  public int getMaxConcurrentCalls() {
    return rejectionContext.limitAtDecision();
  }
}
