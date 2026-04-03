package eu.inqudium.core.pipeline;

interface InternalExecutor<R> {
  R executeWithId(String callId);
}
