package eu.inqudium.core.pipeline;

/**
 * A homogeneous wrapper for the {@link Runnable} interface.
 *
 * <p>Since {@code Runnable} takes no arguments and returns no value, both the argument
 * type and the return type are {@code Void}. The chain is initiated with a {@code null}
 * argument and the core execution returns {@code null} to satisfy the generic signature.</p>
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * Runnable core = () -> System.out.println("Hello");
 * RunnableWrapper logged = new RunnableWrapper("logging", core) {
 *     @Override
 *     protected void handleLayer(String callId, Void argument) {
 *         System.out.println("[" + callId + "] Entering " + getLayerDescription());
 *     }
 * };
 * logged.run();  // prints the callId log line, then "Hello"
 * }</pre>
 */
public class RunnableWrapper
    extends BaseWrapper<Runnable, Void, Void, RunnableWrapper>
    implements Runnable {

  /**
   * Creates a new wrapper layer around the given {@link Runnable}.
   *
   * @param name     a descriptive name for this layer (e.g. "logging", "metrics")
   * @param delegate the runnable to wrap (another wrapper or the core target)
   */
  public RunnableWrapper(String name, Runnable delegate) {
    super(name, delegate);
  }

  /**
   * Implements {@link Runnable#run()} by initiating the wrapper chain.
   * The chain traverses all layers and ultimately calls the core delegate's
   * {@code run()} method.
   */
  @Override
  public void run() {
    // Void argument type — pass null to start the chain
    initiateChain(null);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Invokes the core delegate's {@code run()} method. Returns {@code null} because
   * Java requires a return value for the generic type {@code Void}.</p>
   */
  @Override
  protected Void invokeCore(Void argument) {
    getDelegate().run();
    return null;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Override this method in a subclass to add cross-cutting behavior
   * (e.g. logging, timing, or context propagation) to the runnable execution.</p>
   */
  @Override
  protected void handleLayer(String callId, Void argument) {
    // No-op by default — extend and override to add behavior
  }
}
