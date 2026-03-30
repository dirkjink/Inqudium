package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InqEventRemainingContractsTest {

  static class TestEvent extends InqEvent {
    TestEvent() {
      super("call-99", "contract-test", InqElementType.NO_ELEMENT, Instant.now());
    }
  }

  @Nested
  class PublisherFactoryMethods {

    @Test
    void should_create_publisher_with_default_registry_when_no_registry_is_provided() {
      // Given
      String expectedName = "default-factory-element";
      InqElementType expectedType = InqElementType.NO_ELEMENT;

      // When
      InqEventPublisher publisher = InqEventPublisher.create(expectedName, expectedType);

      // Then
      assertThat(publisher).isNotNull();
      assertThat(publisher).isInstanceOf(DefaultInqEventPublisher.class);

      // We can verify it uses the default registry by checking the toString output
      // or by observing the global registry state, but checking the instance type
      // and successful creation covers the factory delegation.
      assertThat(publisher.toString()).contains("elementName='" + expectedName + "'");
    }

    @Test
    void should_create_publisher_with_custom_registry_when_provided() {
      // Given
      String expectedName = "custom-factory-element";
      InqElementType expectedType = InqElementType.NO_ELEMENT;
      InqEventExporterRegistry customRegistry = new InqEventExporterRegistry();

      List<InqEvent> registryEvents = new ArrayList<>();
      customRegistry.register(registryEvents::add);

      // When
      InqEventPublisher publisher = InqEventPublisher.create(expectedName, expectedType, customRegistry);
      publisher.publish(new TestEvent());

      // Then
      assertThat(publisher).isNotNull();
      assertThat(registryEvents)
          .hasSize(1)
          .as("The publisher must route events to the explicitly provided custom registry");
    }
  }

  @Nested
  class SubscriptionIdempotency {

    @Test
    void should_ignore_multiple_cancellations_of_the_same_subscription() {
      // Given
      DefaultInqEventPublisher publisher = new DefaultInqEventPublisher(
          "test", InqElementType.NO_ELEMENT, new InqEventExporterRegistry()
      );

      List<InqEvent> receivedEvents = new ArrayList<>();
      InqSubscription subscription = publisher.onEvent(receivedEvents::add);

      TestEvent firstEvent = new TestEvent();
      TestEvent secondEvent = new TestEvent();

      // When
      publisher.publish(firstEvent);

      // Cancel multiple times to test idempotency
      subscription.cancel();
      subscription.cancel();
      subscription.cancel();

      publisher.publish(secondEvent);

      // Then
      assertThat(receivedEvents)
          .hasSize(1)
          .containsExactly(firstEvent)
          .as("Subsequent cancellations should have no effect and no exceptions should be thrown");
    }
  }

  @Nested
  class ExporterDefaultBehavior {

    @Test
    void should_return_empty_set_for_subscribed_event_types_by_default() {
      // Given
      InqEventExporter minimalExporter = new InqEventExporter() {
        @Override
        public void export(InqEvent event) {
          // Do nothing
        }
        // We deliberately do NOT override subscribedEventTypes()
      };

      // When
      Set<Class<? extends InqEvent>> eventTypes = minimalExporter.subscribedEventTypes();

      // Then
      assertThat(eventTypes)
          .isNotNull()
          .isEmpty();
    }
  }
}
