package eu.inqudium.core.pipeline;

import java.util.concurrent.CompletionException;

/**
 * Wrapper for dynamic proxies and Spring AOP join points.
 *
 * <p>Integrates AOP proxy executions into the wrapper chain. Checked exceptions
 * are transported via {@link CompletionException} and unwrapped in {@link #proceed()}.</p>
 *
 * <h3>Usage with LayerAction</h3>
 * <pre>{@code
 * @Around("@annotation(Traced)")
 * public Object trace(ProceedingJoinPoint pjp) throws Throwable {
 *     JoinPointWrapper<Object> wrapper = new JoinPointWrapper<>(
 *         pjp.getSignature().toShortString(),
 *         pjp::proceed,
 *         (chainId, callId, arg, next) -> {
 *             MDC.put("chainId", Long.toString(chainId));
 *             MDC.put("callId", Long.toString(callId));
 *             try {
 *                 return next.execute(chainId, callId, arg);
 *             } finally {
 *                 MDC.clear();
 *             }
 *         }
 *     );
 *     return wrapper.proceed();
 * }
 * }</pre>
 *
 * @param <R> the return type of the join point execution
 */
public class JoinPointWrapper<R>
    extends BaseWrapper<ProxyExecution<R>, Void, R, JoinPointWrapper<R>>
    implements ProxyExecution<R> {

  private static <R> InternalExecutor<Void, R> coreFor(ProxyExecution<R> delegate) {
    return (chainId, callId, arg) -> {
      try {
        return delegate.proceed();
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Throwable t) {
        throw new CompletionException(t);
      }
    };
  }

  /**
   * Creates a wrapper with a custom {@link LayerAction} defining this layer's behavior.
   */
  public JoinPointWrapper(String name, ProxyExecution<R> delegate, LayerAction<Void, R> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  /**
   * Creates a wrapper with pass-through behavior (no around-advice).
   */
  public JoinPointWrapper(String name, ProxyExecution<R> delegate) {
    super(name, delegate, coreFor(delegate));
  }

  @Override
  public R proceed() throws Throwable {
    try {
      return initiateChain(null);
    } catch (RuntimeException e) {
      if (e instanceof CompletionException) {
        throw e.getCause();
      }
      throw e;
    }
  }
}
