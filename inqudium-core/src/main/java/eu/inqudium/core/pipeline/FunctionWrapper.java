package eu.inqudium.core.pipeline;

import java.util.function.Function;

/**
 * A homogeneous wrapper for the {@link Function} interface.
 *
 * <p>Unlike the other wrappers, {@code FunctionWrapper} carries a non-void argument type.
 * The input flows through every layer's {@link LayerAction}, enabling input validation,
 * transformation, or logging at each stage.</p>
 *
 * <h3>Usage with LayerAction</h3>
 * <pre>{@code
 * Function<String, Integer> parser = Integer::parseInt;
 *
 * // Validation layer
 * FunctionWrapper<String, Integer> validated = new FunctionWrapper<>("validation", parser,
 *     (chainId, callId, input, next) -> {
 *         if (input == null || input.isBlank()) {
 *             throw new IllegalArgumentException("Input must not be blank");
 *         }
 *         return next.execute(chainId, callId, input);
 *     }
 * );
 * }</pre>
 *
 * @param <I> the input type of the function
 * @param <O> the output type of the function
 */
public class FunctionWrapper<I, O>
    extends BaseWrapper<Function<I, O>, I, O, FunctionWrapper<I, O>>
    implements Function<I, O> {

  /**
   * Creates a wrapper with a custom {@link LayerAction} defining this layer's behavior.
   */
  public FunctionWrapper(String name, Function<I, O> delegate, LayerAction<I, O> layerAction) {
    super(name, delegate, (chainId, callId, input) -> delegate.apply(input), layerAction);
  }

  /**
   * Creates a wrapper with pass-through behavior (no around-advice).
   */
  public FunctionWrapper(String name, Function<I, O> delegate) {
    super(name, delegate, (chainId, callId, input) -> delegate.apply(input));
  }

  @Override
  public O apply(I input) {
    return initiateChain(input);
  }
}
