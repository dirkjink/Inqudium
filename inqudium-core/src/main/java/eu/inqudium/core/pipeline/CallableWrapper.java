package eu.inqudium.core.pipeline;

import java.util.concurrent.Callable;

public class CallableWrapper<V>
    extends BaseWrapper<Callable<V>, V, CallableWrapper<V>>
    implements Callable<V> {

  public CallableWrapper(String name, Callable<V> delegate) {
    super(name, delegate);
  }

  @Override
  public V call() throws Exception {
    // Hinweis: Da initiateChain intern executeWithId nutzt,
    // müssen wir hier eventuelle Exceptions durchreichen.
    try {
      return initiateChain();
    } catch (RuntimeException e) {
      if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
      throw e;
    }
  }

  @Override
  protected V invokeCore() {
    try {
      return getDelegate().call();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void handleLayer(String callId) {
    // Optional: Logging
  }
}
