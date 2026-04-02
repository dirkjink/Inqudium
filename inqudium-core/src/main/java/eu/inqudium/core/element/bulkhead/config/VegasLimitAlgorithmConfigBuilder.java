package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.config.ExtensionBuilder;

import java.time.Duration;

public class VegasLimitAlgorithmConfigBuilder extends ExtensionBuilder<VegasLimitAlgorithmConfig> {
  private int initialLimit;
  private int minLimit;
  private int maxLimit;
  private Duration smoothingTimeConstant;
  private Duration baselineDriftTimeConstant;
  private Duration errorRateSmoothingTimeConstant;
  private double errorRateThreshold;
  private double minUtilizationThreshold;


  VegasLimitAlgorithmConfigBuilder() {
  }

  VegasLimitAlgorithmConfigBuilder(int initialLimit,
                                   int minLimit,
                                   int maxLimit,
                                   Duration smoothingTimeConstant,
                                   Duration baselineDriftTimeConstant,
                                   Duration errorRateSmoothingTimeConstant,
                                   double errorRateThreshold,
                                   double minUtilizationThreshold) {
    this.initialLimit = initialLimit;
    this.minLimit = minLimit;
    this.maxLimit = maxLimit;
    this.smoothingTimeConstant = smoothingTimeConstant;
    this.baselineDriftTimeConstant = baselineDriftTimeConstant;
    this.errorRateSmoothingTimeConstant = errorRateSmoothingTimeConstant;
    this.errorRateThreshold = errorRateThreshold;
    this.minUtilizationThreshold = minUtilizationThreshold;
  }

  public static VegasLimitAlgorithmConfigBuilder vegasLimitAlgorithm() {
    return new VegasLimitAlgorithmConfigBuilder().balanced();
  }

  /**
   * <b>Protective</b> preset — prioritizes stability over throughput.
   *
   * <p>Designed for latency-sensitive downstream services where even brief
   * oversaturation causes cascading failures (e.g., payment gateways, auth
   * services, databases with strict connection limits).
   *
   * <h3>Characteristics</h3>
   * <ul>
   *   <li><b>Slow RTT smoothing (2 s):</b> Heavily filters latency noise. The gradient
   *       reacts to sustained trends rather than individual spikes, preventing
   *       unnecessary limit oscillation.</li>
   *   <li><b>Very slow baseline drift (30 s):</b> The no-load baseline is highly resistant
   *       to temporary latency shifts, keeping the gradient conservative.</li>
   *   <li><b>Tolerant error fallback (15%):</b> The reactive 0.8× decrease only triggers
   *       when the 10-second EWMA error rate exceeds 15%, absorbing transient error bursts
   *       without capacity loss.</li>
   *   <li><b>Low utilization gate (50%):</b> Allows limit growth at moderate load so the
   *       system can still adapt during ramp-up, while preventing inflation when idle.</li>
   * </ul>
   *
   * <h3>Defaults</h3>
   * <table>
   *   <tr><td>Initial limit</td><td>20</td></tr>
   *   <tr><td>Min / Max limit</td><td>1 / 200</td></tr>
   *   <tr><td>RTT smoothing Tau</td><td>2 s</td></tr>
   *   <tr><td>Baseline drift Tau</td><td>30 s</td></tr>
   *   <tr><td>Error rate smoothing Tau</td><td>10 s</td></tr>
   *   <tr><td>Error rate threshold</td><td>15%</td></tr>
   *   <tr><td>Min utilization</td><td>50%</td></tr>
   * </table>
   *
   * @return a protectively tuned Vegas algorithm
   */
  public VegasLimitAlgorithmConfigBuilder protective() {
    return new VegasLimitAlgorithmConfigBuilder(
        20,                        // initialLimit: conservative starting point
        1,                         // minLimit: always allow at least one probe
        200,                       // maxLimit: hard ceiling prevents runaway scaling
        Duration.ofSeconds(2),     // smoothingTimeConstant: heavy noise filtering
        Duration.ofSeconds(30),    // baselineDriftTimeConstant: very slow — resists transient shifts
        Duration.ofSeconds(10),    // errorRateSmoothingTimeConstant: absorbs transient error bursts
        0.15,                      // errorRateThreshold: tolerant — 15% before reactive fallback
        0.5                       // minUtilizationThreshold: moderate gate
    );
  }

  /**
   * <b>Balanced</b> preset — the recommended production default.
   *
   * <p>Suitable for most backend-to-backend communication where the downstream
   * service has moderate, somewhat predictable capacity and latency characteristics
   * (e.g., internal microservices, managed databases, message brokers).
   *
   * <h3>Characteristics</h3>
   * <ul>
   *   <li><b>Responsive RTT smoothing (1 s):</b> Adapts to latency changes within seconds
   *       while still filtering single-request jitter.</li>
   *   <li><b>Moderate baseline drift (10 s):</b> The no-load baseline slowly tracks reality,
   *       recovering from stale low outliers within tens of seconds.</li>
   *   <li><b>Balanced error fallback (10%):</b> The 5-second EWMA window detects sustained
   *       failure patterns within a few seconds without overreacting to isolated errors.</li>
   *   <li><b>Utilization gate (60%):</b> Prevents limit inflation during off-peak periods
   *       while allowing growth once load is meaningful.</li>
   * </ul>
   *
   * <h3>Defaults</h3>
   * <table>
   *   <tr><td>Initial limit</td><td>50</td></tr>
   *   <tr><td>Min / Max limit</td><td>5 / 500</td></tr>
   *   <tr><td>RTT smoothing Tau</td><td>1 s</td></tr>
   *   <tr><td>Baseline drift Tau</td><td>10 s</td></tr>
   *   <tr><td>Error rate smoothing Tau</td><td>5 s</td></tr>
   *   <tr><td>Error rate threshold</td><td>10%</td></tr>
   *   <tr><td>Min utilization</td><td>60%</td></tr>
   * </table>
   *
   * @return a balanced Vegas algorithm suitable for general production use
   */
  public VegasLimitAlgorithmConfigBuilder balanced() {
    return new VegasLimitAlgorithmConfigBuilder(
        50,                        // initialLimit: moderate starting point
        5,                         // minLimit: keeps a small probe window open
        500,                       // maxLimit: allows significant scaling headroom
        Duration.ofSeconds(1),     // smoothingTimeConstant: responsive yet stable
        Duration.ofSeconds(10),    // baselineDriftTimeConstant: moderate drift speed
        Duration.ofSeconds(5),     // errorRateSmoothingTimeConstant: balanced error detection
        0.1,                       // errorRateThreshold: 10% triggers reactive fallback
        0.6                       // minUtilizationThreshold: prevents low-load inflation
    );
  }

