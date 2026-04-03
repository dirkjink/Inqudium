package eu.inqudium.core.pipeline;

import java.util.UUID;

public abstract class BaseWrapper<T, R, S extends BaseWrapper<T, R, S>>
    implements Wrapper<S>, InternalExecutor<R> {

  private final T delegate;
  private final String name;
  private final String chainId;
  private volatile S outer;

  @SuppressWarnings("unchecked")
  protected BaseWrapper(String name, T delegate) {
    this.name = name;
    this.delegate = delegate;

    if (delegate instanceof BaseWrapper) {
      S inner = (S) delegate;
      this.chainId = inner.getChainId();
      inner.setOuter((S) this);
    } else {
      this.chainId = UUID.randomUUID().toString();
    }
  }

  /**
   * Absolut typsicherer Einstieg.
   */
  protected R initiateChain() {
    // Da S eine Unterklasse von BaseWrapper ist,
    // ist S automatisch ein InternalExecutor<R>.
    S root = getOutermost();
    return root.executeWithId(UUID.randomUUID().toString());
  }

  @Override
  public R executeWithId(String callId) {
    handleLayer(callId);
    if (delegate instanceof InternalExecutor) {
      @SuppressWarnings("unchecked")
      InternalExecutor<R> internalInner = (InternalExecutor<R>) delegate;
      return internalInner.executeWithId(callId);
    }
    return invokeCore();
  }

  protected abstract void handleLayer(String callId);

  protected abstract R invokeCore();

  // --- Struktur-Methoden ---
  @Override
  public String getChainId() {
    return chainId;
  }

  @Override
  public String getLayerDescription() {
    return name;
  }

  @SuppressWarnings("unchecked")
  @Override
  public S getInner() {
    return (delegate instanceof BaseWrapper) ? (S) delegate : null;
  }

  @Override
  public S getOuter() {
    return outer;
  }

  @Override
  public void setOuter(S outer) {
    this.outer = outer;
  }

  protected T getDelegate() {
    return delegate;
  }
}
