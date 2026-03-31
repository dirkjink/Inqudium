package eu.inqudium.imperative.ratelimiter;

import eu.inqudium.core.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.ratelimiter.RateLimiterEvent;
import eu.inqudium.core.ratelimiter.RateLimiterException;
import eu.inqudium.core.ratelimiter.RateLimiterSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ImperativeRateLimiter")
class ImperativeRateLimiterTest {

  private static final Instant BASE_TIME = Instant.parse("2025-01-01T00:00:00Z");
  private TestClock clock;

  @BeforeEach
  void setUp() {
    clock = new TestClock(BASE_TIME);
  }

  private RateLimiterConfig defaultConfig() {
    return RateLimiterConfig.builder("test-limiter")
        .capacity(5)
        .refillPermits(5)
        .refillPeriod(Duration.ofSeconds(1))
        .defaultTimeout(Duration.ZERO)
        .build();
  }

  private ImperativeRateLimiter createLimiter() {
    return new ImperativeRateLimiter(defaultConfig(), clock);
  }

  private ImperativeRateLimiter createLimiter(RateLimiterConfig config) {
    return new ImperativeRateLimiter(config, clock);
  }

  static class TestClock extends Clock {
    private volatile Instant instant;

    TestClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      this.instant = this.instant.plus(duration);
    }

    void set(Instant instant) {
      this.instant = instant;
    }

