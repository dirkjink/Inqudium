package eu.inqudium.bulkhead;

import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqFailure;
import eu.inqudium.core.pipeline.InqPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/**
 * Demonstrates Bulkhead usage from a library user's perspective.
 *
 * <p>All standalone tests follow the real-world pattern: decorate once, then
 * invoke the wrapper. The bulkhead limits concurrent access to the decorated call.
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
            // Given — decorate once, reuse the wrapper
            var service = new OrderService();
            var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
                    .maxConcurrentCalls(5)
                    .build());
            Supplier<String> resilientProcess = bh.decorateSupplier(() -> service.processOrder("order-1"));

            // When
            var result = resilientProcess.get();

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
            Supplier<String> resilientProcess = bh.decorateSupplier(() -> service.processOrder("order"));

            // When — two sequential calls through the same wrapper
            resilientProcess.get();
            resilientProcess.get();

            // Then — permits are released after each call
            assertThat(bh.getAvailablePermits()).isEqualTo(2);
            assertThat(service.getCallCount()).isEqualTo(2);
        }

        @Test
        void should_release_permits_after_failed_calls() {
            // Given
            var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
                    .maxConcurrentCalls(2)
                    .build());
            Supplier<String> resilient = bh.decorateSupplier(() -> {
                throw new RuntimeException("boom");
            });

            // When — call that throws
            catchThrowable(resilient::get);

            // Then — permit was still released
            assertThat(bh.getAvailablePermits()).isEqualTo(2);
        }

        @Test
        void should_reject_calls_when_bulkhead_is_full() throws Exception {
            // Given — decorate two suppliers: one slow (to hold the permit), one fast
            var service = new OrderService();
            var holdLatch = new CountDownLatch(1);
            var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
                    .maxConcurrentCalls(1)
                    .build());
            Supplier<String> slowCall = bh.decorateSupplier(
                    () -> service.processOrderSlowly("blocking", holdLatch));
            Supplier<String> fastCall = bh.decorateSupplier(
                    () -> service.processOrder("rejected"));

            // When — slow call holds the single permit
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(slowCall::get);
            Thread.sleep(50);

            // Then — fast call is rejected
            assertThatThrownBy(fastCall::get)
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
            Runnable resilientTask = bh.decorateRunnable(executed::incrementAndGet);

            // When
            resilientTask.run();

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
            Supplier<String> slowCall = bh.decorateSupplier(() -> {
                try { holdLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "done";
            });
            Supplier<String> nextCall = bh.decorateSupplier(() -> "rejected");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(slowCall::get);
            Thread.sleep(50);

            // When
            var handled = new AtomicInteger(0);
            try {
                nextCall.get();
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

            // Hold the single permit via pipeline
            Supplier<String> blocking = InqPipeline.of(() -> {
                        try { holdLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return "blocking";
                    })
                    .shield(bh)
                    .decorate();

            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(blocking::get);
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
