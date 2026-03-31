package eu.inqudium.core.fallback;

/**
 * Exception thrown when the fallback provider cannot recover from a failure.
 *
 * <p>This occurs when either no matching handler was found for the primary
 * exception, or the invoked fallback handler itself threw an exception.
 */
public class FallbackException extends RuntimeException {

  private final String fallbackName;
  private final Reason reason;

  public FallbackException(String fallbackName, Reason reason, Throwable primaryFailure) {
    super("FallbackProvider '%s' — %s".formatted(fallbackName, switch (reason) {
      case NO_HANDLER_MATCHED -> "no handler matched for: " + primaryFailure.getClass().getSimpleName();
      case FALLBACK_FAILED -> "fallback handler failed";
    }), primaryFailure);
    this.fallbackName = fallbackName;
    this.reason = reason;
  }

  public FallbackException(String fallbackName, Reason reason,
                           Throwable primaryFailure, Throwable fallbackFailure) {
    super("FallbackProvider '%s' — fallback handler failed: %s (primary: %s)"
            .formatted(fallbackName,
                fallbackFailure.getMessage(),
                primaryFailure.getMessage()),
        fallbackFailure);
    this.addSuppressed(primaryFailure);
    this.fallbackName = fallbackName;
    this.reason = reason;
  }

  public String getFallbackName() {
    return fallbackName;
  }

  public Reason getReason() {
    return reason;
  }

  public enum Reason {
    /**
     * No registered handler matched the primary exception.
     */
    NO_HANDLER_MATCHED,
    /**
     * The fallback handler itself threw an exception.
     */
    FALLBACK_FAILED
  }
}
