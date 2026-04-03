package eu.inqudium.core.pipeline;

import java.util.function.Supplier;

/**
 * A homogeneous wrapper for the {@link Supplier} interface.
 *
 * <p>Since a {@code Supplier} takes no arguments, the argument type is {@code Void}
 * and the chain is initiated with {@code null}. The return value of the core supplier
 * propagates back through the chain to the caller.</p>
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * Supplier<String> core = () -> "world";
 * SupplierWrapper<String> cached = new SupplierWrapper<>("caching", core) {
 *     private String cachedValue;
 *
 *     @Override
 *     protected void handleLayer(String callId, Void argument) {
 *         // Could implement caching logic using callId for cache key
 *     }
 * };
 * String result = cached.get();  // returns "world"
 * }</pre>
 *
 * @param <T> the return type of the supplier
 */
public class SupplierWrapper<T>
    extends BaseWrapper<Supplier<T>, Void, T, SupplierWrapper<T>>
    implements Supplier<T> {

  /**
   * Creates a new wrapper layer around the given {@link Supplier}.
   *
   * @param name     a descriptive name for this layer (e.g. "caching", "retry")
   * @param delegate the supplier to wrap (another wrapper or the core target)
   */
  public SupplierWrapper(String name, Supplier<T> delegate) {
    super(name, delegate);
  }

  /**
   * Implements {@link Supplier#get()} by initiating the wrapper chain.
   * The chain traverses all layers and ultimately calls the core delegate's
   * {@code get()} method, returning its result.
   *
   * @return the value produced by the core supplier
   */
  @Override
  public T get() {
    // Void argument type — pass null to start the chain
    return initiateChain(null);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Invokes the core delegate's {@code get()} method and returns its result.</p>
   */
  @Override
  protected T invokeCore(Void argument) {
    return getDelegate().get();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Override this method in a subclass to add cross-cutting behavior
   * (e.g. caching, logging, or circuit breaking) to the supplier execution.</p>
   */
  @Override
  protected void handleLayer(String callId, Void argument) {
    // No-op by default — extend and override to add behavior
  }
}
