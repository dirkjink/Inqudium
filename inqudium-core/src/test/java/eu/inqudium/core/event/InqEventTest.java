package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InqEventTest {

  @Nested
  class ConstructorValidation {

    @Test
    void should_retain_all_constructor_parameters() {
      // Given
      String expectedCallId = "call-123";
      String expectedElementName = "my-circuit-breaker";
      // Using a real constant instead of a mock
      InqElementType expectedType = InqElementType.NO_ELEMENT;
      Instant expectedTimestamp = Instant.now();

      // When
      InqEvent actualEvent = new InqEvent(expectedCallId, expectedElementName, expectedType, expectedTimestamp) {
        // Anonymous concrete implementation for testing the abstract base
      };

      // Then
      assertThat(actualEvent.getCallId()).isEqualTo(expectedCallId);
      assertThat(actualEvent.getElementName()).isEqualTo(expectedElementName);
      assertThat(actualEvent.getElementType()).isEqualTo(expectedType);
      assertThat(actualEvent.getTimestamp()).isEqualTo(expectedTimestamp);
    }

    @Test
    void should_throw_exception_if_call_id_is_null() {
      // Given
      String nullCallId = null;

      // When & Then
      assertThatThrownBy(() -> new InqEvent(nullCallId, "name", InqElementType.NO_ELEMENT, Instant.now()) {
      })
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("callId must not be null");
    }
  }
}

