package eu.inqudium.core.event;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registry for {@link InqEventExporter} instances.
 *
 * <p>Exporters receive all events from all elements that use this registry.
 * Registration follows ADR-014 conventions:
 * <ul>
 *   <li>ServiceLoader discovery: lazy on first access, cached for lifetime.</li>
 *   <li>Comparable ordering: Comparable exporters sorted first, then non-Comparable,
 *       then programmatically registered.</li>
 *   <li>Error isolation: a failing exporter is caught and logged, never affects
 *       other exporters or the resilience element.</li>
 *   <li>Frozen after first access: programmatic registration must happen before
 *       the first {@link #export(InqEvent)} call.</li>
 * </ul>
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
 *   ──register(a)──►  Open(programmatic=[a])       (CAS: Open→Open)
 *   ──getExporters()──►  Resolving                   (CAS: Open→Resolving, one thread wins)
 *                        ──discovery──►  Frozen(resolved=[...])
 * }</pre>
 * <p>Only the thread that successfully CAS-es from {@code Open} to {@code Resolving}
 * performs ServiceLoader I/O. All other threads seeing {@code Resolving} yield until
 * {@code Frozen} appears. This prevents a thundering-herd of concurrent I/O operations
 * during application startup.
 *
 * @since 0.1.0
 */
public final class InqEventExporterRegistry {

  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(InqEventExporterRegistry.class);

  // ── Global default instance ──

  private static final AtomicReference<InqEventExporterRegistry> DEFAULT_INSTANCE = new AtomicReference<>();
  private final AtomicReference<RegistryState> state = new AtomicReference<>(new Open());

  /**
   * Creates a new, empty registry.
   *
   * <p>Use for testing or when you need isolated exporter scopes.
   * For production, use {@link #getDefault()}.
   */
  public InqEventExporterRegistry() {
  }

  // ── State machine ──

