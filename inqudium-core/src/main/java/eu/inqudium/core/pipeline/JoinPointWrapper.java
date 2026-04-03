package eu.inqudium.core.pipeline;

/**
 * Wraps a Spring AOP {@code ProceedingJoinPoint} into the hierarchical wrapper structure.
 * <p>
 * This method creates a {@link JoinPointWrapper} around the intercepted join point.
 * By doing so, the AOP execution becomes a formal layer in the static wrapper stack.
 * This allows the join point to participate in the unique {@code callId} propagation
 * and the {@code toStringHierarchy()} visualization.
 * </p>
 *
 * <p><strong>Example Aspect Implementation:</strong></p>
 * <pre>{@code
 * @Around("@annotation(MyCustomAnnotation)")
 * public Object traceHierarchy(ProceedingJoinPoint pjp) throws Throwable {
 * // Create a new layer for this specific execution point
 * JoinPointWrapper<Object> wrapper = new JoinPointWrapper<>(
 * pjp.getSignature().toShortString(),
 * pjp::proceed
 * );
 *
 * // Initiates the chain from the outermost layer, ensuring
 * // all high-level wrappers and ID generation are processed.
 * return wrapper.execute();
 * }
 * }</pre>
 *
 * <p><strong>Key Benefits:</strong></p>
 * <ul>
 * <li><b>ID Consistency:</b> Every sub-call within the join point can access
 * the same {@code callId} via the stack.</li>
 * <li><b>Full Transparency:</b> The {@code toStringHierarchy()} will list
 * the AOP proxy as a named layer (e.g., "Service.doWork()").</li>
 * <li><b>Exception Safety:</b> Throwable exceptions from {@code proceed()}
 * are passed through transparently.</li>
 * </ul>
 *
 */
public class JoinPointWrapper<R>
    extends BaseWrapper<ProxyExecution<R>, R, JoinPointWrapper<R>>
    implements ProxyExecution<R> { // <-- Hier liegt die Lösung

  public JoinPointWrapper(String name, ProxyExecution<R> delegate) {
    super(name, delegate);
  }

  /**
   * Der Einstiegspunkt. Ersetzt die alte execute() Methode.
   */
  @Override
  public R proceed() throws Throwable { // <-- Implementiert das Interface
    try {
      return initiateChain();
    } catch (RuntimeException e) {
      // Entpacken der Exception, falls sie vom Kern geworfen wurde
      if (e.getCause() != null) throw e.getCause();
      throw e;
    }
  }

  @Override
  protected R invokeCore() {
    try {
      return getDelegate().proceed();
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void handleLayer(String callId) {
    // Optional: Logging context updates
  }
}