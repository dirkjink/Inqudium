package eu.inqudium.core.pipeline;

import java.util.function.Function;

/**
 * A homogeneous wrapper for the {@link Function} interface.
 *
 * <p>Unlike the other wrappers in this package, {@code FunctionWrapper} carries a
 * non-void argument type. The input value passed to {@link #apply} flows through
 * every layer's {@link #handleLayer} method (as the {@code argument} parameter)
 * before reaching the core delegate. This allows layers to inspect, log, or even
 * validate the input at each stage.</p>
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * Function<String, Integer> parser = Integer::parseInt;
 * FunctionWrapper<String, Integer> validated = new FunctionWrapper<>("validation", parser) {
 *     @Override
 *     protected void handleLayer(String callId, String input) {
 *         if (input == null || input.isBlank()) {
 *             throw new IllegalArgumentException("Input must not be blank");
 *         }
 *     }
 * };
 * int result = validated.apply("42");  // returns 42
 * }</pre>
 *
 * @param <I> the input type of the function
 * @param <O> the output type of the function
 */
public class FunctionWrapper<I, O>
    extends BaseWrapper<Function<I, O>, I, O, FunctionWrapper<I, O>>
    implements Function<I, O> {

  /**
   * Creates a new wrapper layer around the given {@link Function}.
   *
   * @param name     a descriptive name for this layer (e.g. "validation", "transformation")
   * @param delegate the function to wrap (another wrapper or the core target)
   */
  public FunctionWrapper(String name, Function<I, O> delegate) {
    super(name, delegate);
  }

  /**
   * Implements {@link Function#apply} by initiating the wrapper chain with the
   * given input. The input is passed through every layer's {@link #handleLayer}
   * and ultimately forwarded to the core delegate's {@code apply()} method.
   *
   * @param input the function argument
   * @return the result produced by the core function
   */
  @Override
  public O apply(I input) {
    // Pass the input as the chain argument — unlike Void-based wrappers, this carries data
    return initiateChain(input);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Invokes the core delegate's {@code apply()} method with the input that was
   * passed through the entire chain.</p>
   */
  @Override
  protected O invokeCore(I input) {
    return getDelegate().apply(input);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Override this method in a subclass to add cross-cutting behavior. Unlike the
   * Void-based wrappers, the {@code input} parameter carries the actual function
   * argument, enabling input validation, logging, or transformation at each layer.</p>
   *
   * @param callId the unique invocation identifier
   * @param input  the function argument flowing through the chain
   */
  @Override
  protected void handleLayer(String callId, I input) {
    // No-op by default — extend and override to add behavior
  }
}
