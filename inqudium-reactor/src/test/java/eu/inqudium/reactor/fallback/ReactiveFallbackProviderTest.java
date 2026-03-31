package fallback.reactive;

import fallback.core.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReactiveFallbackProvider")
class ReactiveFallbackProviderTest {

  // ================================================================
  // Mono — Primary Success
  // ================================================================

  @Nested
  @DisplayName("Mono — Primary Success")
  class MonoPrimarySuccess {

    @Test
    @DisplayName("should emit the upstream value when no fallback is needed")
    void should_emit_the_upstream_value_when_no_fallback_is_needed() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("test").withDefault("fb").build());

      // When
      Mono<String> result = provider.execute(Mono.just("primary"));

      // Then
      StepVerifier.create(result)
          .expectNext("primary")
          .verifyComplete();
    }

    @Test
    @DisplayName("should support lazy mono creation via supplier")
    void should_support_lazy_mono_creation_via_supplier() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("test").withDefault("fb").build());

      // When
      Mono<String> result = provider.execute(() -> Mono.just("lazy"));

      // Then
      StepVerifier.create(result)
          .expectNext("lazy")
          .verifyComplete();
    }
  }

  // ================================================================
  // Mono — Exception-Type Routing
  // ================================================================

  @Nested
  @DisplayName("Mono — Exception-Type Routing")
  class MonoExceptionRouting {

    @Test
    @DisplayName("should route to the correct handler based on the error signal type")
    void should_route_to_the_correct_handler_based_on_the_error_signal_type() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("router")
              .onException(IOException.class, _ -> "io-fallback")
              .onException(TimeoutException.class, _ -> "timeout-fallback")
              .build());

      // When
      Mono<String> result = provider.execute(Mono.error(new IOException("conn refused")));

      // Then
      StepVerifier.create(result)
          .expectNext("io-fallback")
          .verifyComplete();
    }

    @Test
    @DisplayName("should route to the timeout handler for timeout errors")
    void should_route_to_the_timeout_handler_for_timeout_errors() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("router")
              .onException(IOException.class, _ -> "io-fallback")
              .onException(TimeoutException.class, _ -> "timeout-fallback")
              .build());

      // When
      Mono<String> result = provider.execute(Mono.error(new TimeoutException("timed out")));

      // Then
      StepVerifier.create(result)
          .expectNext("timeout-fallback")
          .verifyComplete();
    }

    @Test
    @DisplayName("should route to the catch-all handler when no specific handler matches")
    void should_route_to_the_catch_all_handler_when_no_specific_handler_matches() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("router")
              .onException(IOException.class, _ -> "io-fallback")
              .onAnyException(_ -> "catch-all")
              .build());

      // When
      Mono<String> result = provider.execute(
          Mono.error(new IllegalStateException("unexpected")));

      // Then
      StepVerifier.create(result)
          .expectNext("catch-all")
          .verifyComplete();
    }

    @Test
    @DisplayName("should propagate the original error when no handler matches")
    void should_propagate_the_original_error_when_no_handler_matches() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("unhandled")
              .onException(IOException.class, _ -> "io-fallback")
              .build());

      // When
      Mono<String> result = provider.execute(
          Mono.error(new IllegalStateException("not handled")));

      // Then
      StepVerifier.create(result)
          .expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(IllegalStateException.class)
                .hasMessage("not handled");
          })
          .verify();
    }

    @Test
    @DisplayName("should provide the exception to the handler function")
    void should_provide_the_exception_to_the_handler_function() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("msg")
              .onException(IOException.class, IOException::getMessage)
              .build());

      // When
      Mono<String> result = provider.execute(Mono.error(new IOException("disk full")));

      // Then
      StepVerifier.create(result)
          .expectNext("disk full")
          .verifyComplete();
    }
  }

  // ================================================================
  // Mono — Constant Value Fallback
  // ================================================================

  @Nested
  @DisplayName("Mono — Constant Value Fallback")
  class MonoConstantValue {

    @Test
    @DisplayName("should return the constant value for any error")
    void should_return_the_constant_value_for_any_error() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("const").withDefault("default-value").build());

      // When
      Mono<String> result = provider.execute(Mono.error(new RuntimeException("any")));

      // Then
      StepVerifier.create(result)
          .expectNext("default-value")
          .verifyComplete();
    }
  }

  // ================================================================
  // Mono — Fallback Handler Failure
  // ================================================================

  @Nested
  @DisplayName("Mono — Fallback Handler Failure")
  class MonoHandlerFailure {

    @Test
    @DisplayName("should emit FallbackException when the handler itself throws")
    void should_emit_fallback_exception_when_the_handler_itself_throws() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("failing-handler")
              .onAnyException(_ -> {
                throw new RuntimeException("handler crash");
              })
              .build());

      // When
      Mono<String> result = provider.execute(Mono.error(new RuntimeException("primary")));

      // Then
      StepVerifier.create(result)
          .expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(FallbackException.class);
            FallbackException fe = (FallbackException) error;
            assertThat(fe.getReason()).isEqualTo(FallbackException.Reason.FALLBACK_FAILED);
          })
          .verify();
    }
  }

  // ================================================================
  // Mono — Result-Based Fallback
  // ================================================================

  @Nested
  @DisplayName("Mono — Result-Based Fallback")
  class MonoResultFallback {

    @Test
    @DisplayName("should replace a null result with the fallback value")
    void should_replace_a_null_result_with_the_fallback_value() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("null-check")
              .onResult(result -> result == null, () -> "default-value")
              .onAnyException(_ -> "error-fallback")
              .build());

      // When — Mono.justOrEmpty(null) emits empty, but we use Mono.just with a trick
      // Actually, we need the Mono to emit a null-like value. Use flatMap.
      Mono<String> result = provider.execute(Mono.fromSupplier(() -> (String) null));

      // Then
      StepVerifier.create(result)
          .expectNext("default-value")
          .verifyComplete();
    }

    @Test
    @DisplayName("should not invoke the result handler when the result is acceptable")
    void should_not_invoke_the_result_handler_when_the_result_is_acceptable() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("acceptable")
              .onResult(result -> result == null, () -> "default")
              .onAnyException(_ -> "error")
              .build());

      // When
      Mono<String> result = provider.execute(Mono.just("valid"));

      // Then
      StepVerifier.create(result)
          .expectNext("valid")
          .verifyComplete();
    }
  }

  // ================================================================
  // Flux Execution
  // ================================================================

  @Nested
  @DisplayName("Flux Execution")
  class FluxExecution {

    @Test
    @DisplayName("should emit all upstream elements when no fallback is needed")
    void should_emit_all_upstream_elements_when_no_fallback_is_needed() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("flux").withDefault("fb").build());

      // When
      Flux<String> result = provider.executeMany(Flux.just("a", "b", "c"));

      // Then
      StepVerifier.create(result)
          .expectNext("a", "b", "c")
          .verifyComplete();
    }

    @Test
    @DisplayName("should recover from a flux error with the fallback value")
    void should_recover_from_a_flux_error_with_the_fallback_value() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("flux-error")
              .onAnyException(_ -> "recovered")
              .build());

      // When
      Flux<String> result = provider.executeMany(
          Flux.error(new RuntimeException("flux fail")));

      // Then
      StepVerifier.create(result)
          .expectNext("recovered")
          .verifyComplete();
    }

    @Test
    @DisplayName("should emit partial elements then recover on mid-stream error")
    void should_emit_partial_elements_then_recover_on_mid_stream_error() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("mid-stream")
              .onAnyException(_ -> "recovered")
              .build());

      // When
      Flux<String> result = provider.executeMany(
          Flux.concat(
              Flux.just("a", "b"),
              Flux.error(new RuntimeException("mid-stream error"))
          ));

      // Then
      StepVerifier.create(result)
          .expectNext("a", "b", "recovered")
          .verifyComplete();
    }

    @Test
    @DisplayName("should propagate the error when no handler matches for flux")
    void should_propagate_the_error_when_no_handler_matches_for_flux() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("flux-unhandled")
              .onException(IOException.class, _ -> "io-fallback")
              .build());

      // When
      Flux<String> result = provider.executeMany(
          Flux.error(new IllegalStateException("not handled")));

      // Then
      StepVerifier.create(result)
          .expectError(IllegalStateException.class)
          .verify();
    }
  }

  // ================================================================
  // Operator Style (transformDeferred)
  // ================================================================

  @Nested
  @DisplayName("Operator Style Integration")
  class OperatorStyle {

    @Test
    @DisplayName("should work as a mono operator via transformDeferred")
    void should_work_as_a_mono_operator_via_transform_deferred() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("operator")
              .onAnyException(_ -> "recovered")
              .build());

      // When — error mono through the operator
      Mono<String> result = Mono.<String>error(new RuntimeException("fail"))
          .transformDeferred(provider.monoOperator());

      // Then
      StepVerifier.create(result)
          .expectNext("recovered")
          .verifyComplete();
    }

    @Test
    @DisplayName("should work as a flux operator via transformDeferred")
    void should_work_as_a_flux_operator_via_transform_deferred() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("operator").withDefault("fb").build());

      // When
      Flux<String> result = Flux.just("a", "b")
          .transformDeferred(provider.fluxOperator());

      // Then
      StepVerifier.create(result)
          .expectNext("a", "b")
          .verifyComplete();
    }
  }

  // ================================================================
  // Event Stream
  // ================================================================

  @Nested
  @DisplayName("Event Stream")
  class EventStream {

    @Test
    @DisplayName("should emit events for a successful primary execution")
    void should_emit_events_for_a_successful_primary_execution() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("events").withDefault("fb").build());
      List<FallbackEvent> events = new ArrayList<>();
      provider.events().subscribe(events::add);

      // When
      provider.execute(Mono.just("ok")).block();

      // Then
      assertThat(events).extracting(FallbackEvent::type).containsExactly(
          FallbackEvent.Type.PRIMARY_STARTED,
          FallbackEvent.Type.PRIMARY_SUCCEEDED
      );
    }

    @Test
    @DisplayName("should emit the full event sequence for an exception recovery")
    void should_emit_the_full_event_sequence_for_an_exception_recovery() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("events")
              .onAnyException("my-handler", _ -> "recovered")
              .build());
      List<FallbackEvent> events = new ArrayList<>();
      provider.events().subscribe(events::add);

      // When
      provider.execute(Mono.error(new RuntimeException("fail"))).block();

      // Then
      assertThat(events).extracting(FallbackEvent::type).containsExactly(
          FallbackEvent.Type.PRIMARY_STARTED,
          FallbackEvent.Type.PRIMARY_FAILED,
          FallbackEvent.Type.FALLBACK_INVOKED,
          FallbackEvent.Type.FALLBACK_RECOVERED
      );
      assertThat(events.get(2).handlerName()).isEqualTo("my-handler");
    }

    @Test
    @DisplayName("should emit NO_HANDLER_MATCHED when no handler is found")
    void should_emit_no_handler_matched_when_no_handler_is_found() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("events")
              .onException(IOException.class, _ -> "io")
              .build());
      List<FallbackEvent> events = new ArrayList<>();
      provider.events().subscribe(events::add);

      // When
      provider.execute(Mono.error(new IllegalStateException("no match")))
          .onErrorResume(_ -> Mono.empty())
          .block();

      // Then
      assertThat(events).extracting(FallbackEvent::type).containsExactly(
          FallbackEvent.Type.PRIMARY_STARTED,
          FallbackEvent.Type.PRIMARY_FAILED,
          FallbackEvent.Type.NO_HANDLER_MATCHED
      );
    }
  }

  // ================================================================
  // Deferred Subscription Semantics
  // ================================================================

  @Nested
  @DisplayName("Deferred Subscription Semantics")
  class DeferredSubscription {

    @Test
    @DisplayName("should not execute until the mono is subscribed")
    void should_not_execute_until_the_mono_is_subscribed() {
      // Given
      var provider = new ReactiveFallbackProvider<>(
          FallbackConfig.<String>builder("deferred").withDefault("fb").build());
      List<FallbackEvent> events = new ArrayList<>();
      provider.events().subscribe(events::add);

      // When — create but don't subscribe
      Mono<String> result = provider.execute(Mono.just("deferred"));

      // Then — no events yet
      assertThat(events).isEmpty();

      // When — subscribe
      StepVerifier.create(result)
          .expectNext("deferred")
          .verifyComplete();
      assertThat(events).isNotEmpty();
    }
  }

  // ================================================================
  // Introspection
  // ================================================================

  @Nested
  @DisplayName("Introspection")
  class Introspection {

    @Test
    @DisplayName("should return the configuration")
    void should_return_the_configuration() {
      // Given
      var config = FallbackConfig.<String>builder("inspect").withDefault("fb").build();
      var provider = new ReactiveFallbackProvider<>(config);

      // Then
      assertThat(provider.getConfig()).isEqualTo(config);
      assertThat(provider.getConfig().name()).isEqualTo("inspect");
    }
  }
}
