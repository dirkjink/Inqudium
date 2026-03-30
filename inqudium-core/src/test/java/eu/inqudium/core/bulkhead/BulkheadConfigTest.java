package eu.inqudium.core.bulkhead;

import eu.inqudium.core.InqCallIdGenerator;
import eu.inqudium.core.InqClock;
import eu.inqudium.core.compatibility.InqCompatibility;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BulkheadConfigTest {

  @Nested
  class DefaultValues {

    @Test
    void a_default_configuration_has_the_expected_standard_values() {
      // Given
      // We retrieve the default configuration
      BulkheadConfig config = BulkheadConfig.ofDefaults();

      // When / Then
      // The default values should match the specified framework standards
      assertThat(config.getMaxConcurrentCalls()).isEqualTo(25);
      assertThat(config.getMaxWaitDuration()).isEqualTo(Duration.ZERO);
      assertThat(config.getCompatibility()).isEqualTo(InqCompatibility.ofDefaults());
      assertThat(config.getClock()).isNotNull();
      assertThat(config.getLogger()).isNotNull();
      assertThat(config.getCallIdGenerator()).isNotNull();
    }
  }

  @Nested
  class BuilderConfiguration {

    @Test
    void a_custom_configuration_can_be_built_with_specific_values() {
      // Given
      // We define custom values for our bulkhead configuration
      int customMaxCalls = 50;
      Duration customDuration = Duration.ofSeconds(5);
      InqClock customClock = () -> Instant.EPOCH;
      Logger customLogger = LoggerFactory.getLogger("CustomLogger");
      InqCallIdGenerator customGenerator = () -> "custom-id";
      InqCompatibility customCompatibility = InqCompatibility.ofDefaults();

      // When
      // We build the configuration with these exact values
      BulkheadConfig config = BulkheadConfig.builder()
          .maxConcurrentCalls(customMaxCalls)
          .maxWaitDuration(customDuration)
          .clock(customClock)
          .logger(customLogger)
          .callIdGenerator(customGenerator)
          .compatibility(customCompatibility)
          .build();

      // Then
      // The configuration must expose the exact values we provided
      assertThat(config.getMaxConcurrentCalls()).isEqualTo(customMaxCalls);
      assertThat(config.getMaxWaitDuration()).isEqualTo(customDuration);
      assertThat(config.getClock()).isSameAs(customClock);
      assertThat(config.getLogger()).isSameAs(customLogger);
      assertThat(config.getCallIdGenerator()).isSameAs(customGenerator);
      assertThat(config.getCompatibility()).isSameAs(customCompatibility);
    }
  }

  @Nested
  class WaitDurationEdgeCases {

    @Test
    void an_extremely_large_duration_is_automatically_capped_to_the_maximum_safe_nanosecond_value() {
      // Given
      // An extreme duration that would normally cause an ArithmeticException
      Duration extremeDuration = ChronoUnit.FOREVER.getDuration();

      // When
      // We configure the builder with this extreme duration
      BulkheadConfig config = BulkheadConfig.builder()
          .maxWaitDuration(extremeDuration)
          .build();

      // Then
      // The duration is capped to the maximum safe nanosecond value
      assertThat(config.getMaxWaitDuration()).isEqualTo(Duration.ofNanos(Long.MAX_VALUE));

      // And calling toNanos() must not throw an exception
      assertThatCode(() -> config.getMaxWaitDuration().toNanos())
          .doesNotThrowAnyException();
    }
  }

  @Nested
  class EqualityAndHashCode {

    @Test
    void two_configurations_with_identical_values_are_considered_equal() {
      // Given
      // Two separate configurations built with the exact same values
      BulkheadConfig config1 = BulkheadConfig.builder()
          .maxConcurrentCalls(10)
          .maxWaitDuration(Duration.ofSeconds(1))
          .build();

      BulkheadConfig config2 = BulkheadConfig.builder()
          .maxConcurrentCalls(10)
          .maxWaitDuration(Duration.ofSeconds(1))
          .build();

      // When / Then
      // They must be equal and have the same hash code
      assertThat(config1).isEqualTo(config2);
      assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
    }

    @Test
    void two_configurations_with_different_values_are_not_considered_equal() {
      // Given
      // Two configurations with different max concurrent calls
      BulkheadConfig config1 = BulkheadConfig.builder().maxConcurrentCalls(10).build();
      BulkheadConfig config2 = BulkheadConfig.builder().maxConcurrentCalls(20).build();

      // When / Then
      // They must not be equal
      assertThat(config1).isNotEqualTo(config2);
    }
  }
}