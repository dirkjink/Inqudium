package eu.inqudium.core.context;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registry for {@link InqContextPropagator} instances.
 *
 * <p>Propagators are discovered via ServiceLoader and/or registered programmatically.
 * Follows ADR-014 conventions: lazy discovery, Comparable ordering, error isolation,
 * frozen after first access.
 *
 * <h2>Instance-based design</h2>
 * <p>The registry is an instance — not a static utility. A shared global instance
 * is available via {@link #getDefault()} for production use. Tests create isolated
 * instances via the constructor, avoiding cross-test pollution in parallel execution.
 *
 * <h2>Thread safety</h2>
 * <p>All state is held in a single {@link AtomicReference AtomicReference&lt;RegistryState&gt;}.
 * The state machine transitions are:
 * <pre>{@code
 * Open(programmatic=[])
 *   ──register(p)──►  Open(programmatic=[p])       (CAS: Open→Open)
 *   ──getPropagators()──►  Resolving                 (CAS: Open→Resolving, one thread wins)
 *                          ──discovery──►  Frozen(resolved=[...])
 * }</pre>
 * <p>Only the thread that successfully CAS-es from {@code Open} to {@code Resolving}
 * performs ServiceLoader I/O. All other threads seeing {@code Resolving} yield until
 * {@code Frozen} appears.
 *
 * @since 0.1.0
 */
public final class InqContextPropagatorRegistry {

  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(InqContextPropagatorRegistry.class);

  // ── Global default instance ──

  private static final AtomicReference<InqContextPropagatorRegistry> DEFAULT_INSTANCE = new AtomicReference<>();
  private final AtomicReference<RegistryState> state = new AtomicReference<>(new Open());

  /**
   * Creates a new, empty registry.
   *
   * <p>Use for testing or when you need isolated propagator scopes.
   * For production, use {@link #getDefault()}.
   */
  public InqContextPropagatorRegistry() {
  }

  // ── State machine ──

  /**
   * Returns the shared global registry instance.
   *
   * <p>Created atomically on first access via CAS — no split-brain risk.
   *
   * @return the global default registry
   */
  public static InqContextPropagatorRegistry getDefault() {
    var instance = DEFAULT_INSTANCE.get();
    if (instance != null) {
      return instance;
    }
    DEFAULT_INSTANCE.compareAndSet(null, new InqContextPropagatorRegistry());
    return DEFAULT_INSTANCE.get();
  }

  /**
   * Replaces the global default registry.
   *
   * <p><strong>For testing only.</strong> Allows tests to install an isolated
   * registry or reset the global state between test runs.
   *
   * @param registry the new default registry (or {@code null} to reset to lazy creation)
   */
  public static void setDefault(InqContextPropagatorRegistry registry) {
    DEFAULT_INSTANCE.set(registry);
  }

  @SuppressWarnings("unchecked")
  private static List<InqContextPropagator> discoverAndMerge(List<InqContextPropagator> programmatic) {
    var serviceLoaderPropagators = new ArrayList<InqContextPropagator>();

    try {
      var loader = ServiceLoader.load(InqContextPropagator.class);
      Iterator<InqContextPropagator> iterator = loader.iterator();
      while (true) {
        boolean hasNext;
        try {
          hasNext = iterator.hasNext();
        } catch (Throwable t) {
          rethrowIfFatal(t);
          LOGGER.warn("ServiceLoader iterator.hasNext() failed for InqContextPropagator " +
              "— remaining providers skipped.", t);
          break;
        }
        if (!hasNext) {
          break;
        }
        try {
          serviceLoaderPropagators.add(iterator.next());
        } catch (Throwable t) {
          rethrowIfFatal(t);
          LOGGER.warn("Failed to load InqContextPropagator provider — provider skipped.", t);
        }
      }
    } catch (Throwable t) {
      rethrowIfFatal(t);
      LOGGER.warn("ServiceLoader discovery for InqContextPropagator failed.", t);
    }

    // Sort: Comparable first (ascending), then non-Comparable
    var comparable = new ArrayList<InqContextPropagator>();
    var nonComparable = new ArrayList<InqContextPropagator>();
    for (var p : serviceLoaderPropagators) {
      if (p instanceof Comparable) {
        comparable.add(p);
      } else {
        nonComparable.add(p);
      }
    }
    comparable.sort((a, b) -> ((Comparable<InqContextPropagator>) a).compareTo(b));

    var result = new ArrayList<InqContextPropagator>(comparable.size() + nonComparable.size() + programmatic.size());
    result.addAll(comparable);
    result.addAll(nonComparable);
    result.addAll(programmatic);
    return List.copyOf(result);
  }

  private static void rethrowIfFatal(Throwable t) {
    if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
    if (t instanceof LinkageError) throw (LinkageError) t;
  }

  /**
   * Registers a propagator programmatically.
   *
   * <p>Must be called before the first context propagation occurs.
   * Registrations after the first access throw {@link IllegalStateException}.
   *
   * @param propagator the propagator to register
   * @throws IllegalStateException if the registry is already frozen or resolving
   */
  public void register(InqContextPropagator propagator) {
    Objects.requireNonNull(propagator, "propagator must not be null");
    while (true) {
      var current = state.get();
      if (current instanceof Open open) {
        var updated = new ArrayList<>(open.programmatic);
        updated.add(propagator);
        var next = new Open(List.copyOf(updated));
        if (state.compareAndSet(current, next)) {
          return;
        }
        continue;
      }
      throw new IllegalStateException(
          "InqContextPropagatorRegistry is frozen — propagators must be registered " +
              "before the first context propagation.");
    }
  }

  /**
   * Returns the ordered list of all registered propagators.
   *
   * <p>On first call, triggers ServiceLoader discovery and freezes the registry.
   * Only the thread that claims the {@code Resolving} state performs I/O.
   *
   * @return unmodifiable list of propagators
   */
  public List<InqContextPropagator> getPropagators() {
    while (true) {
      var current = state.get();

      if (current instanceof Frozen frozen) {
        return frozen.resolved;
      }

      if (current instanceof Open open) {
        var resolving = new Resolving(open.programmatic);
        if (state.compareAndSet(current, resolving)) {
          var resolved = discoverAndMerge(open.programmatic);
          state.set(new Frozen(resolved));
          return resolved;
        }
        continue;
      }

      // Resolving — another thread is doing discovery, yield and retry
      Thread.yield();
    }
  }

  private sealed interface RegistryState permits Open, Resolving, Frozen {
  }

  private record Open(List<InqContextPropagator> programmatic) implements RegistryState {
    Open() {
      this(List.of());
    }
  }

  private record Resolving(List<InqContextPropagator> programmatic) implements RegistryState {
  }

  private record Frozen(List<InqContextPropagator> resolved) implements RegistryState {
  }
}
