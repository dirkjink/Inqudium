package eu.inqudium.core.pipeline;

/**
 * Core interface for the wrapper chain pattern.
 *
 * <p>A {@code Wrapper} represents a single layer in a chain of decorators. Each layer
 * wraps either another {@code Wrapper} (forming the chain) or a terminal delegate
 * (the actual logic to execute). The chain is traversed inward — from the outermost
 * layer toward the core delegate — during both execution and hierarchy visualization.</p>
 *
 * <p>The design is intentionally <strong>immutable with respect to chain structure</strong>:
 * there is no {@code setOuter()} or {@code getOuter()} method. Once a chain is constructed,
 * the layer relationships are fixed. This eliminates an entire class of concurrency and
 * shared-delegate bugs, and allows the same inner wrapper to safely participate in
 * multiple independent chains.</p>
 *
 * <h3>Type Parameter</h3>
 * <p>The recursive type parameter {@code S} (self-type) ensures that navigation methods
 * like {@link #getInner()} return the concrete wrapper type rather than a raw
 * {@code Wrapper}, enabling type-safe chain traversal without casts in client code.</p>
 *
 * <h3>Hierarchy Visualization</h3>
 * <p>The {@link #toStringHierarchy()} default method produces a human-readable tree
 * representation of the chain, useful for debugging and logging:</p>
 * <pre>{@code
 * Chain-ID: 7f3a2b...
 * Logging
 *   └── Metrics
 *     └── Security
 * }</pre>
 *
 * @param <S> the concrete self-type of the wrapper (recursive generic bound)
 */
public interface Wrapper<S extends Wrapper<S>> {

  /**
   * Returns the next inner layer in the chain, or {@code null} if this is the
   * innermost wrapper (i.e. the delegate is the actual target, not another wrapper).
   *
   * @return the inner wrapper layer, or {@code null} at the end of the chain
   */
  S getInner();

  /**
   * Returns the unique identifier shared by all layers in this chain.
   * The chain ID is generated once when the innermost wrapper is constructed
   * and propagated outward to every subsequently added layer.
   *
   * @return the chain identifier (UUID string)
   */
  String getChainId();

  /**
   * Returns a human-readable description of this layer, typically the name
   * provided at construction time. Used in {@link #toStringHierarchy()} output
   * and useful for logging or diagnostics.
   *
   * @return the descriptive name of this wrapper layer
   */
  String getLayerDescription();

  /**
   * Renders the wrapper hierarchy as a formatted tree string, starting from
   * this layer and traversing inward through {@link #getInner()}.
   *
   * <p>Since there is no outer reference, the caller determines the starting point.
   * This is typically the outermost wrapper known to the caller. If called on an
   * inner layer, only the sub-chain from that layer inward is rendered.</p>
   *
   * <p>A depth guard (max 100 layers) protects against corrupted chains that might
   * cause infinite traversal. If the limit is reached, the output is truncated
   * with a warning message.</p>
   *
   * @return a multi-line string visualizing the chain from this layer inward
   */
  default String toStringHierarchy() {
    // Safety limit to prevent infinite loops in case of chain corruption
    int maxDepth = 100;
    StringBuilder sb = new StringBuilder();
    sb.append("Chain-ID: ").append(getChainId()).append("\n");

    Wrapper<?> current = this;
    int depth = 0;
    while (current != null) {
      // Truncate if the chain exceeds the safety limit
      if (depth >= maxDepth) {
        sb.append("  ".repeat(depth - 1)).append("  └── ... (chain truncated at depth ")
          .append(maxDepth).append(")\n");
        break;
      }
      // Indent nested layers with a tree connector
      if (depth > 0) {
        sb.append("  ".repeat(depth - 1)).append("  └── ");
      }
      sb.append(current.getLayerDescription()).append("\n");
      current = current.getInner();
      depth++;
    }
    return sb.toString();
  }
}
