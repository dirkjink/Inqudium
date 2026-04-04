package eu.inqudium.core.pipeline;

import java.util.function.Supplier;

/**
 * A homogeneous wrapper for the {@link Supplier} interface.
 *
 * <h3>Usage with LayerAction</h3>
 * <pre>{@code
 * Supplier<String> core = () -> fetchFromDatabase();
 *
 * // Caching layer
 * SupplierWrapper<String> cached = new SupplierWrapper<>("cache", core,
 *     (chainId, callId, arg, next) -> {
 *         if (cachedValue != null) return cachedValue;
 *         cachedValue = next.execute(chainId, callId, arg);
 *         return cachedValue;
 *     }
 * );
 * }</pre>
 *
 * @param <T> the return type of the supplier
 */
public class SupplierWrapper<T>
    extends BaseWrapper<Supplier<T>, Void, T, SupplierWrapper<T>>
    implements Supplier<T> {

  /**
   * Creates a wrapper with a custom {@link LayerAction} defining this layer's behavior.
   */
  public SupplierWrapper(String name, Supplier<T> delegate, LayerAction<Void, T> layerAction) {
    super(name, delegate, (chainId, callId, arg) -> delegate.get(), layerAction);
  }

  /**
   * Creates a wrapper with pass-through behavior (no around-advice).
   */
  public SupplierWrapper(String name, Supplier<T> delegate) {
    super(name, delegate, (chainId, callId, arg) -> delegate.get());
  }

  @Override
  public T get() {
    return initiateChain(null);
  }
}
