package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.exception.InqException;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Default implementation of {@link InqEventPublisher} that bridges per-element
 * consumers and an {@link InqEventExporterRegistry}.
 *
 * <p>Optimized for read-heavy operations: consumers are stored in an immutable array
 * wrapped in an {@link AtomicReference}. This guarantees lock-free, zero-allocation
 * publishing with optimal CPU cache locality.
 *
 * <h2>Double registration</h2>
 * <p>Registering the same consumer instance multiple times is allowed and results in
 * the consumer being called once per registration on each event. Each registration
 * receives an independent {@link InqSubscription} for independent cancellation.
 *
 * @since 0.1.0
 */
final class DefaultInqEventPublisher implements InqEventPublisher {

  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(DefaultInqEventPublisher.class);

  // Pre-allocated empty array to avoid allocations when resetting to empty
  private static final ConsumerEntry[] EMPTY_CONSUMERS = new ConsumerEntry[0];

  // FIX #9: Threshold at which a warning is logged to detect potential subscription leaks
  private static final int CONSUMER_COUNT_WARNING_THRESHOLD = 256;

  private final String elementName;
  private final InqElementType elementType;
  private final InqEventExporterRegistry registry;

  /**
   * Copy-on-write array holding the local consumers. Array iteration is significantly
   * faster and more cache-friendly than traversing a ConcurrentHashMap.
   */
  private final AtomicReference<ConsumerEntry[]> consumers = new AtomicReference<>(EMPTY_CONSUMERS);

  /**
   * Per-instance subscription ID generator.
   */
  private final AtomicLong subscriptionCounter = new AtomicLong(0);

  DefaultInqEventPublisher(String elementName, InqElementType elementType,
                           InqEventExporterRegistry registry) {
    this.elementName = Objects.requireNonNull(elementName, "elementName must not be null");
    this.elementType = Objects.requireNonNull(elementType, "elementType must not be null");
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
  }

  @Override
  public void publish(InqEvent event) {
    Objects.requireNonNull(event, "event must not be null");

    // Deliver to local consumers using a cache-friendly array iteration
    ConsumerEntry[] currentConsumers = consumers.get();

    for (int i = 0; i < currentConsumers.length; i++) {
      ConsumerEntry entry = currentConsumers[i];
      try {
        entry.consumer().accept(event);
      } catch (Throwable t) {
        InqException.rethrowIfFatal(t);
        // FIX #5: Use entry's description for meaningful logging instead of lambda class names
        LOGGER.warn("[{}] Event consumer [{}] threw on event {}",
            event.getCallId(), entry.description(),
            event.getClass().getSimpleName(), t);
      }
    }
    // Forward to exporters
    try {
      registry.export(event);
    } catch (Throwable t) {
      InqException.rethrowIfFatal(t);
      LOGGER.warn("[{}] Exporter registry threw on event {}",
          event.getCallId(), event.getClass().getSimpleName(), t);
    }
  }

  @Override
  public InqSubscription onEvent(InqEventConsumer consumer) {
    Objects.requireNonNull(consumer, "consumer must not be null");
    long subscriptionId = subscriptionCounter.incrementAndGet();
    // FIX #5: Capture the actual consumer class name for meaningful diagnostics
    String description = consumer.getClass().getName();
    addConsumer(new ConsumerEntry(subscriptionId, consumer, description));
    return () -> removeConsumer(subscriptionId);
  }

  @Override
  public <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer) {
    Objects.requireNonNull(eventType, "eventType must not be null");
    Objects.requireNonNull(consumer, "consumer must not be null");

    // FIX #5: Named wrapper with meaningful toString instead of anonymous lambda.
    // The description captures both the event type filter and the actual consumer class
    // so log output is useful for debugging (instead of "Lambda$42/0x00000008001a3c40").
    String description = "TypedConsumer[eventType=" + eventType.getSimpleName()
        + ", consumer=" + consumer.getClass().getName() + "]";

    InqEventConsumer wrapper = event -> {
      if (eventType.isInstance(event)) {
        consumer.accept(eventType.cast(event));
      }
    };
    long subscriptionId = subscriptionCounter.incrementAndGet();
    addConsumer(new ConsumerEntry(subscriptionId, wrapper, description));
    return () -> removeConsumer(subscriptionId);
  }

  private void addConsumer(ConsumerEntry entry) {
    consumers.updateAndGet(arr -> {
      ConsumerEntry[] newArr = Arrays.copyOf(arr, arr.length + 1);
      newArr[arr.length] = entry;

      // FIX #9: Warn when consumer count exceeds threshold — potential subscription leak
      if (newArr.length == CONSUMER_COUNT_WARNING_THRESHOLD) {
        LOGGER.warn("Publisher '{}' ({}) has reached {} consumers — possible subscription leak. " +
                "Ensure InqSubscription.cancel() is called when consumers are no longer needed.",
            elementName, elementType, CONSUMER_COUNT_WARNING_THRESHOLD);
      }

      return newArr;
    });
  }

  private void removeConsumer(long id) {
    consumers.updateAndGet(arr -> {
      int index = -1;
      for (int i = 0; i < arr.length; i++) {
        if (arr[i].id() == id) {
          index = i;
          break;
        }
      }

      // Not found or already removed (Idempotent behavior)
      if (index < 0) {
        return arr;
      }

      // If it's the last element, return the shared empty array to avoid allocation
      if (arr.length == 1) {
        return EMPTY_CONSUMERS;
      }

      // Create a new array without the cancelled consumer
      ConsumerEntry[] newArr = new ConsumerEntry[arr.length - 1];
      System.arraycopy(arr, 0, newArr, 0, index);
      System.arraycopy(arr, index + 1, newArr, index, arr.length - index - 1);
      return newArr;
    });
  }

  @Override
  public String toString() {
    return "InqEventPublisher{" +
        "elementName='" + elementName + '\'' +
        ", elementType=" + elementType +
        ", consumers=" + consumers.get().length +
        '}';
  }

  /**
   * Pairs a subscription ID with its consumer and a human-readable description
   * for diagnostic logging.
   */
  private record ConsumerEntry(long id, InqEventConsumer consumer, String description) {
  }
}
