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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class AbstractBulkheadTest {

  // A simple fake implementation of AbstractBulkhead to test base logic
  private static class TestBulkhead extends AbstractBulkhead {
    private final boolean grantPermit;
    private int releaseCount = 0;

    protected TestBulkhead(String name, BulkheadConfig config, boolean grantPermit) {
      super(name, config);
      this.grantPermit = grantPermit;
    }

    protected TestBulkhead(String name, BulkheadConfig config, boolean grantPermit, InqEventPublisher eventPublisher) {
      super(name, config, eventPublisher);
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

  // A fake publisher that only throws during the acquire event
  private record FailingAcquireEventPublisher(RuntimeException exceptionToThrow) implements InqEventPublisher {
    @Override
    public void publish(InqEvent event) {
      if (event instanceof BulkheadOnAcquireEvent) {
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
      FakeEventPublisher fakePublisher = new FakeEventPublisher();
      TestBulkhead bulkhead = new TestBulkhead("success-bulkhead", config, true, fakePublisher);

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
      FakeEventPublisher fakePublisher = new FakeEventPublisher();
      TestBulkhead bulkhead = new TestBulkhead("full-bulkhead", config, false, fakePublisher);

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
    void an_exception_during_the_acquire_event_prevents_business_logic_execution_but_still_releases_the_permit() throws Exception {
      // Given
      // A bulkhead and a publisher that fails immediately upon acquiring
      BulkheadConfig config = BulkheadConfig.builder().build();
      RuntimeException acquireException = new RuntimeException("Publisher failed on acquire!");
      FailingAcquireEventPublisher throwingPublisher = new FailingAcquireEventPublisher(acquireException);
      TestBulkhead bulkhead = new TestBulkhead("acquire-fail-bulkhead", config, true, throwingPublisher);

      // A business call that tracks if it was executed
      boolean[] businessLogicExecuted = {false};
      InqCall<String> testCall = new InqCall<>("call-999", () -> {
        businessLogicExecuted[0] = true;
        return "should-not-reach-this";
      });

      // When
      // We execute the call. The publisher crashes before the business logic starts.
      Throwable thrown = catchThrowable(() -> bulkhead.decorate(testCall).callable().call());

      // Then
      // The exception thrown must be the publisher's exception
      assertThat(thrown).isSameAs(acquireException);

      // The business logic MUST NOT have been executed
      assertThat(businessLogicExecuted[0]).isFalse();

      // But the permit MUST still be released by the finally block
      assertThat(bulkhead.getReleaseCount()).isEqualTo(1);
    }

    @Test
    void an_exception_during_the_release_event_is_thrown_directly_if_the_business_logic_succeeds() throws Exception {
      // Given
      // A bulkhead and a publisher that fails ONLY on release
      BulkheadConfig config = BulkheadConfig.builder().build();
      RuntimeException releaseException = new RuntimeException("Publisher failed on release!");
      FailingReleaseEventPublisher throwingPublisher = new FailingReleaseEventPublisher(releaseException);
      TestBulkhead bulkhead = new TestBulkhead("release-fail-bulkhead", config, true, throwingPublisher);

      // A business call that completes successfully without exceptions
      InqCall<String> successfulCall = new InqCall<>("call-888", () -> "successful-result");

      // When
      // We execute the call. The business logic works, but releasing fails.
      Throwable thrown = catchThrowable(() -> bulkhead.decorate(successfulCall).callable().call());

      // Then
      // The exception thrown must be the publisher's exception, as there is no business error to attach to
      assertThat(thrown).isSameAs(releaseException);

      // And the permit must have been released
      assertThat(bulkhead.getReleaseCount()).isEqualTo(1);
    }

    @Test
    void an_exception_in_the_event_publisher_does_not_mask_the_original_business_exception() throws Exception {
      // Given
      // A bulkhead and a publisher that fails specifically during the release phase
      BulkheadConfig config = BulkheadConfig.builder().build();
      RuntimeException publisherException = new RuntimeException("Publisher failed!");
      FailingReleaseEventPublisher throwingPublisher = new FailingReleaseEventPublisher(publisherException);
      TestBulkhead bulkhead = new TestBulkhead("failing-bulkhead", config, true, throwingPublisher);

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
}