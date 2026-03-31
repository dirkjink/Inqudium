package eu.inqudium.core.bulkhead;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class VegasLimitAlgorithmTest {
  @Nested
  @DisplayName("Windowed Probing and Growth")
  class WindowedProbingAndGrowth {

    @Test
    void limit_should_grow_exactly_by_one_after_full_window_of_successful_requests_without_congestion() {
      // Given
      // Initial limit is 10. To grow by 1, it should take exactly 10 requests.
      // Smoothing factor 1.0 means no smoothing, decay 0.0 means baseline never decays.
      int initialLimit = 10;
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
          initialLimit, 1, 100, 1.0, 0.0
      );

      // When
      // We simulate a stable downstream system where RTT matches the baseline (gradient = 1.0)
      for (int i = 0; i < initialLimit; i++) {
        algorithm.update(Duration.ofMillis(50), true);
      }

      // Then
      // The limit should have increased from 10 to exactly 11.
      assertThat(algorithm.getLimit()).isEqualTo(11);
    }

    @Test
    void limit_should_not_grow_explosively_when_handling_high_throughput() {
      // Given
      // A large initial limit to simulate high throughput capacity.
      int initialLimit = 500;
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
          initialLimit, 1, 2000, 1.0, 0.0
      );

      // When
      // We fire 100 successful requests. With the old (+0.5) logic, the limit would grow by 50.
      for (int i = 0; i < 100; i++) {
        algorithm.update(Duration.ofMillis(20), true);
      }

      // Then
      // With windowed probing (+1.0/500), 100 requests only add ~0.2.
      // Truncated to int, the visible limit remains at 500.
      assertThat(algorithm.getLimit()).isEqualTo(500);
    }
  }

  @Nested
  @DisplayName("Congestion and Gradient Reaction")
  class CongestionAndGradientReaction {

    @Test
    void limit_should_decrease_proportionally_when_current_Rtt_is_twice_the_baseline() {
      // Given
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
          100, 1, 200, 1.0, 0.0
      );
      // Establish the no-load baseline at 10ms
      algorithm.update(Duration.ofMillis(10), true);

      // When
      // Downstream slows down to 20ms (gradient = 10 / 20 = 0.5)
      algorithm.update(Duration.ofMillis(20), true);

      // Then
      // newLimit = 100 * 0.5 + (1.0 / 100) = 50.01 -> truncated to 50
      assertThat(algorithm.getLimit()).isEqualTo(50);
    }

    @Test
    void limit_should_drop_safely_to_the_configured_minimum_limit_but_never_below() {
      // Given
      int minLimit = 5;
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
          10, minLimit, 100, 1.0, 0.0
      );
      // Establish baseline at 10ms
      algorithm.update(Duration.ofMillis(10), true);

      // When
      // Massive congestion occurs (gradient is capped at 0.5 minimum per step)
      // We run it enough times to force the limit into the floor.
      for (int i = 0; i < 10; i++) {
        algorithm.update(Duration.ofMillis(5000), true);
      }

      // Then
      // Limit must not be lower than minLimit
      assertThat(algorithm.getLimit()).isEqualTo(minLimit);
    }
  }

  @Nested
  @DisplayName("Failure Fallback")
  class FailureFallback {

    @Test
    void limit_should_be_reduced_by_fixed_multiplicative_factor_on_failed_requests() {
      // Given
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(
          100, 1, 200, 1.0, 0.0
      );

      // When
      // A request fails (e.g. timeout or 5xx error)
      algorithm.update(Duration.ofMillis(100), false);

      // Then
      // Fallback scales by 0.8: 100 * 0.8 = 80
      assertThat(algorithm.getLimit()).isEqualTo(80);
    }
  }

  @Nested
  class LatencyBasedLimitAdjustments {

    @Test
    void an_increase_in_latency_causes_the_gradient_limit_to_smoothly_decrease() {
      // Given
      // Initial limit 10, min 2, max 20, smoothing 1.0 (instant reaction for testing)
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(10, 2, 20, 1.0);

      // We establish a baseline no-load RTT of 50ms
      algorithm.update(Duration.ofMillis(50), true);
      assertThat(algorithm.getLimit()).isGreaterThanOrEqualTo(10);

      // When
      // The backend becomes slow, and the latency spikes to 100ms (gradient 0.5)
      algorithm.update(Duration.ofMillis(100), true);

      // Then
      // The limit must scale down towards half of the current limit
      assertThat(algorithm.getLimit()).isLessThan(10);
    }

    @Test
    void an_absolute_failure_immediately_penalizes_the_limit_to_protect_the_backend() {
      // Given
      VegasLimitAlgorithm algorithm = new VegasLimitAlgorithm(10, 2, 20, 1.0);

      // When
      // A request fails completely (e.g. 503 error)
      algorithm.update(Duration.ofMillis(50), false);

      // Then
      // The limit must be aggressively reduced (multiplicative decrease)
      assertThat(algorithm.getLimit()).isEqualTo(8); // 10 * 0.8
    }
  }
}
