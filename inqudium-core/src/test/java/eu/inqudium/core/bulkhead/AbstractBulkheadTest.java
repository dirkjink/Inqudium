package eu.inqudium.core.bulkhead;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.event.InqEvent;
import eu.inqudium.core.event.InqEventConsumer;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.event.InqSubscription;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class AbstractBulkheadTest {

  private void injectPublisher(AbstractBulkhead bulkhead, InqEventPublisher publisher) throws Exception {
    Field publisherField = AbstractBulkhead.class.getDeclaredField("eventPublisher");
    publisherField.setAccessible(true);
    publisherField.set(bulkhead, publisher);
  }

  // ── Helper methods and fake classes for testing ──

  // A simple dummy implementation to test the abstract base class
  private static class TestBulkhead extends AbstractBulkhead {
    protected TestBulkhead(String name, BulkheadConfig config) {
      super(name, config);
    }

    @Override
    protected boolean tryAcquirePermit(String callId, Duration timeout) {
      return true;
    }

    @Override
    protected void releasePermit() {
    }

    @Override
    public int getConcurrentCalls() {
      return 1;
    }

    @Override
    public int getAvailablePermits() {
      return 0;
    }
  }

  // A fake publisher that only throws during the release phase.
  // This allows the business logic to actually execute and throw its own exception first.
  private record FailingEventPublisher(RuntimeException exceptionToThrow) implements InqEventPublisher {
    @Override
    public void publish(InqEvent event) {
      if (event instanceof BulkheadOnReleaseEvent) {
        throw exceptionToThrow;
      }
    }

    @Override
    public InqSubscription onEvent(InqEventConsumer consumer) {
      return null;
    }

    @Override
    public <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer) {
      return null;
    }
  }

  @Nested
  class ExceptionHandlingLogic {

    @Test
    void an_exception_in_the_event_publisher_does_not_mask_the_original_business_exception() throws Exception {
      // Given
      // We configure a basic bulkhead instance
      BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1).build();
      TestBulkhead bulkhead = new TestBulkhead("test-bulkhead", config);

      // We use a custom fake publisher that explicitly throws an exception ONLY on release
      RuntimeException publisherException = new RuntimeException("Event publishing failed!");
      InqEventPublisher throwingPublisher = new FailingEventPublisher(publisherException);
      injectPublisher(bulkhead, throwingPublisher);

      // We use the ACTUAL InqCall record and pass a failing lambda as the callable
      RuntimeException businessException = new RuntimeException("Business logic failed!");
      InqCall<String> failingCall = new InqCall<>("call-1", () -> {
        throw businessException;
      });

      // When
      // We execute the decorated call. The business logic fails first, then the publisher fails on release.
      Throwable thrown = catchThrowable(() -> bulkhead.decorate(failingCall).callable().call());

      // Then
      // The original business exception must be the one that is actually thrown
      assertThat(thrown).isSameAs(businessException);

      // And the publisher exception must be safely attached as a suppressed exception
      assertThat(thrown.getSuppressed()).containsExactly(publisherException);
    }
  }
}