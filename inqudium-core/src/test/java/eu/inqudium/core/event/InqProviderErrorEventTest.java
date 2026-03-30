package eu.inqudium.core.event;


import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InqProviderErrorEventTest {

  @Nested
  class EventCreation {

    @Test
    void should_extract_correct_codes_and_retain_all_properties() {
      // Given
      String providerClass = "com.example.BadProvider";
      String spiInterface = "eu.inqudium.core.event.InqEventExporter";
      String phase = "construction";
      String errorMessage = "Class not found";
      Instant timestamp = Instant.now();

      // When
      InqProviderErrorEvent event = new InqProviderErrorEvent(
          providerClass, spiInterface, phase, errorMessage, timestamp
      );

      // Then
      assertThat(event.getProviderClassName()).isEqualTo(providerClass);
      assertThat(event.getSpiInterfaceName()).isEqualTo(spiInterface);
      assertThat(event.getPhase()).isEqualTo(phase);
      assertThat(event.getErrorMessage()).isEqualTo(errorMessage);
      assertThat(event.getTimestamp()).isEqualTo(timestamp);
      assertThat(event.getElementName()).isEqualTo("InqServiceLoader");
    }
  }
}
