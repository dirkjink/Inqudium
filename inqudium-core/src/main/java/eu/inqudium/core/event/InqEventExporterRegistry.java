package eu.inqudium.core.event;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

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
 * The state machine uses a {@code Resolving} intermediate state so that exactly one
 * thread performs ServiceLoader I/O. Waiting threads park with exponential backoff
 * up to a bounded total timeout. If the resolver thread dies, the state falls back
 * to {@code Open} so another thread can retry.
 *
 * @since 0.1.0
 */
public final class InqEventExporterRegistry {

  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(InqEventExporterRegistry.class);

  private static final long PARK_INITIAL_NANOS = 1_000;           // 1 μs
  private static final long PARK_MAX_NANOS = 1_000_000;       // 1 ms per park
  private static final long RESOLVE_TIMEOUT_NANOS = 30_000_000_000L; // 30 seconds total

  /**
   * Max consecutive hasNext() failures before giving up on remaining providers.
   */
  private static final int MAX_CONSECUTIVE_HAS_NEXT_FAILURES = 10;

  // ── Global default instance ──

  private static final AtomicReference<InqEventExporterRegistry> DEFAULT_INSTANCE = new AtomicReference<>();
  private final AtomicReference<RegistryState> state = new AtomicReference<>(new Open());
  private final ClassLoader spiClassLoader;

  // ── State machine ──

  /**
   * Creates a new, empty registry.
   *
   * <p>Captures the {@link Thread#getContextClassLoader() Thread Context ClassLoader}
   * of the creating thread at construction time. This ClassLoader is used later for
   * ServiceLoader discovery — even if the first event is published by a worker thread
   * whose TCCL may be restricted.
   */
  public InqEventExporterRegistry() {
    var tccl = Thread.currentThread().getContextClassLoader();
    this.spiClassLoader = tccl != null ? tccl : InqEventExporter.class.getClassLoader();
  }

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

