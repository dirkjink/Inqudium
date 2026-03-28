package eu.inqudium.core.timelimiter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Central timeout derivation tool (ADR-012).
 *
 * <p>Takes HTTP client timeout components as input and computes the TimeLimiter
 * timeout and Circuit Breaker {@code slowCallDurationThreshold} using either
 * RSS (Root Sum of Squares) or worst-case addition.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var profile = InqTimeoutProfile.builder()
 *     .connectTimeout(Duration.ofMillis(250))
 *     .responseTimeout(Duration.ofSeconds(3))
 *     .method(TimeoutCalculation.RSS)
 *     .safetyMarginFactor(1.2)   // 20% above computed value
 *     .build();
 *
 * Duration tlTimeout = profile.timeLimiterTimeout();
 * Duration slowThreshold = profile.slowCallDurationThreshold();
 * }</pre>
 *
 * <p>The profile is a pure computation — no framework coupling. Works with
 * Netty, OkHttp, Apache HttpClient, or any other client.
 *
 * @since 0.1.0
 */
public final class InqTimeoutProfile {

  private final List<Duration> timeoutComponents;
  private final TimeoutCalculation method;
  private final double safetyMarginFactor;

  private InqTimeoutProfile(Builder b) {
    this.timeoutComponents = List.copyOf(b.timeoutComponents);
    this.method = b.method;
    this.safetyMarginFactor = b.safetyMarginFactor;
  }

  /**
   * Creates a new builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Computes the recommended TimeLimiter timeout.
   *
   * <p>Uses the selected method (RSS or worst-case) to combine timeout
   * components, then applies the safety margin factor.
   *
   * @return the computed TimeLimiter timeout
   */
  public Duration timeLimiterTimeout() {
    if (timeoutComponents.isEmpty()) {
      return Duration.ofSeconds(5); // fallback
    }

    double nominalMs = 0;
    double toleranceSumOrSquaredSum = 0;

    for (var component : timeoutComponents) {
      double ms = component.toMillis();
      nominalMs += ms * 0.5; // assume nominal is ~50% of timeout
      double tolerance = ms * 0.5; // tolerance is the other 50%

      switch (method) {
        case RSS -> toleranceSumOrSquaredSum += tolerance * tolerance;
        case WORST_CASE -> toleranceSumOrSquaredSum += tolerance;
      }
    }

    double combinedTolerance = switch (method) {
      case RSS -> Math.sqrt(toleranceSumOrSquaredSum);
      case WORST_CASE -> toleranceSumOrSquaredSum;
    };

    double totalMs = (nominalMs + combinedTolerance) * safetyMarginFactor;
    return Duration.ofMillis(Math.round(totalMs));
  }

  /**
   * Computes the recommended Circuit Breaker {@code slowCallDurationThreshold}.
   *
   * <p>Aligned with the TimeLimiter timeout — a call is only "slow" if it
   * reaches the safety net's limit (ADR-012).
   *
   * @return the computed slow call threshold (equal to {@link #timeLimiterTimeout()})
   */
  public Duration slowCallDurationThreshold() {
    return timeLimiterTimeout();
  }

  /**
   * Returns the first timeout component (typically connectTimeout).
   *
   * @return the connect timeout, or Duration.ZERO if no components configured
   */
  public Duration connectTimeout() {
    return timeoutComponents.isEmpty() ? Duration.ZERO : timeoutComponents.getFirst();
  }

  /**
   * Returns the second timeout component (typically responseTimeout).
   *
   * @return the response timeout, or Duration.ZERO if fewer than 2 components
   */
  public Duration responseTimeout() {
    return timeoutComponents.size() < 2 ? Duration.ZERO : timeoutComponents.get(1);
  }

  /**
   * Returns the calculation method used.
   *
   * @return RSS or WORST_CASE
   */
  public TimeoutCalculation getMethod() {
    return method;
  }

  /**
   * Returns the safety margin factor.
   *
   * @return the factor (e.g. 1.2 for 20% margin)
   */
  public double getSafetyMarginFactor() {
    return safetyMarginFactor;
  }