    @Override
    public Instant instant() {
      return instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  // ================================================================
  // Successful Execution
  // ================================================================

  @Nested
  @DisplayName("Successful Execution")
  class SuccessfulExecution {

    @Test
    @DisplayName("should return the result of a successful callable")
    void should_return_the_result_of_a_successful_callable() throws Exception {
      // Given
      ImperativeRateLimiter limiter = createLimiter();

      // When
      String result = limiter.execute(() -> "hello");

      // Then
      assertThat(result).isEqualTo("hello");
    }

    @Test
    @DisplayName("should allow calls up to the configured capacity")
    void should_allow_calls_up_to_the_configured_capacity() throws Exception {
      // Given
      ImperativeRateLimiter limiter = createLimiter(); // capacity=5

      // When / Then — all 5 should succeed
      for (int i = 0; i < 5; i++) {
        assertThat(limiter.execute(() -> "ok")).isEqualTo("ok");
      }
    }

    @Test
    @DisplayName("should execute a runnable without throwing")
    void should_execute_a_runnable_without_throwing() throws Exception {
      // Given
      ImperativeRateLimiter limiter = createLimiter();
      AtomicInteger counter = new AtomicInteger(0);

      // When
      limiter.execute(counter::incrementAndGet);

      // Then
      assertThat(counter.get()).isEqualTo(1);
    }
  }

  // ================================================================
  // Rate Limiting (Fail-Fast)
  // ================================================================

  @Nested
  @DisplayName("Rate Limiting — Fail-Fast Mode")
  class RateLimitingFailFast {

    @Test
    @DisplayName("should throw RateLimiterException when the bucket is exhausted")
    void should_throw_rate_limiter_exception_when_the_bucket_is_exhausted() throws Exception {
      // Given
      ImperativeRateLimiter limiter = createLimiter(); // capacity=5
      for (int i = 0; i < 5; i++) {
        limiter.execute(() -> "ok");
      }

      // When / Then
      assertThatThrownBy(() -> limiter.execute(() -> "should fail"))
          .isInstanceOf(RateLimiterException.class)
          .hasMessageContaining("test-limiter");
    }

    @Test
    @DisplayName("should include the wait duration in the exception")
    void should_include_the_wait_duration_in_the_exception() throws Exception {
      // Given
      ImperativeRateLimiter limiter = createLimiter();
      for (int i = 0; i < 5; i++) {
        limiter.execute(() -> "ok");
      }

      // When / Then
      assertThatThrownBy(() -> limiter.execute(() -> "fail"))
          .isInstanceOf(RateLimiterException.class)
          .satisfies(e -> {
            RateLimiterException rle = (RateLimiterException) e;
            assertThat(rle.getWaitDuration()).isPositive();
          });
    }

    @Test
    @DisplayName("should allow calls again after the refill period")
    void should_allow_calls_again_after_the_refill_period() throws Exception {
      // Given
      ImperativeRateLimiter limiter = createLimiter();
      for (int i = 0; i < 5; i++) {
        limiter.execute(() -> "ok");
      }

      // When — advance time past refill period
      clock.advance(Duration.ofSeconds(1));

      // Then
      assertThat(limiter.execute(() -> "recovered")).isEqualTo("recovered");
    }
  }

  // ================================================================
  // tryAcquirePermission
  // ================================================================

  @Nested
  @DisplayName("Direct Permission Check")
  class DirectPermissionCheck {

    @Test
    @DisplayName("should return true when permits are available")
    void should_return_true_when_permits_are_available() {
      // Given
      ImperativeRateLimiter limiter = createLimiter();

      // When
      boolean permitted = limiter.tryAcquirePermission();

      // Then
      assertThat(permitted).isTrue();
    }

    @Test
    @DisplayName("should return false when the bucket is empty")
    void should_return_false_when_the_bucket_is_empty() {
      // Given
      ImperativeRateLimiter limiter = createLimiter();
      for (int i = 0; i < 5; i++) {
        limiter.tryAcquirePermission();
      }

      // When
      boolean permitted = limiter.tryAcquirePermission();

      // Then
      assertThat(permitted).isFalse();
    }

    @Test
    @DisplayName("should decrement the available permits on each successful acquisition")
    void should_decrement_the_available_permits_on_each_successful_acquisition() {
      // Given
      ImperativeRateLimiter limiter = createLimiter(); // capacity=5

      // When
      limiter.tryAcquirePermission();
      limiter.tryAcquirePermission();

      // Then
      assertThat(limiter.getAvailablePermits()).isEqualTo(3);
    }
  }

  // ================================================================
  // Fallback
  // ================================================================

  @Nested
  @DisplayName("Fallback Execution")
  class FallbackExecution {

    @Test
    @DisplayName("should return the primary result when permits are available")
    void should_return_the_primary_result_when_permits_are_available() throws Exception {
      // Given
      ImperativeRateLimiter limiter = createLimiter();

      // When
      String result = limiter.executeWithFallback(
          () -> "primary",
          () -> "fallback"
      );

      // Then
      assertThat(result).isEqualTo("primary");
    }

    @Test
    @DisplayName("should return the fallback value when rate-limited")
    void should_return_the_fallback_value_when_rate_limited() throws Exception {
      // Given
      ImperativeRateLimiter limiter = createLimiter();
      for (int i = 0; i < 5; i++) {
        limiter.execute(() -> "ok");
      }

      // When
      String result = limiter.executeWithFallback(
          () -> "primary",
          () -> "fallback"
      );

      // Then
      assertThat(result).isEqualTo("fallback");
    }

    @Test
    @DisplayName("should still propagate non-rate-limiter exceptions with fallback")
    void should_still_propagate_non_rate_limiter_exceptions_with_fallback() {
      // Given
      ImperativeRateLimiter limiter = createLimiter();

      // When / Then
      assertThatThrownBy(() -> limiter.executeWithFallback(
          () -> {
            throw new IllegalArgumentException("bad input");
          },
          () -> "fallback"
      )).isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ================================================================
  // Blocking Wait
  // ================================================================

  @Nested
  @DisplayName("Blocking Wait Mode")
  class BlockingWaitMode {

    @Test
    @DisplayName("should wait and succeed when permits refill within the timeout")
    void should_wait_and_succeed_when_permits_refill_within_the_timeout() throws Exception {
      // Given — capacity=2, timeout=2s, refill every 500ms
      RateLimiterConfig config = RateLimiterConfig.builder("wait-test")
          .capacity(2)
          .refillPermits(2)
          .refillPeriod(Duration.ofMillis(500))
          .defaultTimeout(Duration.ofSeconds(2))
          .build();
      ImperativeRateLimiter limiter = createLimiter(config);

      // Exhaust permits
      limiter.execute(() -> "ok");
      limiter.execute(() -> "ok");

      // Schedule a clock advance to simulate time passing during the park
      Thread.ofVirtual().start(() -> {
        try {
          Thread.sleep(50);
        } catch (InterruptedException e) {
        }
        clock.advance(Duration.ofMillis(600));
      });

      // When — this should block and then succeed after the refill
      String result = limiter.execute(() -> "after-wait");

      // Then
      assertThat(result).isEqualTo("after-wait");
    }
  }

  // ================================================================
  // Event Listeners
  // ================================================================

  @Nested
  @DisplayName("Event Listeners")
  class EventListeners {

    @Test
    @DisplayName("should emit a PERMITTED event on successful acquisition")
    void should_emit_a_permitted_event_on_successful_acquisition() {
      // Given
      ImperativeRateLimiter limiter = createLimiter();
      List<RateLimiterEvent> events = new ArrayList<>();
      limiter.onEvent(events::add);

      // When
      limiter.tryAcquirePermission();

      // Then
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().type()).isEqualTo(RateLimiterEvent.Type.PERMITTED);
    }

    @Test
    @DisplayName("should emit a REJECTED event when rate-limited in fail-fast mode")
    void should_emit_a_rejected_event_when_rate_limited_in_fail_fast_mode() throws Exception {
      // Given
      ImperativeRateLimiter limiter = createLimiter();
      List<RateLimiterEvent> events = new ArrayList<>();
      limiter.onEvent(events::add);

      // Exhaust permits
      for (int i = 0; i < 5; i++) {
        limiter.execute(() -> "ok");
      }
      events.clear();

      // When
      try {
        limiter.execute(() -> "fail");
      } catch (RateLimiterException e) {
      }

      // Then
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().type()).isEqualTo(RateLimiterEvent.Type.REJECTED);
    }

    @Test
    @DisplayName("should emit a DRAINED event when draining")
    void should_emit_a_drained_event_when_draining() {
      // Given
      ImperativeRateLimiter limiter = createLimiter();
      List<RateLimiterEvent> events = new ArrayList<>();
      limiter.onEvent(events::add);

      // When
      limiter.drain();

      // Then
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().type()).isEqualTo(RateLimiterEvent.Type.DRAINED);
    }

    @Test
    @DisplayName("should emit a RESET event when resetting")
    void should_emit_a_reset_event_when_resetting() {
      // Given
      ImperativeRateLimiter limiter = createLimiter();
      List<RateLimiterEvent> events = new ArrayList<>();
      limiter.onEvent(events::add);

      // When
      limiter.reset();

      // Then
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().type()).isEqualTo(RateLimiterEvent.Type.RESET);
    }
  }

  // ================================================================
  // Drain & Reset
  // ================================================================

  @Nested
  @DisplayName("Drain and Reset")
  class DrainAndReset {

    @Test
    @DisplayName("should reject all calls after draining")
    void should_reject_all_calls_after_draining() {
      // Given
      ImperativeRateLimiter limiter = createLimiter();
      limiter.drain();

      // When / Then
      assertThat(limiter.tryAcquirePermission()).isFalse();
    }

    @Test
    @DisplayName("should restore full capacity after resetting")
    void should_restore_full_capacity_after_resetting() throws Exception {
      // Given
      ImperativeRateLimiter limiter = createLimiter();
      for (int i = 0; i < 5; i++) {
        limiter.execute(() -> "ok");
      }

      // When
      limiter.reset();

      // Then
      assertThat(limiter.getAvailablePermits()).isEqualTo(5);
      assertThat(limiter.execute(() -> "after-reset")).isEqualTo("after-reset");
    }
  }

  // ================================================================
  // Refill Behaviour through the Wrapper
  // ================================================================

  @Nested
  @DisplayName("Refill Behaviour")
  class RefillBehaviour {

    @Test
    @DisplayName("should refill permits automatically when time advances")
    void should_refill_permits_automatically_when_time_advances() {
      // Given
      ImperativeRateLimiter limiter = createLimiter();
      for (int i = 0; i < 5; i++) {
        limiter.tryAcquirePermission();
      }
      assertThat(limiter.getAvailablePermits()).isZero();

      // When
      clock.advance(Duration.ofSeconds(1));

      // Then
      assertThat(limiter.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    @DisplayName("should partially refill when less than a full period has elapsed")
    void should_partially_refill_when_less_than_a_full_period_has_elapsed() {
      // Given — refill 2 per 1s, capacity=10
      RateLimiterConfig config = RateLimiterConfig.builder("partial-refill")
          .capacity(10)
          .refillPermits(2)
          .refillPeriod(Duration.ofSeconds(1))
          .build();
      ImperativeRateLimiter limiter = createLimiter(config);
      // consume all 10
      for (int i = 0; i < 10; i++) {
        limiter.tryAcquirePermission();
      }

      // When — advance 2.5 seconds → 2 full periods → 4 permits
      clock.advance(Duration.ofMillis(2500));

      // Then
      assertThat(limiter.getAvailablePermits()).isEqualTo(4);
    }
  }

  // ================================================================
  // Concurrency with Virtual Threads
  // ================================================================

  @Nested
  @DisplayName("Concurrency with Virtual Threads")
  class ConcurrencyWithVirtualThreads {

    @Test
    @DisplayName("should never issue more permits than capacity under concurrent access")
    void should_never_issue_more_permits_than_capacity_under_concurrent_access() throws Exception {
      // Given — capacity=20, fail-fast
      RateLimiterConfig config = RateLimiterConfig.builder("concurrency-test")
          .capacity(20)
          .refillPermits(20)
          .refillPeriod(Duration.ofSeconds(10)) // no refill during test
          .defaultTimeout(Duration.ZERO)
          .build();
      ImperativeRateLimiter limiter = createLimiter(config);
      int threadCount = 100;
      AtomicInteger permittedCount = new AtomicInteger(0);
      AtomicInteger rejectedCount = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(threadCount);

      // When
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> {
            try {
              if (limiter.tryAcquirePermission()) {
                permittedCount.incrementAndGet();
              } else {
                rejectedCount.incrementAndGet();
              }
            } finally {
              latch.countDown();
            }
          });
        }
        latch.await(10, TimeUnit.SECONDS);
      }

      // Then — exactly 20 should have been permitted
      assertThat(permittedCount.get()).isEqualTo(20);
      assertThat(rejectedCount.get()).isEqualTo(80);
    }

    @Test
    @DisplayName("should handle concurrent execute calls safely with virtual threads")
    void should_handle_concurrent_execute_calls_safely_with_virtual_threads() throws Exception {
      // Given
      RateLimiterConfig config = RateLimiterConfig.builder("concurrent-exec")
          .capacity(50)
          .refillPermits(50)
          .refillPeriod(Duration.ofSeconds(10))
          .defaultTimeout(Duration.ZERO)
          .build();
      ImperativeRateLimiter limiter = createLimiter(config);
      int threadCount = 200;
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger failCount = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(threadCount);

      // When
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> {
            try {
              limiter.execute(() -> {
                successCount.incrementAndGet();
                return "ok";
              });
            } catch (RateLimiterException e) {
              failCount.incrementAndGet();
            } catch (Exception e) {
            } finally {
              latch.countDown();
            }
          });
        }
        latch.await(10, TimeUnit.SECONDS);
      }

      // Then
      assertThat(successCount.get()).isEqualTo(50);
      assertThat(failCount.get()).isEqualTo(150);
      assertThat(successCount.get() + failCount.get()).isEqualTo(200);
    }
  }

  // ================================================================
  // Custom Timeout Override
  // ================================================================

  @Nested
  @DisplayName("Custom Timeout Override")
  class CustomTimeoutOverride {

    @Test
    @DisplayName("should use the per-call timeout instead of the default when provided")
    void should_use_the_per_call_timeout_instead_of_the_default_when_provided() throws Exception {
      // Given — default timeout = 0 (fail-fast), but we'll override
      RateLimiterConfig config = RateLimiterConfig.builder("timeout-override")
          .capacity(1)
          .refillPermits(1)
          .refillPeriod(Duration.ofMillis(200))
          .defaultTimeout(Duration.ZERO)
          .build();
      ImperativeRateLimiter limiter = createLimiter(config);
      limiter.execute(() -> "first"); // exhaust

      // Schedule a refill
      Thread.ofVirtual().start(() -> {
        try {
          Thread.sleep(50);
        } catch (InterruptedException e) {
        }
        clock.advance(Duration.ofMillis(250));
      });

      // When — override with a generous timeout
      String result = limiter.execute(() -> "waited", Duration.ofSeconds(5));

      // Then
      assertThat(result).isEqualTo("waited");
    }
  }

  // ================================================================
  // Introspection
  // ================================================================

  @Nested
  @DisplayName("Introspection")
  class Introspection {

    @Test
    @DisplayName("should return the correct available permits count")
    void should_return_the_correct_available_permits_count() throws Exception {
      // Given
      ImperativeRateLimiter limiter = createLimiter(); // capacity=5

      // When
      limiter.execute(() -> "ok");
      limiter.execute(() -> "ok");

      // Then
      assertThat(limiter.getAvailablePermits()).isEqualTo(3);
    }

    @Test
    @DisplayName("should return the configuration")
    void should_return_the_configuration() {
      // Given
      RateLimiterConfig config = defaultConfig();
      ImperativeRateLimiter limiter = createLimiter(config);

      // When / Then
      assertThat(limiter.getConfig()).isEqualTo(config);
    }

    @Test
    @DisplayName("should return a consistent snapshot")
    void should_return_a_consistent_snapshot() {
      // Given
      ImperativeRateLimiter limiter = createLimiter();

      // When
      RateLimiterSnapshot snapshot = limiter.getSnapshot();

      // Then
      assertThat(snapshot.availablePermits()).isEqualTo(5);
      assertThat(snapshot.lastRefillTime()).isEqualTo(BASE_TIME);
    }
  }
}