  /**
   * <b>Performant</b> preset — prioritizes throughput over caution.
   *
   * <p>Designed for downstream services with high, elastic capacity where
   * under-utilization is more costly than brief oversaturation (e.g., autoscaling
   * compute clusters, CDN origins, horizontally scaled stateless services).
   *
   * <h3>Characteristics</h3>
   * <ul>
   *   <li><b>Fast RTT smoothing (500 ms):</b> The gradient reacts quickly to latency
   *       changes, enabling rapid upward scaling when conditions improve.</li>
   *   <li><b>Fast baseline drift (5 s):</b> The no-load baseline tracks reality
   *       aggressively, keeping the gradient accurate even during rapid capacity changes.</li>
   *   <li><b>Strict error fallback (5%):</b> Compensates for the fast scaling by
   *       triggering the reactive 0.8× decrease early. The 3-second EWMA ensures
   *       the reaction is still based on a pattern, not a single failure.</li>
   *   <li><b>High utilization gate (75%):</b> Demands clear evidence of load before
   *       allowing limit growth, ensuring the fast scaling is backed by real demand.</li>
   * </ul>
   *
   * <h3>Defaults</h3>
   * <table>
   *   <tr><td>Initial limit</td><td>100</td></tr>
   *   <tr><td>Min / Max limit</td><td>10 / 1000</td></tr>
   *   <tr><td>RTT smoothing Tau</td><td>500 ms</td></tr>
   *   <tr><td>Baseline drift Tau</td><td>5 s</td></tr>
   *   <tr><td>Error rate smoothing Tau</td><td>3 s</td></tr>
   *   <tr><td>Error rate threshold</td><td>5%</td></tr>
   *   <tr><td>Min utilization</td><td>75%</td></tr>
   * </table>
   *
   * @return a throughput-optimized Vegas algorithm
   */
  public VegasLimitAlgorithmConfigBuilder performant() {
    return new VegasLimitAlgorithmConfigBuilder(
        100,                       // initialLimit: high starting point
        10,                        // minLimit: substantial floor for elastic backends
        1000,                      // maxLimit: generous ceiling
        Duration.ofMillis(500),    // smoothingTimeConstant: fast latency tracking
        Duration.ofSeconds(5),     // baselineDriftTimeConstant: fast — tracks reality aggressively
        Duration.ofSeconds(3),     // errorRateSmoothingTimeConstant: quick but pattern-based
        0.05,                      // errorRateThreshold: strict — react at 5%
        0.75                      // minUtilizationThreshold: only grow when truly loaded
    );
  }

  public VegasLimitAlgorithmConfigBuilder initialLimit(int initialLimit) {
    this.initialLimit = initialLimit;
    return this;
  }

  public VegasLimitAlgorithmConfigBuilder minLimit(int minLimit) {
    this.minLimit = minLimit;
    return this;
  }

  public VegasLimitAlgorithmConfigBuilder maxLimit(int maxLimit) {
    this.maxLimit = maxLimit;
    return this;
  }

  public VegasLimitAlgorithmConfigBuilder smoothingTimeConstant(Duration smoothingTimeConstant) {
    this.smoothingTimeConstant = smoothingTimeConstant;
    return this;
  }

  public VegasLimitAlgorithmConfigBuilder baselineDriftTimeConstant(Duration baselineDriftTimeConstant) {
    this.baselineDriftTimeConstant = baselineDriftTimeConstant;
    return this;
  }

  public VegasLimitAlgorithmConfigBuilder errorRateSmoothingTimeConstant(Duration errorRateSmoothingTimeConstant) {
    this.errorRateSmoothingTimeConstant = errorRateSmoothingTimeConstant;
    return this;
  }

  public VegasLimitAlgorithmConfigBuilder errorRateThreshold(double errorRateThreshold) {
    this.errorRateThreshold = errorRateThreshold;
    return this;
  }

  public VegasLimitAlgorithmConfigBuilder minUtilizationThreshold(double minUtilizationThreshold) {
    this.minUtilizationThreshold = minUtilizationThreshold;
    return this;
  }

  @Override
  public VegasLimitAlgorithmConfig build() {
    return new VegasLimitAlgorithmConfig(initialLimit,
        minLimit,
        maxLimit,
        smoothingTimeConstant,
        baselineDriftTimeConstant,
        errorRateSmoothingTimeConstant,
        errorRateThreshold,
        minUtilizationThreshold);
  }
}