  /**
   * Provides an agnostic configuration model for HTTP client timeouts.
   * <p>
   * This class abstracts the specific timeout parameters of various JVM HTTP clients
   * (like Apache HttpClient, OkHttp, Java 11+ HttpClient, and Spring WebClient)
   * into a unified set of configuration properties.
   * </p>
   *
   * <h3>1. Timeout Configuration incl. Data Types &amp; Units (JVM)</h3>
   * <table border="1" cellpadding="5" cellspacing="0">
   * <tr>
   * <th>HTTP Client (JVM)</th>
   * <th>Timeout Parameter</th>
   * <th>Data Type &amp; Unit</th>
   * <th>Monitored Time Span</th>
   * </tr>
   * <tr>
   * <td><b>Apache HttpClient</b></td>
   * <td>{@code ConnectTimeout}</td>
   * <td><b>v5:</b> {@code Timeout}<br><b>v4:</b> {@code int} (ms)</td>
   * <td>Time limit for the initial TCP handshake.</td>
   * </tr>
   * <tr>
   * <td></td>
   * <td>{@code ResponseTimeout} (v5) / {@code SocketTimeout} (v4)</td>
   * <td><b>v5:</b> {@code Timeout}<br><b>v4:</b> {@code int} (ms)</td>
   * <td>Maximum inactivity time between two received data packets.</td>
   * </tr>
   * <tr>
   * <td></td>
   * <td>{@code ConnectionRequestTimeout}</td>
   * <td><b>v5:</b> {@code Timeout}<br><b>v4:</b> {@code int} (ms)</td>
   * <td>Maximum wait time for a free connection from the connection pool.</td>
   * </tr>
   * <tr>
   * <td><b>OkHttp</b></td>
   * <td>{@code connectTimeout}</td>
   * <td>{@code long} + {@code TimeUnit} or {@code Duration}</td>
   * <td>Time for establishing the TCP connection and TLS handshake.</td>
   * </tr>
   * <tr>
   * <td></td>
   * <td>{@code readTimeout}</td>
   * <td>{@code long} + {@code TimeUnit} or {@code Duration}</td>
   * <td>Maximum inactivity between two successful read operations.</td>
   * </tr>
   * <tr>
   * <td></td>
   * <td>{@code writeTimeout}</td>
   * <td>{@code long} + {@code TimeUnit} or {@code Duration}</td>
   * <td>Maximum time a single write operation is allowed to block on the network socket.</td>
   * </tr>
   * <tr>
   * <td></td>
   * <td>{@code callTimeout}</td>
   * <td>{@code long} + {@code TimeUnit} or {@code Duration}</td>
   * <td>Hard upper limit for the entire call.</td>
   * </tr>
   * <tr>
   * <td><b>Java 11+ HttpClient</b></td>
   * <td>{@code connectTimeout}</td>
   * <td>{@code java.time.Duration}</td>
   * <td>Maximum time allowed for establishing the connection.</td>
   * </tr>
   * <tr>
   * <td></td>
   * <td>{@code timeout}</td>
   * <td>{@code java.time.Duration}</td>
   * <td>Maximum total time for the specific request.</td>
   * </tr>
   * <tr>
   * <td><b>Spring WebClient</b><br><i>(Reactor Netty)</i></td>
   * <td>{@code CONNECT_TIMEOUT_MILLIS}</td>
   * <td>{@code Integer} (strictly <b>ms</b>)</td>
   * <td>Configured in Netty {@code ChannelOption}. Monitors purely the TCP establishment.</td>
   * </tr>
   * <tr>
   * <td></td>
   * <td>{@code ReadTimeoutHandler}</td>
   * <td>{@code int} + {@code TimeUnit} or {@code Duration}</td>
   * <td>Netty level: Time span without new received data.</td>
   * </tr>
   * <tr>
   * <td></td>
   * <td>{@code WriteTimeoutHandler}</td>
   * <td>{@code int} + {@code TimeUnit} or {@code Duration}</td>
   * <td>Netty level: Maximum time a single write operation to the socket is allowed to block.</td>
   * </tr>
   * <tr>
   * <td></td>
   * <td>{@code responseTimeout}</td>
   * <td>{@code java.time.Duration}</td>
   * <td>HttpClient level: Wait time for the response after sending the request.</td>
   * </tr>
   * <tr>
   * <td></td>
   * <td>{@code .timeout()}</td>
   * <td>{@code java.time.Duration}</td>
   * <td>Reactive operator: Hard upper limit for the asynchronous pipeline.</td>
   * </tr>
   * </table>
   *
   * <h3>2. Agnostic HTTP Timeout Configuration Set</h3>
   * <table border="1" cellpadding="5" cellspacing="0">
   * <tr>
   * <th>Timeout Type</th>
   * <th>Agnostic Parameter Name</th>
   * <th>Monitored Time Span</th>
   * <th>Equivalent in Common Clients</th>
   * </tr>
   * <tr>
   * <td><b>Pool Wait Time</b></td>
   * <td>{@code connectionAcquireTimeout}</td>
   * <td>The maximum time waiting for a free TCP connection from an internal pool.</td>
   * <td>Apache: {@code ConnectionRequestTimeout}<br>Spring: {@code ConnectionProvider}</td>
   * </tr>
   * <tr>
   * <td><b>Connection Establishment</b></td>
   * <td>{@code connectionEstablishmentTimeout}</td>
   * <td>The time for the actual TCP connection (and TLS handshake).</td>
   * <td>OkHttp/Java 11+: {@code connectTimeout}<br>Apache: {@code ConnectTimeout}</td>
   * </tr>
   * <tr>
   * <td><b>Read Inactivity</b></td>
   * <td>{@code readInactivityTimeout}</td>
   * <td>Maximum wait time between two received data packets.</td>
   * <td>OkHttp: {@code readTimeout}<br>Apache: {@code ResponseTimeout}</td>
   * </tr>
   * <tr>
   * <td><b>Write Operation</b></td>
   * <td>{@code writeOperationTimeout}</td>
   * <td>Maximum time a single write operation to the network socket is allowed to block.</td>
   * <td>OkHttp: {@code writeTimeout}<br>Spring: {@code WriteTimeoutHandler}</td>
   * </tr>
   * <tr>
   * <td><b>Total Execution Time</b></td>
   * <td>{@code totalExecutionTimeout}</td>
   * <td>Absolute upper limit for the entire lifecycle of the call.</td>
   * <td>Java 11+: {@code timeout}<br>OkHttp: {@code callTimeout}<br>Spring: {@code .timeout()}</td>
   * </tr>
   * </table>
   *
   * <h3>3. Mapping: Agnostic Configuration to JVM Clients</h3>
   * <table border="1" cellpadding="5" cellspacing="0">
   * <tr>
   * <th>Agnostic Parameter</th>
   * <th>Apache HttpClient (v5)</th>
   * <th>OkHttp</th>
   * <th>Java 11+ HttpClient</th>
   * <th>Spring WebClient <i>(Reactor Netty)</i></th>
   * </tr>
   * <tr>
   * <td><b>{@code connectionAcquireTimeout}</b></td>
   * <td>{@code ConnectionRequestTimeout}</td>
   * <td>Implicitly covered by {@code callTimeout}. If unset: infinite block.</td>
   * <td>Implicitly covered by total {@code timeout}. If unset: infinite block.</td>
   * <td>{@code pendingAcquireTimeout}</td>
   * </tr>
   * <tr>
   * <td><b>{@code connectionEstablishmentTimeout}</b></td>
   * <td>{@code ConnectTimeout}</td>
   * <td>{@code connectTimeout}</td>
   * <td>{@code connectTimeout}</td>
   * <td>{@code ChannelOption.CONNECT_TIMEOUT_MILLIS}</td>
   * </tr>
   * <tr>
   * <td><b>{@code readInactivityTimeout}</b></td>
   * <td>{@code ResponseTimeout}</td>
   * <td>{@code readTimeout}</td>
   * <td>Implicitly covered by total {@code timeout}. If unset: infinite block.</td>
   * <td>{@code ReadTimeoutHandler}</td>
   * </tr>
   * <tr>
   * <td><b>{@code writeOperationTimeout}</b></td>
   * <td>No native limit. Relies on OS-level TCP socket timeouts.</td>
   * <td>{@code writeTimeout}</td>
   * <td>Implicitly covered by total {@code timeout}. If unset: infinite block.</td>
   * <td>{@code WriteTimeoutHandler}</td>
   * </tr>
   * <tr>
   * <td><b>{@code totalExecutionTimeout}</b></td>
   * <td>No native limit. Requires external wrapper (e.g., timed {@code Future}).</td>
   * <td>{@code callTimeout}</td>
   * <td>{@code timeout}</td>
   * <td>{@code .timeout(Duration)}</td>
   * </tr>
   * </table>
   */
  public static final class Builder {

