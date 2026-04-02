package eu.inqudium.core.element.bulkhead.algo;

import eu.inqudium.core.algo.ContinuousTimeEwma;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Thread-safe implementation of the Additive Increase, Multiplicative Decrease (AIMD) algorithm.
 *
 * <h2>Algorithm Overview</h2>
 * <p>AIMD is a feedback-driven concurrency control algorithm, originally made famous by TCP
 * congestion control (RFC 5681). It dynamically adjusts the maximum number of concurrent
 * calls allowed through a bulkhead by observing the outcomes of completed requests:
 *
 * <ul>
 * <li><b>Additive Increase (Probing Phase):</b> After each successful call, the concurrency
 * limit is gently increased. This allows the system to probe for available downstream
 * capacity without risking sudden overload. The increase strategy is configurable:
 * <ul>
 * <li><b>Fixed ({@code +1}, default):</b> Each success adds exactly 1 to the limit.
 * Simple and predictable, but the growth rate is proportional to transaction
 * volume. A system processing 1000 RPS would increase the limit by 1000/sec,
 * hitting maxLimit almost instantly.</li>
 * <li><b>Windowed ({@code +1/currentLimit}, opt-in):</b> Each success adds a fraction
 * inversely proportional to the current limit. Over one full "congestion window"
 * of {@code currentLimit} consecutive successes, the net increase is exactly +1.
 * This matches classic TCP behavior and makes the growth rate independent of
 * transaction throughput.</li>
 * </ul>
 * </li>
 * <li><b>Multiplicative Decrease (Protection Phase):</b> When the system detects sustained
 * failures indicating downstream congestion, the limit is multiplied by a fractional
 * backoff ratio (e.g., 0.5 to halve it). This aggressively sheds load to give the
 * downstream service breathing room. The failure detection is configurable:
 * <ul>
 * <li><b>Immediate (default):</b> Every individual failure triggers a decrease.
 * Simple but prone to overreaction on transient network hiccups.</li>
 * <li><b>Continuous-Time EWMA-smoothed (opt-in):</b> A time-based Exponentially
 * Weighted Moving Average tracks the error rate via {@link ContinuousTimeEwma}.
 * This ensures the decay is independent of the request rate (RPS). Only when this
 * smoothed rate exceeds a configurable threshold (e.g., 10%) is the decrease triggered.
 * Isolated failures are absorbed without capacity loss.</li>
 * </ul>
 * </li>
 * </ul>
 *
 * <p>Together, the additive increase and multiplicative decrease produce the characteristic
 * "sawtooth" pattern: the limit slowly climbs until congestion is detected, drops sharply,
 * and begins climbing again. The sawtooth amplitude and frequency are controlled by the
 * backoff ratio and the increase strategy.
 *
 * <h2>Configuration Modes</h2>
 *
 * <h3>Classic Mode (4-parameter constructor)</h3>
 * <p>Preserves the exact behavior of the original AIMD implementation for full backward
 * compatibility. Uses fixed {@code +1} increase and immediate per-failure decrease.
 * Suitable for low-to-moderate throughput systems or when simplicity is preferred over
 * stability.
 *
 * <h3>Stabilized Mode (9-parameter constructor)</h3>
 * <p>Enables windowed additive increase, EWMA error rate smoothing, and utilization thresholds
 * for production use in high-throughput environments. Key benefits:
 * <ul>
 * <li>Growth rate is independent of RPS (no runaway limit inflation)</li>
 * <li>Transient single failures do not cause capacity drops</li>
 * <li>Sawtooth amplitude is smaller and more predictable</li>
 * <li>Prevents limit inflation when the system is under low load</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All mutable algorithm state ({@code currentLimit}, {@code smoothedErrorRate}, and
 * {@code lastUpdateNanos}) is bundled into a single immutable {@link AimdState} record
 * and managed via {@link AtomicReference#compareAndSet(Object, Object)}. This is the same
 * pattern used by {@link VegasLimitAlgorithm} and guarantees that every
 * {@link #update(Duration, boolean, int)} call reads and writes a consistent snapshot without
 * any locking or blocking.
 *
 * <p>The CAS retry loop is wait-free in practice: the compute step is pure arithmetic
 * with no I/O, and contention is bounded by the number of threads concurrently completing
 * calls through this bulkhead.
 *
 * @see VegasLimitAlgorithm
 * @see InqLimitAlgorithm
 * @since 0.2.0
 */
public final class AimdLimitAlgorithm implements InqLimitAlgorithm {

  // ──────────────────────────────────────────────────────────────────────────
  // Configuration Fields (immutable after construction)
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * The absolute minimum concurrency limit. The algorithm will never reduce the limit
   * below this floor, regardless of how many failures occur.
   */
  private final int minLimit;

  /**
   * The absolute maximum concurrency limit. The algorithm will never increase the limit
   * above this ceiling, even under a sustained stream of successes.
   */
  private final int maxLimit;

  /**
   * The multiplicative factor applied during the decrease phase (0.1–0.9).
   */
  private final double backoffRatio;

  /**
   * The stateless calculator used to compute the continuous-time EWMA of the error rate.
   * Replaces the manual tau calculation to ensure RPS-independent decay.
   */
  private final ContinuousTimeEwma errorRateEwma;

  /**
   * The EWMA-smoothed error rate must strictly exceed this threshold before the
   * multiplicative decrease is triggered.
   */
  private final double errorRateThreshold;

  /**
   * Controls the additive increase strategy.
   */
  private final boolean windowedIncrease;

  /**
   * The minimum utilization percentage (0.0 to 1.0) required to allow limit growth.
   * Prevents limit inflation when the system is under low load.
   */
  private final double minUtilizationThreshold;

  /**
   * The injectable nano-time source for all timing measurements related to the EWMA decay.
   */
  private final LongSupplier nanoTimeSource;

  // ──────────────────────────────────────────────────────────────────────────
  // Mutable State (managed via CAS on an immutable snapshot)
  // ──────────────────────────────────────────────────────────────────────────

  private final AtomicReference<AimdState> state;

  // ──────────────────────────────────────────────────────────────────────────
  // Constructors
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Creates a new AIMD algorithm in <b>classic mode</b> (backward-compatible).
   *
   * <p><b>Caution:</b> Classic mode triggers a multiplicative decrease on <em>every single
   * failure</em> — there is no error rate smoothing. At high throughput (e.g., 10,000 RPS),
   * a single transient timeout will halve the limit immediately. For production use,
   * prefer the stabilized mode (9-parameter constructor) or {@link #classicSmoothed} which
   * adds minimal EWMA smoothing while preserving the classic {@code +1} increase.
   *
   * @param initialLimit The starting concurrency limit before any feedback is received.
   * @param minLimit     The absolute minimum limit.
   * @param maxLimit     The absolute upper bound to prevent infinite scaling.
   * @param backoffRatio The multiplier for the decrease phase (e.g., 0.5 for halving).
   */
  public AimdLimitAlgorithm(int initialLimit,
                            int minLimit,
                            int maxLimit,
                            double backoffRatio) {
    this(initialLimit,
        minLimit,
        maxLimit,
        backoffRatio,
        Duration.ofNanos(1),
        0.0,
        false,
        0.0, // 0.0 disables the utilization threshold for classic backward compatibility
        System::nanoTime);
  }

  /**
   * Creates a classic AIMD algorithm with minimal error rate smoothing.
   *
   * <p>Behaves like the classic 4-parameter constructor (fixed {@code +1} increase,
   * no utilization threshold) but adds a 1-second EWMA window with a 10% error rate
   * threshold. This absorbs isolated transient failures without triggering a decrease,
   * while still reacting quickly to sustained error bursts.
   *
   * <p>This is the recommended replacement for the raw classic constructor in
   * production environments.
   *
   * @param initialLimit The starting concurrency limit.
   * @param minLimit     The absolute minimum limit.
   * @param maxLimit     The absolute upper bound.
   * @param backoffRatio The decrease multiplier (e.g., 0.5 for halving).
   * @return a new AIMD algorithm with smoothed error detection
   */
  public static AimdLimitAlgorithm classicSmoothed(int initialLimit,
                                                   int minLimit,
                                                   int maxLimit,
                                                   double backoffRatio) {
    return new AimdLimitAlgorithm(initialLimit,
        minLimit,
        maxLimit,
        backoffRatio,
        Duration.ofSeconds(1),
        0.1,
        false,
        0.0,
        System::nanoTime);
  }

  /**
   * Creates a new AIMD algorithm in <b>stabilized mode</b> with full control over all
   * behavioral parameters.
   *
   * @param initialLimit            The starting concurrency limit. Clamped to
   * [{@code minLimit}, {@code maxLimit}].
   * @param minLimit                The absolute minimum limit. Clamped to at least 1.
   * @param maxLimit                The absolute upper bound. Clamped to at least {@code minLimit}.
   * @param backoffRatio            The decrease multiplier. Clamped to [0.1, 0.9].
   * @param smoothingTimeConstant   Time constant (Tau) for the continuous-time EWMA.
   * A larger duration = smoother (more resistant to transient spikes).
   * @param errorRateThreshold      The smoothed error rate must strictly exceed this value to
   * trigger a multiplicative decrease. Clamped to [0.0, 1.0].
   * @param windowedIncrease        {@code true} for TCP-style {@code +1/currentLimit} increase,
   * {@code false} for classic {@code +1} increase.
   * @param minUtilizationThreshold The minimum utilization percentage (0.0 to 1.0) required to
   * allow limit growth. Prevents limit inflation under low load.
   * @param nanoTimeSource          The time source used for calculating elapsed time.
   */
  public AimdLimitAlgorithm(int initialLimit, int minLimit, int maxLimit,
                            double backoffRatio, Duration smoothingTimeConstant,
                            double errorRateThreshold, boolean windowedIncrease,
                            double minUtilizationThreshold,
                            LongSupplier nanoTimeSource) {

    this.minLimit = Math.max(1, minLimit);
    this.maxLimit = Math.max(this.minLimit, maxLimit);
    this.backoffRatio = Math.max(0.1, Math.min(0.9, backoffRatio));
    this.errorRateEwma = new ContinuousTimeEwma(smoothingTimeConstant);
    this.errorRateThreshold = Math.max(0.0, Math.min(1.0, errorRateThreshold));
    this.windowedIncrease = windowedIncrease;
    this.minUtilizationThreshold = Math.max(0.0, Math.min(1.0, minUtilizationThreshold));
    this.nanoTimeSource = nanoTimeSource != null ? nanoTimeSource : System::nanoTime;

    double bounded = Math.max(this.minLimit, Math.min(initialLimit, this.maxLimit));
    this.state = new AtomicReference<>(
        new AimdState(bounded,
            0.0,
            this.nanoTimeSource.getAsLong()));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // InqLimitAlgorithm Interface
  // ──────────────────────────────────────────────────────────────────────────

  @Override
  public int getLimit() {
    return (int) (state.get().currentLimit() + 1e-9);
  }

  @Override
  public void update(Duration rtt, boolean isSuccess, int inFlightCalls) {
    if (rtt.toNanos() <= 0) {
      return;
    }

    long now = nanoTimeSource.getAsLong();

    AimdState current;
    AimdState next;
    do {
      current = state.get();

      // ── Step 1: Update the Continuous-Time EWMA-smoothed error rate ──
      //
      // The error rate is a sliding average that tracks the recent proportion of
      // failures. Each call contributes a binary sample:
      //   - Success → 0.0 (pulls the rate down toward "all healthy")
      //   - Failure → 1.0 (pulls the rate up toward "all failing")
      //
      // The calculation is delegated to the stateless ContinuousTimeEwma calculator,
      // which ensures the decay is applied perfectly relative to the elapsed time.
      double sample = isSuccess ? 0.0 : 1.0;
      double newErrorRate = errorRateEwma.calculate(
          current.smoothedErrorRate(),
          current.lastUpdateNanos(),
          now,
          sample
      );

      // ── Step 2: Calculate the new concurrency limit ──
      double newLimit;

      if (isSuccess) {
        // Check if the current in-flight calls meet the utilization threshold
        boolean isFullyUtilized = inFlightCalls >= (current.currentLimit() * minUtilizationThreshold);

        if (isFullyUtilized) {
          if (windowedIncrease) {
            newLimit = current.currentLimit() + 1.0 / current.currentLimit();
          } else {
            newLimit = current.currentLimit() + 1.0;
          }
        } else {
          // Low load: Do not inflate the limit
          newLimit = current.currentLimit();
        }

      } else if (newErrorRate > errorRateThreshold) {
        newLimit = current.currentLimit() * backoffRatio;

      } else {
        newLimit = current.currentLimit();
      }

      // ── Step 3: Clamp the new limit to the configured bounds ──
      newLimit = Math.max(minLimit, Math.min(maxLimit, newLimit));

      // Bundle the new limit, error rate, and 'now' timestamp into an immutable
      // snapshot for the atomic CAS swap.
      next = new AimdState(newLimit, newErrorRate, now);

    } while (!state.compareAndSet(current, next));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Internal State Record
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Immutable snapshot of the algorithm's mutable state.
   *
   * @param currentLimit      The current concurrency limit as a double to support fractional
   * windowed increments. Always in [{@code minLimit}, {@code maxLimit}].
   * @param smoothedErrorRate The time-smoothed error rate, ranging from 0.0 (all recent calls
   * succeeded) to 1.0 (all recent calls failed).
   * @param lastUpdateNanos   The timestamp of the last state update, used by the
   * {@link ContinuousTimeEwma} calculator.
   */
  private record AimdState(double currentLimit, double smoothedErrorRate, long lastUpdateNanos) {
  }
}