  /**
   * Returns the shared global registry instance.
   *
   * <p>Created atomically on first access via CAS — no split-brain risk.
   *
   * @return the global default registry
   */
  public static InqEventExporterRegistry getDefault() {
    var instance = DEFAULT_INSTANCE.get();
    if (instance != null) {
      return instance;
    }
    DEFAULT_INSTANCE.compareAndSet(null, new InqEventExporterRegistry());
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
  public static void setDefault(InqEventExporterRegistry registry) {
    DEFAULT_INSTANCE.set(registry);
  }

  private static boolean isSubscribed(InqEventExporter exporter, InqEvent event) {
    var types = exporter.subscribedEventTypes();
    if (types == null || types.isEmpty()) {
      return true;
    }
    for (var type : types) {
      if (type.isInstance(event)) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static List<InqEventExporter> discoverAndMerge(List<InqEventExporter> programmatic) {
    var serviceLoaderExporters = new ArrayList<InqEventExporter>();

    try {
      var loader = ServiceLoader.load(InqEventExporter.class);
      Iterator<InqEventExporter> iterator = loader.iterator();
      while (true) {
        // hasNext() and next() are separated — a stuck hasNext() breaks
        // the loop instead of spinning forever
        boolean hasNext;
        try {
          hasNext = iterator.hasNext();
        } catch (Throwable t) {
          rethrowIfFatal(t);
          LOGGER.warn("ServiceLoader iterator.hasNext() failed for InqEventExporter " +
              "— remaining providers skipped.", t);
          break; // iterator is stuck — cannot advance past broken provider
        }
        if (!hasNext) {
          break;
        }
        try {
          serviceLoaderExporters.add(iterator.next());
        } catch (Throwable t) {
          rethrowIfFatal(t);
          LOGGER.warn("Failed to load InqEventExporter provider — provider skipped.", t);
          // next() failed but iterator may have advanced — continue
        }
      }
    } catch (Throwable t) {
      rethrowIfFatal(t);
      LOGGER.warn("ServiceLoader discovery for InqEventExporter failed.", t);
    }

    // Sort: Comparable first (ascending), then non-Comparable
    var comparable = new ArrayList<InqEventExporter>();
    var nonComparable = new ArrayList<InqEventExporter>();
    for (var exporter : serviceLoaderExporters) {
      if (exporter instanceof Comparable) {
        comparable.add(exporter);
      } else {
        nonComparable.add(exporter);
      }
    }
    comparable.sort((a, b) -> ((Comparable<InqEventExporter>) a).compareTo(b));

    var result = new ArrayList<InqEventExporter>(comparable.size() + nonComparable.size() + programmatic.size());
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
   * Registers an exporter programmatically.
   *
   * <p>Must be called before the first event is exported. Registrations after
   * the first export throw {@link IllegalStateException} (ADR-014, Convention 5).
   *
   * <p>Lock-free: uses CAS to append to the immutable programmatic list.
   *
   * @param exporter the exporter to register
   * @throws IllegalStateException if the registry is already frozen or resolving
   */
  public void register(InqEventExporter exporter) {
    Objects.requireNonNull(exporter, "exporter must not be null");
    while (true) {
      var current = state.get();
      if (current instanceof Open open) {
        var updated = new ArrayList<>(open.programmatic);
        updated.add(exporter);
        var next = new Open(List.copyOf(updated));
        if (state.compareAndSet(current, next)) {
          return;
        }
        // CAS failed — another thread modified state, retry
        continue;
      }
      // Resolving or Frozen — too late
      throw new IllegalStateException(
          "InqEventExporterRegistry is frozen — exporters must be registered " +
              "before the first event is exported.");
    }
  }

  /**
   * Exports an event to all registered exporters.
   *
   * <p>On first call, triggers ServiceLoader discovery and freezes the registry.
   * Exporter exceptions are caught and logged — they never propagate.
   *
   * @param event the event to export
   */
  public void export(InqEvent event) {
    for (var exporter : getExporters()) {
      try {
        if (isSubscribed(exporter, event)) {
          exporter.export(event);
        }
      } catch (Throwable t) {
        rethrowIfFatal(t);
        LOGGER.warn("[{}] InqEventExporter {} threw on event {}",
            event.getCallId(), exporter.getClass().getName(),
            event.getClass().getSimpleName(), t);
      }
    }
  }

  /**
   * Returns the resolved exporter list, triggering discovery on first access.
   *
   * <p>Only the thread that successfully CAS-es {@code Open → Resolving}
   * performs the (potentially expensive) ServiceLoader I/O. All other threads
   * yield until the resolver publishes {@code Frozen}.
   */
  private List<InqEventExporter> getExporters() {
    while (true) {
      var current = state.get();

      if (current instanceof Frozen frozen) {
        return frozen.resolved;
      }

      if (current instanceof Open open) {
        // Try to claim the resolver role
        var resolving = new Resolving(open.programmatic);
        if (state.compareAndSet(current, resolving)) {
          // We won — perform discovery (outside CAS loop, only once)
          var resolved = discoverAndMerge(open.programmatic);
          state.set(new Frozen(resolved));
          return resolved;
        }
        // CAS failed — someone else changed state, re-read
        continue;
      }

      // Resolving — another thread is doing discovery, yield and retry
      Thread.yield();
    }
  }

  private sealed interface RegistryState permits Open, Resolving, Frozen {
  }

  /**
   * Accepts registrations. Contains the accumulated programmatic exporters.
   */
  private record Open(List<InqEventExporter> programmatic) implements RegistryState {
    Open() {
      this(List.of());
    }
  }

  /**
   * Discovery in progress. Only one thread performs I/O; others yield.
   */
  private record Resolving(List<InqEventExporter> programmatic) implements RegistryState {
  }

  /**
   * Resolved and frozen. No more registrations accepted.
   */
  private record Frozen(List<InqEventExporter> resolved) implements RegistryState {
  }
}