    private final List<Duration> timeoutComponents = new ArrayList<>();
    private TimeoutCalculation method = TimeoutCalculation.RSS;
    private double safetyMarginFactor = 1.2;

    private Builder() {
    }

    /**
     * Adds the HTTP connect timeout as a component.
     *
     * @param timeout the connect timeout
     * @return this builder
     */
    public Builder connectTimeout(Duration timeout) {
      timeoutComponents.add(Objects.requireNonNull(timeout));
      return this;
    }

    /**
     * Adds the HTTP response timeout as a component.
     *
     * @param timeout the response timeout
     * @return this builder
     */
    public Builder responseTimeout(Duration timeout) {
      timeoutComponents.add(Objects.requireNonNull(timeout));
      return this;
    }

    /**
     * Adds an additional timeout component (e.g. TLS handshake).
     *
     * @param timeout the additional timeout
     * @return this builder
     */
    public Builder additionalTimeout(Duration timeout) {
      timeoutComponents.add(Objects.requireNonNull(timeout));
      return this;
    }

    /**
     * Sets the calculation method.
     *
     * @param method RSS (default) or WORST_CASE
     * @return this builder
     */
    public Builder method(TimeoutCalculation method) {
      this.method = Objects.requireNonNull(method);
      return this;
    }

    /**
     * Sets the safety margin factor applied to the computed timeout.
     *
     * @param factor the factor (e.g. 1.2 for 20% margin). Default: 1.2
     * @return this builder
     */
    public Builder safetyMarginFactor(double factor) {
      if (factor < 1.0) throw new IllegalArgumentException("Safety margin factor must be >= 1.0, got: " + factor);
      this.safetyMarginFactor = factor;
      return this;
    }

    /**
     * Builds the timeout profile.
     *
     * @return the computed profile
     */
    public InqTimeoutProfile build() {
      return new InqTimeoutProfile(this);
    }
  }
}
