package eu.inqudium.core.pipeline;

/**
 * Internal contract for propagating a call through the wrapper chain.
 *
 * <p>Package-private. Each layer delegates to the next via {@code execute},
 * passing both the chain ID and call ID as primitives for zero-allocation tracing.</p>
 *
 * @param <A> the argument type passed through the chain
 * @param <R> the return type produced by the chain
 */
interface InternalExecutor<A, R> {

  /**
   * Executes this layer and propagates to the next.
   *
   * @param chainId  identifies the wrapper chain (shared across all layers)
   * @param callId   identifies this particular invocation (unique per call)
   * @param argument the argument flowing through the chain
   * @return the result of the innermost delegate's execution
   */
  R execute(long chainId, long callId, A argument);
}
