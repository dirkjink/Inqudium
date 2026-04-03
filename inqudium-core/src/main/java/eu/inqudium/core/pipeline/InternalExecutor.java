package eu.inqudium.core.pipeline;

/**
 * Internal contract for propagating a call through the wrapper chain.
 *
 * <p>This interface is intentionally <strong>package-private</strong>. It exists solely
 * to enable the recursive delegation mechanism inside {@link BaseWrapper#executeWithId}:
 * each layer checks whether its delegate also implements {@code InternalExecutor} and,
 * if so, forwards the call with the same {@code callId}. This ensures that every layer
 * in the chain receives the same tracing identifier without requiring the public
 * functional interfaces (Runnable, Supplier, etc.) to be aware of it.</p>
 *
 * <p>External callers never interact with this interface directly. They invoke the
 * standard functional method (e.g. {@code run()}, {@code get()}, {@code call()}) on
 * the outermost wrapper, which internally delegates to {@code initiateChain()} and
 * then to {@code executeWithId()}.</p>
 *
 * @param <A> the argument type passed through the chain ({@code Void} for no-arg wrappers)
 * @param <R> the return type produced by the chain ({@code Void} for fire-and-forget wrappers)
 */
interface InternalExecutor<A, R> {

  /**
   * Executes this layer's logic and propagates the call to the next inner layer.
   *
   * <p>The {@code callId} is generated once per invocation by the outermost layer's
   * {@link BaseWrapper#initiateChain} method and threaded through unchanged to every
   * layer. This allows cross-cutting concerns (logging, tracing, MDC propagation) to
   * correlate all activity within a single logical call.</p>
   *
   * @param callId  a unique identifier for this particular invocation
   * @param argument the argument to pass through the chain
   * @return the result of the innermost delegate's execution
   */
  R executeWithId(String callId, A argument);
}
