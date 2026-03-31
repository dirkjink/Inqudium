package eu.inqudium.imperative.bulkhead.imperative;

import eu.inqudium.core.bulkhead.BlockingBulkheadStateMachine;
import eu.inqudium.core.bulkhead.BulkheadConfig;

/**
 * Factory for creating configured instances of imperative bulkheads.
 *
 * <p>It evaluates the provided {@link BulkheadConfig} to determine which state machine
 * implementation to wire:
 * <ul>
 *   <li><b>CoDel:</b> If CoDel parameters (targetDelay, interval) are configured,
 *       creates a {@link CoDelImperativeStateMachine} for queue-based delay management.</li>
 *   <li><b>Adaptive:</b> If a limit algorithm (AIMD, Vegas) is configured, creates an
 *       {@link AdaptiveImperativeStateMachine} for dynamic concurrency limits.</li>
 *   <li><b>Static:</b> Otherwise, creates a semaphore-based
 *       {@link SemaphoreImperativeStateMachine} with fixed limits.</li>
 * </ul>
 *
 * <p>The original factory only knew about Static and Adaptive paths.
 * {@code CoDelImperativeStateMachine} was unreachable through the standard creation flow.
 *
 * @since 0.2.0
 */
public final class ImperativeBulkheadFactory {

  private ImperativeBulkheadFactory() {
    // Prevent instantiation of utility class
  }

  /**
   * Creates a new imperative bulkhead based on the provided configuration.
   *
   * @param name   the unique name of the bulkhead instance
   * @param config the configuration dictating the behavior and limits
   * @return a fully wired imperative bulkhead ready for decoration
   */
  public static ImperativeBulkhead create(String name, BulkheadConfig config) {
    final BlockingBulkheadStateMachine stateMachine;

    if (config.isCoDelEnabled()) {
      // CoDel — Queue-based delay management
      // BulkheadConfig.build() already validates that CoDel and limitAlgorithm are mutually exclusive
      stateMachine = new CoDelImperativeStateMachine(
          name, config, config.getCodelTargetDelay(), config.getCodelInterval());
    } else if (config.getLimitAlgorithm() != null) {
      // Dynamic: Adaptive limits configured (e.g. AIMD, Vegas)
      stateMachine = new AdaptiveImperativeStateMachine(name, config, config.getLimitAlgorithm());
    } else {
      // Static: Fixed limits using standard Semaphores
      stateMachine = new SemaphoreImperativeStateMachine(name, config);
    }

    return new ImperativeBulkhead(name, config, stateMachine);
  }
}
