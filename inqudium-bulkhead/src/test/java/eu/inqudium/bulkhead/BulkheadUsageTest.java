package eu.inqudium.bulkhead;

import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqFailure;
import eu.inqudium.core.pipeline.InqPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/**
 * Demonstrates Bulkhead usage from a library user's perspective.
 */
@DisplayName("Bulkhead — User Perspective")
class BulkheadUsageTest {

    // ── Simulated slow service ──

    static class OrderService {
        private final AtomicInteger callCount = new AtomicInteger(0);

        String processOrder(String orderId) {
            callCount.incrementAndGet();
            return "processed-" + orderId;
        }

        String processOrderSlowly(String orderId, CountDownLatch holdLatch) {
            callCount.incrementAndGet();
            try {
                holdLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "processed-" + orderId;
        }

        int getCallCount() { return callCount.get(); }
    }

    @Nested
    @DisplayName("Standalone usage")
    class Standalone {

        @Test
        void should_let_calls_through_when_bulkhead_has_capacity() {
            // Given
            var service = new OrderService();
            var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
                    .maxConcurrentCalls(5)
                    .build());

            // When
            var result = bh.executeSupplier(() -> service.processOrder("order-1"));

            // Then
            assertThat(result).isEqualTo("processed-order-1");
            assertThat(bh.getAvailablePermits()).isEqualTo(5);
            assertThat(bh.getConcurrentCalls()).isZero();
        }

        @Test
        void should_release_permits_after_successful_calls() {
            // Given
            var service = new OrderService();
            var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
                    .maxConcurrentCalls(2)
                    .build());

            // When — two sequential calls
            bh.executeSupplier(() -> service.processOrder("order-1"));
            bh.executeSupplier(() -> service.processOrder("order-2"));

            // Then — permits are released
            assertThat(bh.getAvailablePermits()).isEqualTo(2);
            assertThat(service.getCallCount()).isEqualTo(2);
        }

        @Test
        void should_release_permits_after_failed_calls() {
            // Given
            var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
                    .maxConcurrentCalls(2)
                    .build());

            // When — call that throws
            catchThrowable(() ->
                    bh.executeSupplier(() -> { throw new RuntimeException("boom"); }));

            // Then — permit was still released
            assertThat(bh.getAvailablePermits()).isEqualTo(2);
        }

        @Test
        void should_reject_calls_when_bulkhead_is_full() throws Exception {
            // Given
            var service = new OrderService();
            var holdLatch = new CountDownLatch(1);
            var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
                    .maxConcurrentCalls(1)
                    .build());

            // When — one concurrent call holds the single permit
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(() -> bh.executeSupplier(
                    () -> service.processOrderSlowly("blocking", holdLatch)));
            Thread.sleep(50); // allow the blocking call to acquire the permit

            // Then — subsequent call is rejected
            assertThatThrownBy(() -> bh.executeSupplier(() -> service.processOrder("rejected")))
                    .isInstanceOf(InqBulkheadFullException.class)
                    .satisfies(ex -> {
                        var bhEx = (InqBulkheadFullException) ex;
                        assertThat(bhEx.getCode()).isEqualTo("INQ-BH-001");
                        assertThat(bhEx.getElementName()).isEqualTo("orderService");
                        assertThat(bhEx.getConcurrentCalls()).isEqualTo(1);
                        assertThat(bhEx.getMaxConcurrentCalls()).isEqualTo(1);
                    });

            holdLatch.countDown();
            executor.shutdown();
        }

        @Test
        void should_decorate_a_runnable_for_fire_and_forget() {
            // Given
            var executed = new AtomicInteger(0);
            var bh = Bulkhead.ofDefaults("orderService");

            // When
            Runnable resilient = bh.decorateRunnable(executed::incrementAndGet);
            resilient.run();

            // Then
            assertThat(executed).hasValue(1);
            assertThat(bh.getAvailablePermits()).isEqualTo(bh.getConfig().getMaxConcurrentCalls());
        }

        @Test
        void should_allow_catching_bulkhead_full_via_inq_failure() throws Exception {
            // Given
            var holdLatch = new CountDownLatch(1);
            var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
                    .maxConcurrentCalls(1)
                    .build());

            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(() -> bh.executeSupplier(() -> {
                try { holdLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "done";
            }));
            Thread.sleep(50);

            // When
            var handled = new AtomicInteger(0);
            try {
                bh.executeSupplier(() -> "rejected");
            } catch (RuntimeException e) {
                InqFailure.find(e)
                        .ifBulkheadFull(info -> {
                            handled.incrementAndGet();
                            assertThat(info.getMaxConcurrentCalls()).isEqualTo(1);
                        })
                        .orElseThrow();
            }

            // Then
            assertThat(handled).hasValue(1);

            holdLatch.countDown();
            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("Pipeline usage")
    class Pipeline {

        @Test
        void should_isolate_a_call_through_the_pipeline() {
            // Given
            var service = new OrderService();
            var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
                    .maxConcurrentCalls(5)
                    .build());

            Supplier<String> resilient = InqPipeline.of(() -> service.processOrder("pipeline-1"))
                    .shield(bh)
                    .decorate();

            // When
            var result = resilient.get();

            // Then
            assertThat(result).isEqualTo("processed-pipeline-1");
            assertThat(bh.getAvailablePermits()).isEqualTo(5);
        }

        @Test
        void should_carry_a_pipeline_call_id_on_bulkhead_full_exception() throws Exception {
            // Given
            var holdLatch = new CountDownLatch(1);
            var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
                    .maxConcurrentCalls(1)
                    .build());

            // Hold the single permit
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(() ->
                    InqPipeline.of(() -> {
                                try { holdLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                                return "blocking";
                            })
                            .shield(bh)
                            .decorate()
                            .get());
            Thread.sleep(50);

            Supplier<String> resilient = InqPipeline.of(() -> "rejected")
                    .shield(bh)
                    .decorate();

            // When / Then
            assertThatThrownBy(resilient::get)
                    .isInstanceOf(InqBulkheadFullException.class)
                    .satisfies(ex -> {
                        var inqEx = (InqException) ex;
                        assertThat(inqEx.getCallId())
                                .isNotNull()
                                .isNotEqualTo("None");
                    });

            holdLatch.countDown();
            executor.shutdown();
        }
    }
}