  @SuppressWarnings("unchecked")
  private static List<CachedExporter> discoverAndMerge(List<InqEventExporter> programmatic,
                                                       ClassLoader classLoader) {
    var serviceLoaderExporters = new ArrayList<InqEventExporter>();

    try {
      var loader = ServiceLoader.load(InqEventExporter.class, classLoader);
      Iterator<InqEventExporter> iterator = loader.iterator();
      int consecutiveHasNextFailures = 0;
      while (true) {
        boolean hasNext;
        try {
          hasNext = iterator.hasNext();
          consecutiveHasNextFailures = 0; // reset on success
        } catch (Throwable t) {
          rethrowIfFatal(t);
          consecutiveHasNextFailures++;
          LOGGER.warn("ServiceLoader iterator.hasNext() failed for InqEventExporter " +
              "(consecutive failure #{}) — retrying.", consecutiveHasNextFailures, t);
          if (consecutiveHasNextFailures >= MAX_CONSECUTIVE_HAS_NEXT_FAILURES) {
            LOGGER.warn("Giving up after {} consecutive hasNext() failures " +
                "— remaining providers skipped.", MAX_CONSECUTIVE_HAS_NEXT_FAILURES);
            break;
          }
          // Java's ServiceLoader advances its internal cursor past the broken
          // entry — the next hasNext() call attempts the next provider
          continue;
        }
        if (!hasNext) {
          break;
        }
        try {
          serviceLoaderExporters.add(iterator.next());
        } catch (Throwable t) {
          rethrowIfFatal(t);
          LOGGER.warn("Failed to load InqEventExporter provider — provider skipped.", t);
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

    var all = new ArrayList<InqEventExporter>(comparable.size() + nonComparable.size() + programmatic.size());
    all.addAll(comparable);
    all.addAll(nonComparable);
    all.addAll(programmatic);

    // Cache each exporter's subscribed event types at freeze time
    var cached = new ArrayList<CachedExporter>(all.size());
    for (var exporter : all) {
      Class<? extends InqEvent>[] types = null;
      try {
        var typeSet = exporter.subscribedEventTypes();
        if (typeSet != null && !typeSet.isEmpty()) {
          types = typeSet.toArray(new Class[0]);
        }
      } catch (Throwable t) {
        rethrowIfFatal(t);
        LOGGER.warn("InqEventExporter.subscribedEventTypes() threw — exporter will receive all events.", t);
      }
      cached.add(new CachedExporter(exporter, types));
    }
    return List.copyOf(cached);
  }

  private static void rethrowIfFatal(Throwable t) {
    if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
    if (t instanceof LinkageError) throw (LinkageError) t;
  }

  /**
   * Registers an exporter programmatically.
   *
   * <p>Must be called before the first event is exported. Registrations after
   * discovery has started throw {@link IllegalStateException}.
   *
   * @param exporter the exporter to register
   * @throws IllegalStateException if the registry is resolving or already frozen
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
        continue;
      }
      if (current instanceof Resolving) {
        throw new IllegalStateException(
            "InqEventExporterRegistry is currently resolving — " +
                "exporters must be registered before the first event is exported.");
      }
      // Frozen
      throw new IllegalStateException(
          "InqEventExporterRegistry is frozen — " +
              "exporters must be registered before the first event is exported.");
    }
  }

  /**
   * Exports an event to all registered exporters.
   *
   * @param event the event to export
   */
  public void export(InqEvent event) {
    for (var cached : getExporters()) {
      try {
        if (cached.isSubscribed(event)) {
          cached.exporter.export(event);
        }
      } catch (Throwable t) {
        rethrowIfFatal(t);
        LOGGER.warn("[{}] InqEventExporter {} threw on event {}",
            event.getCallId(), cached.exporter.getClass().getName(),
            event.getClass().getSimpleName(), t);
      }
    }
  }

  /**
   * Returns the resolved exporter list, triggering discovery on first access.
   *
   * <p>Only the thread that CAS-es {@code Open → Resolving} performs I/O.
   * Waiting threads park with exponential backoff up to {@code RESOLVE_TIMEOUT_NANOS}.
   * If the resolver thread dies, state resets to {@code Open}. If the timeout
   * is exceeded, the waiter forces a reset and retries.
   */
  private List<CachedExporter> getExporters() {
    long parkNanos = PARK_INITIAL_NANOS;
    long totalParked = 0;
    while (true) {
      var current = state.get();

      if (current instanceof Frozen frozen) {
        return frozen.exporters;
      }

      if (current instanceof Open open) {
        var resolving = new Resolving(open.programmatic);
        if (state.compareAndSet(current, resolving)) {
          try {
            var resolved = discoverAndMerge(open.programmatic, spiClassLoader);
            state.set(new Frozen(resolved));
            return resolved;
          } catch (Throwable t) {
            // Reset so another thread can retry
            state.set(new Open(List.copyOf(open.programmatic)));
            rethrowIfFatal(t);
            LOGGER.error("ServiceLoader discovery failed — registry reset to Open", t);
            return List.of();
          }
        }
        continue;
      }

      // Resolving — park with exponential backoff, bounded by total timeout
      if (totalParked >= RESOLVE_TIMEOUT_NANOS) {
        // Resolver thread is presumably stuck — force reset to Open
        var resolving = (Resolving) current;
        if (state.compareAndSet(current, new Open(List.copyOf(resolving.programmatic)))) {
          LOGGER.warn("Resolver thread appears stuck after {}ms — forced reset to Open",
              totalParked / 1_000_000);
        }
        // Re-read state and retry (either we reset, or another thread did)
        parkNanos = PARK_INITIAL_NANOS;
        totalParked = 0;
        continue;
      }

      LockSupport.parkNanos(parkNanos);
      totalParked += parkNanos;
      parkNanos = Math.min(parkNanos * 2, PARK_MAX_NANOS);
    }
  }

  private sealed interface RegistryState permits Open, Resolving, Frozen {
  }

  private record Open(List<InqEventExporter> programmatic) implements RegistryState {
    Open() {
      this(List.of());
    }
  }

  private record Resolving(List<InqEventExporter> programmatic) implements RegistryState {
  }

  private record Frozen(List<CachedExporter> exporters) implements RegistryState {
  }

  /**
   * Pairs an exporter with its pre-resolved event type filter.
   * {@code subscribedEventTypes()} is called once at freeze time — not on every event.
   */
  private record CachedExporter(InqEventExporter exporter, Class<? extends InqEvent>[] eventTypes) {
    boolean isSubscribed(InqEvent event) {
      if (eventTypes == null || eventTypes.length == 0) {
        return true;
      }
      for (var type : eventTypes) {
        if (type.isInstance(event)) {
          return true;
        }
      }
      return false;
    }
  }
}
