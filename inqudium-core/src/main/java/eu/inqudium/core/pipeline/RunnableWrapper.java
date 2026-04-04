package eu.inqudium.core.pipeline;

/**
 * A homogeneous wrapper for the {@link Runnable} interface.
 *
 * <p>Since {@code Runnable} takes no arguments and returns no value, both the argument
 * type and the return type are {@code Void}.</p>
 *
 * <h3>Usage with LayerAction</h3>
 * <pre>{@code
 * Runnable core = () -> System.out.println("Hello");
 *
 * // Timing layer
 * RunnableWrapper timed = new RunnableWrapper("timing", core, (chainId, callId, arg, next) -> {
 *     long start = System.nanoTime();
 *     Void result = next.execute(chainId, callId, arg);
 *     System.out.println("Took " + (System.nanoTime() - start) + "ns");
 *     return result;
 * });
 *
 * // Logging layer wrapping the timing layer
 * RunnableWrapper logged = new RunnableWrapper("logging", timed, (chainId, callId, arg, next) -> {
 *     System.out.println("[call=" + callId + "] entering");
 *     return next.execute(chainId, callId, arg);
 * });
 *
 * logged.run();  // logs entry → measures time → prints "Hello"
 * }</pre>
 */
public class RunnableWrapper
    extends BaseWrapper<Runnable, Void, Void, RunnableWrapper>
    implements Runnable {

  /**
   * Creates a wrapper with a custom {@link LayerAction} defining this layer's behavior.
   *
   * @param name        a descriptive name for this layer
   * @param delegate    the runnable to wrap (another wrapper or the core target)
   * @param layerAction the around-advice for this layer
   */
  public RunnableWrapper(String name, Runnable delegate, LayerAction<Void, Void> layerAction) {
    super(name, delegate, (chainId, callId, arg) -> { delegate.run(); return null; }, layerAction);
  }

  /**
   * Creates a wrapper with pass-through behavior (no around-advice).
   *
   * @param name     a descriptive name for this layer
   * @param delegate the runnable to wrap
   */
  public RunnableWrapper(String name, Runnable delegate) {
    super(name, delegate, (chainId, callId, arg) -> { delegate.run(); return null; });
  }

  @Override
  public void run() {
    initiateChain(null);
  }
}
