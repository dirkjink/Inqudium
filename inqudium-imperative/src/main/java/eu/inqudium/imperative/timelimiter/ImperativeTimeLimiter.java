package eu.inqudium.imperative.timelimiter;

import eu.inqudium.core.timelimiter.ExecutionSnapshot;
import eu.inqudium.core.timelimiter.TimeLimiterConfig;
import eu.inqudium.core.timelimiter.TimeLimiterCore;
import eu.inqudium.core.timelimiter.TimeLimiterEvent;
import eu.inqudium.core.timelimiter.TimeLimiterException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe time limiter with both blocking and non-blocking execution modes.
 *
 * <h2>Blocking API ({@code execute*})</h2>
 * <p>The caller thread blocks until the operation completes, fails, or times out.
 * A virtual thread runs the callable; the caller waits via {@code Future.get(timeout)}.
 *
 * <h2>Non-blocking API ({@code execute*Async})</h2>
 * <p>Returns a {@link CompletableFuture} immediately. The caller can compose
 * further pipeline stages without blocking. Timeout, event emission, and
 * cancellation are handled inside the returned future's completion callbacks.
 *
 * <ul>
 *   <li>{@link #executeAsync(Callable)} — spawns a virtual thread, returns CF</li>
 *   <li>{@link #executeFutureAsync(Supplier)} — bridges an external {@link Future}
 *       to a CF via a lightweight virtual thread (necessary because {@code Future}
 *       has no non-blocking completion API)</li>
 *   <li>{@link #executeCompletionStageAsync(Supplier)} — attaches timeout handling
 *       directly to the existing {@link CompletableFuture} pipeline using
 *       {@link CompletableFuture#orTimeout}; no additional thread is spawned</li>
 * </ul>
 *
 * <p><strong>Cancellation semantics:</strong> On timeout with {@code cancelOnTimeout=true}:
 * <ul>
 *   <li>Callable / FutureTask: {@code Thread.interrupt()} — true interruption</li>
 *   <li>External Future: {@code future.cancel(true)} — may interrupt depending on impl</li>
 *   <li>CompletionStage: {@code cf.cancel(true)} — does <em>not</em> interrupt the underlying
 *       computation (see {@link CompletableFuture#cancel} Javadoc)</li>
 * </ul>
 */
public class ImperativeTimeLimiter {

  private static final Logger LOG = Logger.getLogger(ImperativeTimeLimiter.class.getName());

  private final TimeLimiterConfig config;
  private final Clock clock;
  private final List<Consumer<TimeLimiterEvent>> eventListeners;
  private final String instanceId;
  private final AtomicLong threadCounter = new AtomicLong(0);

  public ImperativeTimeLimiter(TimeLimiterConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeTimeLimiter(TimeLimiterConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.eventListeners = new CopyOnWriteArrayList<>();
    this.instanceId = UUID.randomUUID().toString();
  }

  // ================================================================================
  // Blocking API — Callable
  // ================================================================================

  private static Throwable unwrapCompletionException(Throwable ex) {
    return (ex instanceof CompletionException && ex.getCause() != null)
        ? ex.getCause() : ex;
  }

  private static <T> void validateCallable(Callable<T> callable) {
    Objects.requireNonNull(callable, "callable must not be null");
  }

  private static void validateTimeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive, got " + timeout);
    }
  }

  public <T> T execute(Callable<T> callable) throws Exception {
    return execute(callable, config.timeout());
  }

  // ================================================================================
  // Blocking API — External Future
  // ================================================================================

  public <T> T execute(Callable<T> callable, Duration timeout) throws Exception {
    validateCallable(callable);
    validateTimeout(timeout);

    Instant now = clock.instant();
    ExecutionSnapshot snapshot = TimeLimiterCore.start(config, timeout, now);
    emitEvent(TimeLimiterEvent.started(config.name(), timeout, now));

    FutureTask<T> task = new FutureTask<>(callable);
    Thread.ofVirtual().name(nextThreadName()).start(task);

    try {
      return awaitFuture(task, snapshot, timeout);
    } catch (Throwable t) {
      cancelTaskSafely(task);
      throw t;
    }
  }

  public void execute(Runnable runnable) {
    Objects.requireNonNull(runnable, "runnable must not be null");
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ================================================================================
  // Blocking API — CompletionStage
  // ================================================================================

  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (TimeLimiterException e) {
      if (isOwnException(e)) return fallback.get();
      throw e;
    }
  }

  // ================================================================================
  // Non-blocking API — Callable
  // ================================================================================

  public <T> T executeFuture(Supplier<Future<T>> futureSupplier) throws Exception {
    return executeFuture(futureSupplier, config.timeout());
  }

  public <T> T executeFuture(Supplier<Future<T>> futureSupplier, Duration timeout) throws Exception {
    Objects.requireNonNull(futureSupplier, "futureSupplier must not be null");
    validateTimeout(timeout);

    Instant now = clock.instant();
    ExecutionSnapshot snapshot = TimeLimiterCore.start(config, timeout, now);
    emitEvent(TimeLimiterEvent.started(config.name(), timeout, now));

    Future<T> future = futureSupplier.get();
    try {
      return awaitFuture(future, snapshot, timeout);
    } catch (Throwable t) {
      cancelTaskSafely(future);
      throw t;
    }
  }

  public <T> T executeCompletionStage(Supplier<CompletionStage<T>> stageSupplier) throws Exception {
    return executeFuture(() -> stageSupplier.get().toCompletableFuture());
  }

  /**
   * Executes the callable asynchronously with timeout protection.
   *
   * <p>Spawns a virtual thread to run the callable and returns a
   * {@link CompletableFuture} that completes when either:
   * <ul>
   *   <li>The callable returns a result — future completes normally</li>
   *   <li>The callable throws — future completes exceptionally with the cause</li>
   *   <li>The timeout expires — future completes exceptionally with
   *       {@link TimeLimiterException}; the virtual thread is interrupted</li>
   * </ul>
   *
   * @param callable the operation to execute
   * @return a future that completes with the result or a timeout/failure exception
   */
  public <T> CompletableFuture<T> executeAsync(Callable<T> callable) {
    return executeAsync(callable, config.timeout());
  }

  // ================================================================================
  // Non-blocking API — External Future
  // ================================================================================

  public <T> CompletableFuture<T> executeAsync(Callable<T> callable, Duration timeout) {
    validateCallable(callable);
    validateTimeout(timeout);

    Instant now = clock.instant();
    ExecutionSnapshot snapshot = TimeLimiterCore.start(config, timeout, now);
    emitEvent(TimeLimiterEvent.started(config.name(), timeout, now));

    CompletableFuture<T> cf = new CompletableFuture<>();
    Thread vThread = Thread.ofVirtual().name(nextThreadName()).start(() -> {
      try {
        cf.complete(callable.call());
      } catch (Throwable t) {
        cf.completeExceptionally(t);
      }
    });

    // On timeout: interrupt the virtual thread for true cancellation
    return attachTimeoutAndEvents(cf, snapshot, timeout, () -> vThread.interrupt());
  }

  /**
   * Async execution of a {@link Runnable} with timeout protection.
   *
   * @param runnable the operation to execute
   * @return a future that completes with {@code null} or a timeout/failure exception
   */
  public CompletableFuture<Void> executeAsync(Runnable runnable) {
    return executeAsync(runnable, config.timeout());
  }

  // ================================================================================
  // Non-blocking API — CompletionStage
  // ================================================================================

  public CompletableFuture<Void> executeAsync(Runnable runnable, Duration timeout) {
    Objects.requireNonNull(runnable, "runnable must not be null");
    return executeAsync(() -> {
      runnable.run();
      return null;
    }, timeout);
  }

  /**
   * Bridges an external {@link Future} into a timeout-protected {@link CompletableFuture}.
   *
   * <p>Since {@code Future} has no non-blocking completion API, a lightweight
   * virtual thread is spawned to block on {@code future.get()} and bridge the
   * result into the returned {@code CompletableFuture}. The timeout is enforced
   * via {@link CompletableFuture#orTimeout} on the bridge future — no second
   * blocking wait is involved.
   *
   * <p>On timeout, {@code future.cancel(true)} is called on the original future,
   * which may interrupt the underlying operation depending on the {@code Future}
   * implementation.
   *
   * @param futureSupplier supplier for the already-running future
   * @return a future that completes with the result or a timeout/failure exception
   */
  public <T> CompletableFuture<T> executeFutureAsync(Supplier<Future<T>> futureSupplier) {
    return executeFutureAsync(futureSupplier, config.timeout());
  }

  // ================================================================================
  // Shared async infrastructure
  // ================================================================================

  public <T> CompletableFuture<T> executeFutureAsync(
      Supplier<Future<T>> futureSupplier, Duration timeout) {
    Objects.requireNonNull(futureSupplier, "futureSupplier must not be null");
    validateTimeout(timeout);

    Instant now = clock.instant();
    ExecutionSnapshot snapshot = TimeLimiterCore.start(config, timeout, now);
    emitEvent(TimeLimiterEvent.started(config.name(), timeout, now));

    Future<T> future = futureSupplier.get();

    // Bridge Future -> CompletableFuture via virtual thread.
    // The virtual thread blocks on future.get() (cheap on virtual threads)
    // while the CF gets the timeout attached non-blockingly via orTimeout.
    CompletableFuture<T> cf = new CompletableFuture<>();
    Thread.ofVirtual().name(nextBridgeThreadName()).start(() -> {
      try {
        cf.complete(future.get());
      } catch (ExecutionException e) {
        cf.completeExceptionally(e.getCause() != null ? e.getCause() : e);
      } catch (InterruptedException e) {
        // Bridge thread was interrupted (typically by orTimeout cancellation).
        // The CF is already completed with TimeoutException by orTimeout —
        // do not overwrite it. Just restore the interrupt flag.
        Thread.currentThread().interrupt();
      } catch (Throwable t) {
        cf.completeExceptionally(t);
      }
    });

    // On timeout: cancel the original Future (may interrupt the real operation)
    return attachTimeoutAndEvents(cf, snapshot, timeout, () -> future.cancel(true));
  }

  // ================================================================================
  // Blocking internals — Future awaiting
  // ================================================================================

  /**
   * Attaches timeout protection directly to the existing {@link CompletionStage} pipeline.
   *
   * <p>No additional thread is spawned. The timeout is enforced via
   * {@link CompletableFuture#orTimeout}, which uses the JDK's internal
   * {@code ScheduledThreadPoolExecutor} to schedule the deadline check.
   * Event emission and exception transformation are added as dependent
   * pipeline stages via {@link CompletableFuture#handle}.
   *
   * <p><strong>Cancellation caveat:</strong> On timeout, {@code cf.cancel(true)}
   * is called, but {@link CompletableFuture#cancel} does not interrupt the
   * underlying computation. The asynchronous operation may continue in the
   * background. For cooperative cancellation, the supplier should inspect
   * the future's state or use a shared cancellation signal.
   *
   * @param stageSupplier supplier for the already-running completion stage
   * @return the pipeline with timeout, events, and exception transformation attached
   */
  public <T> CompletableFuture<T> executeCompletionStageAsync(
      Supplier<CompletionStage<T>> stageSupplier) {
    return executeCompletionStageAsync(stageSupplier, config.timeout());
  }

  // ================================================================================
  // Shared internals
  // ================================================================================

  public <T> CompletableFuture<T> executeCompletionStageAsync(
      Supplier<CompletionStage<T>> stageSupplier, Duration timeout) {
    Objects.requireNonNull(stageSupplier, "stageSupplier must not be null");
    validateTimeout(timeout);

    Instant now = clock.instant();
    ExecutionSnapshot snapshot = TimeLimiterCore.start(config, timeout, now);
    emitEvent(TimeLimiterEvent.started(config.name(), timeout, now));

    CompletableFuture<T> cf = stageSupplier.get().toCompletableFuture();

    // Attach directly to the existing pipeline — no new thread spawned.
    // On timeout: cf.cancel(true) is called, but does NOT interrupt (documented caveat).
    return attachTimeoutAndEvents(cf, snapshot, timeout, () -> cf.cancel(true));
  }

  /**
   * Attaches timeout enforcement, event emission, and exception transformation
   * to a {@link CompletableFuture}.
   *
   * <p>This is the shared backbone for all three async execution modes:
   * <ol>
   *   <li>{@link CompletableFuture#orTimeout} — schedules a deadline; if the CF
   *       is not completed in time, it completes exceptionally with
   *       {@link TimeoutException}</li>
   *   <li>{@link CompletableFuture#handle} — intercepts every completion
   *       (success, failure, timeout) to emit events and transform the
   *       {@code TimeoutException} into a {@link TimeLimiterException}</li>
   *   <li>The {@code onTimeoutCancel} callback — invoked on timeout when
   *       {@code cancelOnTimeout} is enabled; specific to each execution mode</li>
   * </ol>
   *
   * @param cf              the future to protect
   * @param snapshot        the execution snapshot (RUNNING state)
   * @param timeout         the effective timeout duration
   * @param onTimeoutCancel cancellation action specific to the execution mode
   * @return a new dependent future with timeout and event handling attached
   */
  private <T> CompletableFuture<T> attachTimeoutAndEvents(
      CompletableFuture<T> cf,
      ExecutionSnapshot snapshot,
      Duration timeout,
      Runnable onTimeoutCancel) {

    // Schedule the deadline on the CF (modifies in place, returns same reference)
    cf.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);

    // Intercept completion to emit events and transform exceptions
    return cf.handle((result, ex) -> {
      Instant eventTime = clock.instant();

      // Happy path: completed within the deadline
      if (ex == null) {
        ExecutionSnapshot completed = TimeLimiterCore.recordSuccess(snapshot, eventTime);
        emitEvent(TimeLimiterEvent.completed(
            config.name(), completed.elapsed(eventTime), timeout, eventTime));
        return result;
      }

      Throwable cause = unwrapCompletionException(ex);

      // Timeout path: orTimeout completed the CF with TimeoutException
      if (cause instanceof TimeoutException) {
        ExecutionSnapshot timedOut = TimeLimiterCore.recordTimeout(snapshot, eventTime);
        emitEvent(TimeLimiterEvent.timedOut(
            config.name(), timedOut.elapsed(eventTime), timeout, eventTime));

        if (config.cancelOnTimeout()) {
          onTimeoutCancel.run();
          Instant cancelledAt = clock.instant();
          ExecutionSnapshot cancelled = TimeLimiterCore.recordCancellation(timedOut, cancelledAt);
          emitEvent(TimeLimiterEvent.cancelled(
              config.name(), cancelled.elapsed(cancelledAt), timeout, cancelledAt));
        }

        throw new CompletionException(createTimeoutExceptionWithIdentity(timeout));
      }

      // Failure path: operation threw within the deadline
      ExecutionSnapshot failed = TimeLimiterCore.recordFailure(snapshot, cause, eventTime);
      emitEvent(TimeLimiterEvent.failed(
          config.name(), failed.elapsed(eventTime), timeout, eventTime));
      throw new CompletionException(cause);
    });
  }

  private <T> T awaitFuture(
      Future<T> future,
      ExecutionSnapshot snapshot,
      Duration timeout) throws Exception {

    try {
      T result = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);

      Instant completedAt = clock.instant();
      ExecutionSnapshot completed = TimeLimiterCore.recordSuccess(snapshot, completedAt);
      emitEvent(TimeLimiterEvent.completed(
          config.name(), completed.elapsed(completedAt), timeout, completedAt));
      return result;

    } catch (TimeoutException e) {
      Instant timedOutAt = clock.instant();
      ExecutionSnapshot timedOut = TimeLimiterCore.recordTimeout(snapshot, timedOutAt);
      emitEvent(TimeLimiterEvent.timedOut(
          config.name(), timedOut.elapsed(timedOutAt), timeout, timedOutAt));

      if (config.cancelOnTimeout()) {
        boolean cancelled = future.cancel(true);
        if (cancelled) {
          Instant cancelledAt = clock.instant();
          ExecutionSnapshot cs = TimeLimiterCore.recordCancellation(timedOut, cancelledAt);
          emitEvent(TimeLimiterEvent.cancelled(
              config.name(), cs.elapsed(cancelledAt), timeout, cancelledAt));
        }
      }

      throw createTimeoutExceptionWithIdentity(timeout);

    } catch (ExecutionException e) {
      Instant failedAt = clock.instant();
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      ExecutionSnapshot failed = TimeLimiterCore.recordFailure(snapshot, cause, failedAt);
      emitEvent(TimeLimiterEvent.failed(
          config.name(), failed.elapsed(failedAt), timeout, failedAt));

      if (cause instanceof Exception ex) throw ex;
      if (cause instanceof Error err) throw err;
      throw new RuntimeException(cause);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Instant failedAt = clock.instant();
      ExecutionSnapshot failed = TimeLimiterCore.recordFailure(snapshot, e, failedAt);
      emitEvent(TimeLimiterEvent.failed(
          config.name(), failed.elapsed(failedAt), timeout, failedAt));
      cancelTaskSafely(future);
      throw e;
    }
  }

  private RuntimeException createTimeoutExceptionWithIdentity(Duration effectiveTimeout) {
    RuntimeException exception = config.createTimeoutException(effectiveTimeout);
    if (exception instanceof TimeLimiterException tle) {
      return tle.withInstanceId(instanceId);
    }
    return exception;
  }

  private void cancelTaskSafely(Future<?> future) {
    try {
      future.cancel(true);
    } catch (Throwable t) {
      LOG.log(Level.WARNING,
          "Failed to cancel task for time limiter '%s': %s"
              .formatted(config.name(), t.getMessage()), t);
    }
  }

  private boolean isOwnException(TimeLimiterException e) {
    if (e.getInstanceId() != null) {
      return Objects.equals(e.getInstanceId(), this.instanceId);
    }
    return Objects.equals(e.getTimeLimiterName(), config.name());
  }

  private String nextThreadName() {
    return "timelimiter-%s-%d".formatted(config.name(), threadCounter.incrementAndGet());
  }

  private String nextBridgeThreadName() {
    return "timelimiter-%s-bridge-%d".formatted(config.name(), threadCounter.incrementAndGet());
  }

  // ================================================================================
  // Listeners & Introspection
  // ================================================================================

  public Runnable onEvent(Consumer<TimeLimiterEvent> listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    eventListeners.add(listener);
    return () -> eventListeners.remove(listener);
  }

  private void emitEvent(TimeLimiterEvent event) {
    for (Consumer<TimeLimiterEvent> listener : eventListeners) {
      try {
        listener.accept(event);
      } catch (Throwable t) {
        LOG.log(Level.WARNING,
            "Event listener threw exception for time limiter '%s' (event: %s): %s"
                .formatted(config.name(), event.type(), t.getMessage()), t);
      }
    }
  }

  public TimeLimiterConfig getConfig() {
    return config;
  }

  public String getInstanceId() {
    return instanceId;
  }
}
