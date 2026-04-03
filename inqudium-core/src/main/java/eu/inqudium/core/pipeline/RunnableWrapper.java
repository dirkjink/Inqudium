package eu.inqudium.core.pipeline;

public class RunnableWrapper
    extends BaseWrapper<Runnable, Void, RunnableWrapper>
    implements Runnable {

  public RunnableWrapper(String name, Runnable delegate) {
    super(name, delegate);
  }

  @Override
  public void run() {
    initiateChain();
  }

  @Override
  protected Void invokeCore() {
    getDelegate().run();
    return null;
  }

  @Override
  protected void handleLayer(String callId) {
    // Optional: Logging mit callId
  }
}
