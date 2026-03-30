package eu.inqudium.core.bulkhead;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.event.InqEvent;
import eu.inqudium.core.event.InqEventConsumer;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.event.InqSubscription;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class AbstractBulkheadTest {

  @Nested
  class ElementProperties {

    @Test
    void the_bulkhead_correctly_exposes_its_name_and_element_type() {
      // Given
      // A standard bulkhead instance
      BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(5).build();
      TestBulkhead bulkhead = new TestBulkhead("my-bulkhead", config, true);

      // When / Then
      // The properties must reflect the initialization values
      assertThat(bulkhead.getName()).isEqualTo("my-bulkhead");
      assertThat(bulkhead.getElementType()).isEqualTo(InqElementType.BULKHEAD);
      assertThat(bulkhead.getMaxConcurrentCalls()).isEqualTo(5);
      assertThat(bulkhead.getConfig()).isSameAs(config);
    }
  }

  @Nested
  class PermitAcquisitionSuccess {

    @Test
    void a_successful_call_acquires_a_permit_executes_the_logic_and_publishes_events() throws Exception {
      // Given
      // A bulkhead that always grants permits
      BulkheadConfig config = BulkheadConfig.builder().build();
      TestBulkhead bulkhead = new TestBulkhead("success-bulkhead", config, true);

      FakeEventPublisher fakePublisher = new FakeEventPublisher();
      injectPublisher(bulkhead, fakePublisher);

      InqCall<String> successfulCall = new InqCall<>("call-123", () -> "success-result");

      // When
      // We execute the decorated call
      String result = bulkhead.decorate(successfulCall).callable().call();

      // Then
      // The business logic must return the expected result
      assertThat(result).isEqualTo("success-result");

      // And exactly one acquire and one release event must have been published
      assertThat(fakePublisher.getPublishedEvents()).hasSize(2);
      assertThat(fakePublisher.getPublishedEvents().get(0)).isInstanceOf(BulkheadOnAcquireEvent.class);
      assertThat(fakePublisher.getPublishedEvents().get(1)).isInstanceOf(BulkheadOnReleaseEvent.class);

      // And the permit must have been released
      assertThat(bulkhead.getReleaseCount()).isEqualTo(1);
    }
  }

  @Nested
  class PermitAcquisitionRejection {

    @Test
    void a_rejected_call_throws_a_bulkhead_full_exception_and_publishes_a_reject_event() throws Exception {
      // Given
      // A bulkhead that always denies permits (simulating a full state)
      BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(2).build();
      TestBulkhead bulkhead = new TestBulkhead("full-bulkhead", config, false);

      FakeEventPublisher fakePublisher = new FakeEventPublisher();
      injectPublisher(bulkhead, fakePublisher);

      InqCall<String> rejectedCall = new InqCall<>("call-456", () -> "should-never-run");

      // When
      // We attempt to execute the decorated call
      Throwable thrown = catchThrowable(() -> bulkhead.decorate(rejectedCall).callable().call());

      // Then
      // It must throw an InqBulkheadFullException
      assertThat(thrown)
          .isInstanceOf(InqBulkheadFullException.class)
          .hasMessageContaining("Bulkhead 'full-bulkhead' is full");

      // And exactly one reject event must have been published
      assertThat(fakePublisher.getPublishedEvents()).hasSize(1);
      assertThat(fakePublisher.getPublishedEvents().get(0)).isInstanceOf(BulkheadOnRejectEvent.class);

      // And releasePermit must NOT have been called, because it was never acquired
      assertThat(bulkhead.getReleaseCount()).isZero();
    }
  }

  @Nested
  class ExceptionHandling {

    @Test
    void an_exception_in_the_event_publisher_does_not_mask_the_original_business_exception() throws Exception {
      // Given
      // A bulkhead and a publisher that fails specifically during the release phase
      BulkheadConfig config = BulkheadConfig.builder().build();
      TestBulkhead bulkhead = new TestBulkhead("failing-bulkhead", config, true);

      RuntimeException publisherException = new RuntimeException("Publisher failed!");
      FailingReleaseEventPublisher throwingPublisher = new FailingReleaseEventPublisher(publisherException);
      injectPublisher(bulkhead, throwingPublisher);

      // A business call that also throws an exception
      RuntimeException businessException = new RuntimeException("Business failed!");
      InqCall<String> failingCall = new InqCall<>("call-789", () -> {
        throw businessException;
      });

      // When
      // We execute the call, causing both the business logic and the release event to fail
      Throwable thrown = catchThrowable(() -> bulkhead.decorate(failingCall).callable().call());

      // Then
      // The original business exception must be the main exception
      assertThat(thrown).isSameAs(businessException);

      // And the publisher exception must be safely attached as a suppressed exception
      assertThat(thrown.getSuppressed()).containsExactly(publisherException);

      // And the permit must still be released despite the exceptions
      assertThat(bulkhead.getReleaseCount()).isEqualTo(1);
    }
  }

  // ── Helper methods and fake classes for testing ──

  private void injectPublisher(AbstractBulkhead bulkhead, InqEventPublisher publisher) throws Exception {
    Field publisherField = AbstractBulkhead.class.getDeclaredField("eventPublisher");
    publisherField.setAccessible(true);
    publisherField.set(bulkhead, publisher);
  }

  // A simple fake implementation of AbstractBulkhead to test base logic
  private static class TestBulkhead extends AbstractBulkhead {
    private final boolean grantPermit;
    private int releaseCount = 0;

    protected TestBulkhead(String name, BulkheadConfig config, boolean grantPermit) {
      super(name, config);
      this.grantPermit = grantPermit;
    }

    @Override
    protected boolean tryAcquirePermit(String callId, Duration timeout) {
      return grantPermit;
    }

    @Override
    protected void releasePermit() {
      releaseCount++;
    }

    @Override
    public int getConcurrentCalls() {
      return 1;
    }

    @Override
    public int getAvailablePermits() {
      return grantPermit ? 1 : 0;
    }

    public int getReleaseCount() {
      return releaseCount;
    }
  }

  // A fake event publisher that collects events in a list instead of mocking
  private static class FakeEventPublisher implements InqEventPublisher {
    private final List<Object> publishedEvents = new ArrayList<>();

    @Override
    public void publish(InqEvent event) {
      publishedEvents.add(event);
    }

    @Override
    public InqSubscription onEvent(InqEventConsumer consumer) {
      return null;
    }

    @Override
    public <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer) {
      return null;
    }

    public List<Object> getPublishedEvents() {
      return publishedEvents;
    }
  }

  // A fake publisher that only throws during the release event
  private record FailingReleaseEventPublisher(RuntimeException exceptionToThrow) implements InqEventPublisher {
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
}