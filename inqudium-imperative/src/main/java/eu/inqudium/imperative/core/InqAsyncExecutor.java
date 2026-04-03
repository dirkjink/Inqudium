package eu.inqudium.imperative.core;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Strategy interface for non-blocking execution.
 *
 * <p>Implementations define <em>how</em> a callable, future, or completion stage
 * is executed asynchronously.
 */
public interface InqAsyncExecutor {

  /**
   * Executes the callable asynchronously with a timeout.
   *
   * @param callable the operation to execute
   * @return a future that completes with the result or a timeout/failure exception
   */
  <T> CompletableFuture<T> executeAsync(Callable<T> callable);

  /**
   * Bridges an external {@link Future} into a timeout-protected {@link CompletableFuture}.
   *
   * @param futureSupplier supplier for the already-running future
   * @return a future that completes with the result or a timeout/failure exception
   */
  <T> CompletableFuture<T> executeFutureAsync(Supplier<Future<T>> futureSupplier);

  /**
   * Attaches timeout protection to an existing {@link CompletionStage} pipeline.
   *
   * @param stageSupplier supplier for the already-running completion stage
   * @return a future with timeout, events, and exception transformation attached
   */
  <T> CompletableFuture<T> executeCompletionStageAsync(Supplier<CompletionStage<T>> stageSupplier);
}
