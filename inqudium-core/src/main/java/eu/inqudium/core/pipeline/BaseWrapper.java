package eu.inqudium.core.pipeline;

import java.util.UUID;

public abstract class BaseWrapper<T, A, R, S extends BaseWrapper<T, A, R, S>>
    implements Wrapper<S>, InternalExecutor<A, R> {

  private final T delegate;
  private final String name;

  /**
   * Identifies the wrapper chain this layer belongs to.
   * All layers wrapping the same core delegate share the same chainId.
   * Generated once when the innermost wrapper (closest to the real delegate) is constructed.
   */
  private final String chainId;

  protected BaseWrapper(String name, T delegate) {
    if (name == null) {
      throw new IllegalArgumentException("Name must not be null");
    }
    if (delegate == null) {
      throw new IllegalArgumentException("Delegate must not be null");
    }
    this.name = name;
    this.delegate = delegate;
    if (delegate instanceof BaseWrapper) {
      this.chainId = ((BaseWrapper<?, ?, ?, ?>) delegate).getChainId();
    } else {
      this.chainId = UUID.randomUUID().toString();
    }
  }

  /**
   * Initiates the chain starting from this layer and propagating inward.
   * Each call receives a unique call ID for tracing purposes.
   */
  protected R initiateChain(A argument) {
    return this.executeWithId(generateCallId(), argument);
  }

  @Override
  public R executeWithId(String callId, A argument) {
    handleLayer(callId, argument);
    if (delegate instanceof InternalExecutor) {
      @SuppressWarnings("unchecked")
      InternalExecutor<A, R> internalInner = (InternalExecutor<A, R>) delegate;
      return internalInner.executeWithId(callId, argument);
    }
    return invokeCore(argument);
  }

  /**
   * Called for every layer in the chain during top-down traversal.
   * Use this to implement cross-cutting concerns like logging, metrics, or context propagation.
   *
   * @param callId unique identifier for this particular invocation, shared across all layers
   * @param argument the argument passed through the chain
   */
  protected abstract void handleLayer(String callId, A argument);

  /**
   * Executes the actual core logic by delegating to the wrapped target.
   * Only invoked on the innermost wrapper whose delegate is NOT a BaseWrapper.
   * Outer wrappers must still implement this method (it is abstract), but their
   * implementation will never be called during normal chain execution.
   *
   * @param argument the argument passed through the chain
   * @return the result of the core delegate execution
   */
  protected abstract R invokeCore(A argument);

  /**
   * Creates a unique identifier for a single chain invocation.
   * Override this to supply custom ID strategies (e.g. sequential counters,
   * shorter IDs, or externally provided correlation IDs).
   *
   * @return a new unique call ID
   */
  protected String generateCallId() {
    return UUID.randomUUID().toString();
  }

  @Override public String getChainId() { return chainId; }
  @Override public String getLayerDescription() { return name; }

  @SuppressWarnings("unchecked")
  @Override public S getInner() {
    return (delegate instanceof BaseWrapper) ? (S) delegate : null;
  }

  protected T getDelegate() { return delegate; }
}
