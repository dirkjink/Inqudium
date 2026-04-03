package eu.inqudium.core.pipeline;

/**
 * Internal marker exception used to transport checked exceptions and throwables
 * through the {@link InternalExecutor} chain, which only supports unchecked exceptions.
 *
 * <p>This is intentionally package-private. The public wrapper methods (e.g.
 * {@link CallableWrapper#call()}, {@link JoinPointWrapper#proceed()}) unwrap the
 * original cause before re-throwing, so callers never see this type.</p>
 */
class WrappedCheckedException extends RuntimeException {

  WrappedCheckedException(Throwable cause) {
    super(cause);
  }
}
