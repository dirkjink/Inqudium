package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;

import java.util.function.Consumer;

/**
 * Per-element event publisher contract.
 *
 * <p>Each element instance owns its own publisher. Events flow in two directions
 * from the publisher (ADR-003):
 * <ul>
 *   <li>To local consumers registered via {@link #onEvent}</li>
 *   <li>To global exporters registered in the {@link InqEventExporterRegistry}</li>
 * </ul>
 *
 * <h2>Creating a publisher</h2>
 * <pre>{@code
 * // Production — uses the global default registry
 * var publisher = InqEventPublisher.create("paymentService", InqElementType.CIRCUIT_BREAKER);
 *
 * // Testing — uses an isolated registry
 * var testRegistry = new InqEventExporterRegistry();
 * var publisher = InqEventPublisher.create("paymentService", InqElementType.CIRCUIT_BREAKER, testRegistry);
 * }</pre>
 *
 * <h2>Subscribing to events</h2>
 * <pre>{@code
 * InqSubscription sub = circuitBreaker.getEventPublisher()
 *     .onEvent(CircuitBreakerOnStateTransitionEvent.class, event -> { ... });
 *
 * // Later — unsubscribe to prevent memory leaks
 * sub.cancel();
 * }</pre>
 *
 * @since 0.1.0
 */
public interface InqEventPublisher {

  /**
   * Creates a new publisher for an element instance, using the
   * {@linkplain InqEventExporterRegistry#getDefault() global default registry}.
   *
   * @param elementName the name of the element instance
   * @param elementType the type of the element
   * @return a new publisher
   */
  static InqEventPublisher create(String elementName, InqElementType elementType) {
    return new DefaultInqEventPublisher(elementName, elementType, InqEventExporterRegistry.getDefault());
  }

  /**
   * Creates a new publisher for an element instance, using the specified registry.
   *
   * <p>Useful for testing — pass an isolated registry to avoid cross-test pollution.
   *
   * @param elementName the name of the element instance
   * @param elementType the type of the element
   * @param registry    the exporter registry to use
   * @return a new publisher
   */
  static InqEventPublisher create(String elementName, InqElementType elementType,
                                  InqEventExporterRegistry registry) {
    return new DefaultInqEventPublisher(elementName, elementType, registry);
  }

  /**
   * Publishes an event to all registered consumers and global exporters.
   *
   * <p>This method must be thread-safe. Consumer exceptions are caught and
   * do not propagate to the element.
   *
   * @param event the event to publish
   */
  void publish(InqEvent event);

  /**
   * Registers a consumer for all events from this publisher.
   *
   * @param consumer the event consumer
   * @return a subscription handle for cancellation
   */
  InqSubscription onEvent(InqEventConsumer consumer);

  /**
   * Registers a typed consumer that only receives events of the specified type.
   *
   * @param eventType the event class to filter on
   * @param consumer  the typed consumer
   * @param <E>       the event type
   * @return a subscription handle for cancellation
   */
  <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer);
}
