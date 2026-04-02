package eu.inqudium.imperative.bulkhead.config;

import eu.inqudium.core.config.ExtensionBuilder;

import java.time.Duration;

public class CoDelBulkheadStrategyConfigBuilder extends ExtensionBuilder<CoDelBulkheadStrategyConfig> {
  private Duration targetDelay;
  private Duration interval;

  CoDelBulkheadStrategyConfigBuilder() {
  }

  CoDelBulkheadStrategyConfigBuilder(Duration targetDelay,
                                     Duration interval) {
    this.targetDelay = targetDelay;
    this.interval = interval;
  }
  // ──────────────────────────────────────────────────────────────────────────
  // Preset Factory Methods
  // ──────────────────────────────────────────────────────────────────────────

  public static CoDelBulkheadStrategyConfigBuilder coDelBulkheadStrategy() {
    return new CoDelBulkheadStrategyConfigBuilder().balanced();
  }

  /**
   * <b>Protective</b> preset — prioritizes downstream safety over throughput.
   *
   * <p>Designed for critical downstream services where queue buildup is an early
   * warning of cascading failure (e.g., payment gateways, auth services, databases
   * with strict connection limits).
   *
   * <h3>Characteristics</h3>
   * <ul>
   *   <li><b>Low concurrency (20):</b> Tight permit budget limits the blast radius
   *       if the downstream service degrades.</li>
   *   <li><b>Short target delay (50 ms):</b> Detects queuing early. Any request that
   *       waits more than 50 ms for a permit is considered "above target" and starts
   *       the CoDel congestion stopwatch.</li>
   *   <li><b>Short interval (500 ms):</b> If sojourn times remain above target for
   *       500 ms, the first drop occurs. Combined with the stopwatch reset after each
   *       drop, this produces a steady one-drop-per-500ms cadence under sustained
   *       congestion — shedding load quickly without flushing the entire queue.</li>
   * </ul>
   *
   * <h3>Defaults</h3>
   * <table>
   *   <tr><td>Max concurrent calls</td><td>20</td></tr>
   *   <tr><td>Target delay</td><td>50 ms</td></tr>
   *   <tr><td>Interval</td><td>500 ms</td></tr>
   * </table>
   *
   * @return a protectively tuned CoDel strategy
   */
  public CoDelBulkheadStrategyConfigBuilder protective() {
    return new CoDelBulkheadStrategyConfigBuilder(
        Duration.ofMillis(50),      // targetDelay: detect queuing early
        Duration.ofMillis(500)     // interval: start dropping after 500 ms sustained congestion
    );
  }

  /**
   * <b>Balanced</b> preset — the recommended production default.
   *
   * <p>Suitable for most backend-to-backend communication where the downstream
   * service has moderate capacity and occasional latency spikes are normal
   * (e.g., internal microservices, managed databases, message brokers).
   *
   * <h3>Characteristics</h3>
   * <ul>
   *   <li><b>Moderate concurrency (50):</b> Provides reasonable throughput while
   *       limiting the number of threads that can be parked simultaneously.</li>
   *   <li><b>Moderate target delay (100 ms):</b> Tolerates brief wait times caused by
   *       bursty traffic without triggering the congestion stopwatch. Only sustained
   *       queuing beyond 100 ms is considered problematic.</li>
   *   <li><b>Moderate interval (1 s):</b> Gives the downstream service a full second
   *       to recover from a transient spike before the first drop. Long enough to
   *       absorb GC pauses and brief load spikes, short enough to react within
   *       a few seconds to genuine congestion.</li>
   * </ul>
   *
   * <h3>Defaults</h3>
   * <table>
   *   <tr><td>Max concurrent calls</td><td>50</td></tr>
   *   <tr><td>Target delay</td><td>100 ms</td></tr>
   *   <tr><td>Interval</td><td>1 s</td></tr>
   * </table>
   *
   * @return a balanced CoDel strategy suitable for general production use
   */
  public CoDelBulkheadStrategyConfigBuilder balanced() {
    return new CoDelBulkheadStrategyConfigBuilder(
        Duration.ofMillis(100),     // targetDelay: tolerates brief spikes
        Duration.ofSeconds(1)    // interval: absorbs transient congestion
    );
  }

  /**
   * <b>Performant</b> preset — prioritizes throughput over caution.
   *
   * <p>Designed for downstream services with high, elastic capacity where
   * brief queue buildup is acceptable and aggressive dropping would waste
   * capacity (e.g., autoscaling compute clusters, CDN origins, horizontally
   * scaled stateless services).
   *
   * <h3>Characteristics</h3>
   * <ul>
   *   <li><b>High concurrency (100):</b> Allows many requests in flight simultaneously,
   *       maximizing utilization of elastic backends.</li>
   *   <li><b>Tolerant target delay (250 ms):</b> Accepts longer wait times before
   *       considering the system congested. Elastic backends may have variable latency
   *       during scale-up; a higher target prevents premature drops during these
   *       transient periods.</li>
   *   <li><b>Long interval (2 s):</b> Gives the downstream service ample time to
   *       absorb load or scale up before any drops occur. Only truly sustained
   *       congestion (beyond 2 seconds) triggers load shedding. This minimizes
   *       unnecessary request rejection at the cost of slightly slower reaction
   *       to genuine overload.</li>
   * </ul>
   *
   * <h3>Defaults</h3>
   * <table>
   *   <tr><td>Max concurrent calls</td><td>100</td></tr>
   *   <tr><td>Target delay</td><td>250 ms</td></tr>
   *   <tr><td>Interval</td><td>2 s</td></tr>
   * </table>
   *
   * @return a throughput-optimized CoDel strategy
   */
  public CoDelBulkheadStrategyConfigBuilder performant() {
    return new CoDelBulkheadStrategyConfigBuilder(
        Duration.ofMillis(250),     // targetDelay: tolerant of queue buildup
        Duration.ofSeconds(2)     // interval: only drop under sustained congestion
    );
  }

  public CoDelBulkheadStrategyConfigBuilder targetDelay(Duration targetDelay) {
    this.targetDelay = targetDelay;
    return this;
  }

  public CoDelBulkheadStrategyConfigBuilder interval(Duration interval) {
    this.interval = interval;
    return this;
  }

  @Override
  public CoDelBulkheadStrategyConfig build() {
    return new CoDelBulkheadStrategyConfig(
        targetDelay,
        interval
    );
  }
}
