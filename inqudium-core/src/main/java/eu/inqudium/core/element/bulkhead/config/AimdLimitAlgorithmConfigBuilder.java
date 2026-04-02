package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.config.ExtensionBuilder;

import java.time.Duration;

public class AimdLimitAlgorithmConfigBuilder extends ExtensionBuilder<AimdLimitAlgorithmConfig> {
  private int initialLimit;
  private int minLimit;
  private int maxLimit;
  private double backoffRatio;
  private Duration smoothingTimeConstant;
  private double errorRateThreshold;
  private boolean windowedIncrease;
  private double minUtilizationThreshold;


  AimdLimitAlgorithmConfigBuilder() {
  }

  AimdLimitAlgorithmConfigBuilder(int initialLimit,
                                  int minLimit,
                                  int maxLimit,
                                  double backoffRatio,
                                  Duration smoothingTimeConstant,
                                  double errorRateThreshold,
                                  boolean windowedIncrease,
                                  double minUtilizationThreshold) {
    this.initialLimit = initialLimit;
    this.minLimit = minLimit;
    this.maxLimit = maxLimit;
    this.backoffRatio = backoffRatio;
    this.smoothingTimeConstant = smoothingTimeConstant;
    this.errorRateThreshold = errorRateThreshold;
    this.windowedIncrease = windowedIncrease;
    this.minUtilizationThreshold = minUtilizationThreshold;
  }

  public static AimdLimitAlgorithmConfigBuilder aimdLimitAlgorithm() {
    return new AimdLimitAlgorithmConfigBuilder().balanced();
  }

  /**
   * <b>Protective</b> preset — prioritizes stability over throughput.
   *
   * <p>Designed for critical downstream services where oversaturation is more
   * dangerous than under-utilization (e.g., payment gateways, auth services,
   * databases with strict connection limits).
   *
   * <h3>Characteristics</h3>
   * <ul>
   *   <li><b>Slow growth:</b> Windowed increase ({@code +1/currentLimit}) ensures the
   *       limit climbs by at most +1 per full congestion window, regardless of RPS.</li>
   *   <li><b>Aggressive backoff:</b> Halves the limit on sustained failures ({@code 0.5}).</li>
   *   <li><b>Tolerant error detection:</b> 15% smoothed error rate with a 5-second EWMA
   *       window absorbs transient failure bursts without unnecessary capacity drops.</li>
   *   <li><b>Low utilization gate:</b> Requires only 50% utilization to allow growth,
   *       so the limit can still adapt during moderate-load periods.</li>
   * </ul>
   *
   * <h3>Defaults</h3>
   * <table>
   *   <tr><td>Initial limit</td><td>20</td></tr>
   *   <tr><td>Min / Max limit</td><td>1 / 200</td></tr>
   *   <tr><td>Backoff ratio</td><td>0.5</td></tr>
   *   <tr><td>Smoothing Tau</td><td>5 s</td></tr>
   *   <tr><td>Error rate threshold</td><td>15%</td></tr>
   *   <tr><td>Windowed increase</td><td>true</td></tr>
   *   <tr><td>Min utilization</td><td>50%</td></tr>
   * </table>
   *
   * @return a protectively tuned AIMD algorithm
   */
  public AimdLimitAlgorithmConfigBuilder protective() {
    return new AimdLimitAlgorithmConfigBuilder(
        20,                       // initialLimit: conservative starting point
        1,                        // minLimit: always allow at least one probe
        200,                      // maxLimit: hard ceiling prevents runaway scaling
        0.5,                      // backoffRatio: halve on sustained failures
        Duration.ofSeconds(5),    // smoothingTimeConstant: very smooth error tracking
        0.15,                     // errorRateThreshold: tolerant — absorbs transient bursts
        true,                     // windowedIncrease: slow, RPS-independent growth
        0.5                      // minUtilizationThreshold: moderate gate
    );
  }

