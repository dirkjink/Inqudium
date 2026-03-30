package eu.inqudium.core.bulkhead;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BulkheadConfigTest {

  @Nested
  class WaitDurationConfiguration {

    @Test
    void an_extremely_large_duration_is_automatically_capped_to_the_maximum_safe_nanosecond_value() {
      // Given
      // An extreme duration that would normally cause an ArithmeticException when calling toNanos()
      Duration extremeDuration = ChronoUnit.FOREVER.getDuration();

      // When
      // We configure the builder with this extreme duration
      BulkheadConfig config = BulkheadConfig.builder()
          .maxWaitDuration(extremeDuration)
          .build();

      // Then
      // The duration is capped to the maximum safe nanosecond value (Long.MAX_VALUE)
      assertThat(config.getMaxWaitDuration()).isEqualTo(Duration.ofNanos(Long.MAX_VALUE));

      // And calling toNanos() on the resulting configuration must not throw an exception
      assertThatCode(() -> config.getMaxWaitDuration().toNanos())
          .doesNotThrowAnyException();
    }

    @Test
    void a_normal_duration_is_accepted_without_modification() {
      // Given
      // A standard duration well within the bounds of nanosecond conversion
      Duration normalDuration = Duration.ofSeconds(30);

      // When
      // We configure the builder with the normal duration
      BulkheadConfig config = BulkheadConfig.builder()
          .maxWaitDuration(normalDuration)
          .build();

      // Then
      // The duration remains exactly as configured
      assertThat(config.getMaxWaitDuration()).isEqualTo(normalDuration);
    }
  }
}