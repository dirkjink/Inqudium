package eu.inqudium.core.pipeline;

@FunctionalInterface
public interface ProxyExecution<R> {
  R proceed() throws Throwable;
}