  /**
   * <b>Balanced</b> preset — the recommended production default.
   *
   * <p>Suitable for most backend-to-backend communication where the downstream
   * service has moderate and somewhat predictable capacity (e.g., internal
   * microservices, managed databases, message brokers).
   *
   * <h3>Characteristics</h3>
   * <ul>
   *   <li><b>Moderate growth:</b> Windowed increase provides steady, predictable scaling
   *       that is independent of request throughput.</li>
   *   <li><b>Moderate backoff:</b> Retains 70% of capacity on sustained failures,
   *       shedding load without overshooting.</li>
   *   <li><b>Balanced error detection:</b> 10% smoothed error rate with a 2-second EWMA
   *       window reacts within a few seconds while still filtering single-request hiccups.</li>
   *   <li><b>Utilization gate:</b> 60% utilization required before the limit is allowed
   *       to grow, preventing inflation during off-peak periods.</li>
   * </ul>
   *
   * <h3>Defaults</h3>
   * <table>
   *   <tr><td>Initial limit</td><td>50</td></tr>
   *   <tr><td>Min / Max limit</td><td>5 / 500</td></tr>
   *   <tr><td>Backoff ratio</td><td>0.7</td></tr>
   *   <tr><td>Smoothing Tau</td><td>2 s</td></tr>
   *   <tr><td>Error rate threshold</td><td>10%</td></tr>
   *   <tr><td>Windowed increase</td><td>true</td></tr>
   *   <tr><td>Min utilization</td><td>60%</td></tr>
   * </table>
   *
   * @return a balanced AIMD algorithm suitable for general production use
   */
  public AimdLimitAlgorithmConfigBuilder balanced() {
    return new AimdLimitAlgorithmConfigBuilder(
        50,                       // initialLimit: moderate starting point
        5,                        // minLimit: keeps a small probe window open
        500,                      // maxLimit: allows significant scaling headroom
        0.7,                      // backoffRatio: retain 70% on failures
        Duration.ofSeconds(2),    // smoothingTimeConstant: responsive yet stable
        0.1,                      // errorRateThreshold: 10% triggers decrease
        true,                     // windowedIncrease: RPS-independent growth
        0.6                      // minUtilizationThreshold: prevents low-load inflation
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
   *   <li><b>Fast growth:</b> Fixed {@code +1} increase scales the limit rapidly
   *       in proportion to successful request throughput.</li>
   *   <li><b>Gentle backoff:</b> Retains 85% of capacity on sustained failures,
   *       avoiding deep cuts that would starve an elastic backend.</li>
   *   <li><b>Strict error detection:</b> 5% smoothed error rate with a 1-second
   *       EWMA triggers quickly, compensating for the gentle backoff.</li>
   *   <li><b>High utilization gate:</b> 75% utilization required before the limit is
   *       allowed to grow, ensuring the system genuinely needs more capacity.</li>
   * </ul>
   *
   * <h3>Defaults</h3>
   * <table>
   *   <tr><td>Initial limit</td><td>100</td></tr>
   *   <tr><td>Min / Max limit</td><td>10 / 1000</td></tr>
   *   <tr><td>Backoff ratio</td><td>0.85</td></tr>
   *   <tr><td>Smoothing Tau</td><td>1 s</td></tr>
   *   <tr><td>Error rate threshold</td><td>5%</td></tr>
   *   <tr><td>Windowed increase</td><td>false (+1 per success)</td></tr>
   *   <tr><td>Min utilization</td><td>75%</td></tr>
   * </table>
   *
   * @return a throughput-optimized AIMD algorithm
   */
  public AimdLimitAlgorithmConfigBuilder performant() {
    return new AimdLimitAlgorithmConfigBuilder(
        100,                      // initialLimit: high starting point
        10,                       // minLimit: substantial floor for elastic backends
        1000,                     // maxLimit: generous ceiling
        0.85,                     // backoffRatio: gentle — retain 85%
        Duration.ofSeconds(1),    // smoothingTimeConstant: fast error detection
        0.05,                     // errorRateThreshold: strict — react at 5%
        false,                    // windowedIncrease: fast +1 growth per success
        0.75                     // minUtilizationThreshold: only grow when truly loaded
    );
  }

  public AimdLimitAlgorithmConfigBuilder initialLimit(int initialLimit) {
    this.initialLimit = initialLimit;
    return this;
  }

  public AimdLimitAlgorithmConfigBuilder minLimit(int minLimit) {
    this.minLimit = minLimit;
    return this;
  }

  public AimdLimitAlgorithmConfigBuilder maxLimit(int maxLimit) {
    this.maxLimit = maxLimit;
    return this;
  }

  public AimdLimitAlgorithmConfigBuilder backoffRatio(double backoffRatio) {
    this.backoffRatio = backoffRatio;
    return this;
  }

  public AimdLimitAlgorithmConfigBuilder smoothingTimeConstant(Duration smoothingTimeConstant) {
    this.smoothingTimeConstant = smoothingTimeConstant;
    return this;
  }

  public AimdLimitAlgorithmConfigBuilder errorRateThreshold(double errorRateThreshold) {
    this.errorRateThreshold = errorRateThreshold;
    return this;
  }

  public AimdLimitAlgorithmConfigBuilder windowedIncrease(boolean windowedIncrease) {
    this.windowedIncrease = windowedIncrease;
    return this;
  }

  public AimdLimitAlgorithmConfigBuilder minUtilizationThreshold(double minUtilizationThreshold) {
    this.minUtilizationThreshold = minUtilizationThreshold;
    return this;
  }

  @Override
  public AimdLimitAlgorithmConfig build() {
    return new AimdLimitAlgorithmConfig(
        initialLimit,
        minLimit,
        maxLimit,
        backoffRatio,
        smoothingTimeConstant,
        errorRateThreshold,
        windowedIncrease,
        minUtilizationThreshold
    ).inference();
  }
}
