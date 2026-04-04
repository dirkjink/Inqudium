package eu.inqudium.core.pipeline;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for all wrapper layers in the pipeline.
 *
 * <p>{@code BaseWrapper} provides the core chain-execution mechanism. When a public
 * functional method is invoked on the outermost wrapper, it calls {@link #initiateChain},
 * which generates a unique call ID and begins a top-down traversal through every layer
 * via {@link #execute}. Both the chain ID and the call ID are passed as primitive
 * {@code long} values for zero-allocation tracing.</p>
 *
 * <h3>Execution Flow</h3>
 * <pre>{@code
 * outerWrapper.run()
 *   └── initiateChain(null)                               // generates callId
 *         └── outer.execute(chainId, callId, null)         // handleLayer → forward
 *               └── inner.execute(chainId, callId, null)   // handleLayer → forward
 *                     └── coreExecution                     // calls delegate.run()
 * }</pre>
 *
 * @param <T> the delegate type this wrapper wraps around
 * @param <A> the argument type flowing through the chain
 * @param <R> the return type flowing back through the chain
 * @param <S> the concrete self-type (recursive generic bound)
 */
public abstract class BaseWrapper<T, A, R, S extends BaseWrapper<T, A, R, S>>
    implements Wrapper<S>, InternalExecutor<A, R> {

  /** Global counter for chain IDs — unique per JVM, monotonically increasing. */
  private static final AtomicLong CHAIN_ID_COUNTER = new AtomicLong();

  private final T delegate;
  private final String name;
  private final long chainId;
  private final InternalExecutor<A, R> nextStep;

  /**
   * Shared call ID counter for this chain. Created once by the innermost wrapper
   * and inherited by every outer wrapper, just like the {@link #chainId}.
   */
  private final AtomicLong callIdCounter;

  /**
   * Constructs a new wrapper layer around the given delegate.
   *
   * <p>If the delegate is itself a {@code BaseWrapper}, this layer joins the same chain
   * by inheriting the delegate's {@link #chainId} and {@link #callIdCounter}.
   * Otherwise, a new chain ID and counter are created.</p>
   *
   * @param name          a descriptive name for this layer (must not be {@code null})
   * @param delegate      the target to wrap (must not be {@code null})
   * @param coreExecution the terminal execution logic when the delegate is not a wrapper
   */
  @SuppressWarnings("unchecked")
  protected BaseWrapper(String name, T delegate, InternalExecutor<A, R> coreExecution) {
    if (name == null) {
      throw new IllegalArgumentException("Name must not be null");
    }
    if (delegate == null) {
      throw new IllegalArgumentException("Delegate must not be null");
    }
    this.name = name;
    this.delegate = delegate;

    if (delegate instanceof BaseWrapper<?,?,?,?> innerWrapper) {
      this.chainId = innerWrapper.getChainId();
      this.callIdCounter = innerWrapper.callIdCounter;
      this.nextStep = (InternalExecutor<A, R>) delegate;
    } else {
      this.chainId = CHAIN_ID_COUNTER.incrementAndGet();
      this.callIdCounter = new AtomicLong();
      this.nextStep = coreExecution;
    }
  }

  /**
   * Entry point for chain execution. Generates a fresh call ID and starts traversal,
   * passing both the chain ID and the call ID through every layer.
   */
  protected R initiateChain(A argument) {
    return this.execute(chainId, generateCallId(), argument);
  }

  /**
   * Processes this layer and propagates to the next step.
   *
   * @param chainId  the chain identifier, shared across all layers
   * @param callId   the call identifier, unique per invocation
   * @param argument the argument flowing through the chain
   * @return the result of the innermost delegate's execution
   */
  @Override
  public R execute(long chainId, long callId, A argument) {
    handleLayer(chainId, callId, argument);
    return nextStep.execute(chainId, callId, argument);
  }

  /**
   * Hook for layer-specific cross-cutting logic. No-op by default.
   *
   * <p>Both the chain ID and call ID are available as primitives, enabling
   * zero-allocation logging and tracing without calling {@link #getChainId()}.</p>
   *
   * @param chainId  the chain identifier (which wrapper chain this belongs to)
   * @param callId   the call identifier (which invocation this is)
   * @param argument the argument passed through the chain
   */
  protected void handleLayer(long chainId, long callId, A argument) {
    // No-op by default — override in subclasses to add cross-cutting behavior
  }

  /**
   * Creates a unique call identifier using the chain's shared counter.
   * Override to supply external correlation IDs.
   */
  protected long generateCallId() {
    return callIdCounter.incrementAndGet();
  }

  @Override public long getChainId() { return chainId; }
  @Override public String getLayerDescription() { return name; }

  @SuppressWarnings("unchecked")
  @Override public S getInner() {
    return (delegate instanceof BaseWrapper) ? (S) delegate : null;
  }
}
