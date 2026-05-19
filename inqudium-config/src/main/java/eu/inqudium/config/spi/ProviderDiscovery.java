package eu.inqudium.config.spi;

import eu.inqudium.core.element.paradigm.ParadigmTag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Discovers paradigm providers by direct class-loading probes,
 * mirroring the {@code DetectionAsync} pattern (ADR-037). Each
 * known paradigm module's provider class is probed at class-init
 * time; absent modules contribute nothing.
 *
 * <p>This replaces the previous {@code ServiceLoader<ParadigmProvider>}
 * mechanism. SPI's purpose is to load unknown future
 * implementations; the library's paradigm modules (imperative,
 * reactor, rxjava3, coroutines) are known at compile time of
 * this module, so presence-probe is the architecturally
 * consistent choice and matches how {@code DetectionAsync}
 * (and similar probes in {@code inqudium-annotation}) handle
 * the same question.</p>
 *
 * <p>The discovered providers are computed once at class load
 * and cached. Each probe uses
 * {@link Class#forName(String, boolean, ClassLoader)} with
 * {@code initialize=false} — the FQN is resolved but the
 * class's static initialiser does not run until needed. If a
 * probed class is not on the classpath,
 * {@link ClassNotFoundException} is caught and the probe
 * contributes nothing. If a probed class exists but cannot be
 * instantiated (no public no-arg constructor), it's a
 * configuration error and escalates as
 * {@link IllegalStateException}.</p>
 *
 * <p>Adding a new paradigm provider when a future module ships:
 * one new {@code probeX()} method per module + one line in
 * {@link #discoverProviders()}.</p>
 *
 * @since 0.10.0
 */
public final class ProviderDiscovery {

    private static final Map<ParadigmTag, ParadigmProvider> PROVIDERS =
            discoverProviders();

    private ProviderDiscovery() {
    }

    /**
     * Returns the map of discovered paradigm providers, keyed
     * by their paradigm tag. Iteration order is fixed: sync
     * first, then async, then reactive, rxjava3, coroutines
     * as those modules ship.
     *
     * @return an immutable map; never null but possibly empty
     *         if no paradigm modules are on the classpath.
     */
    public static Map<ParadigmTag, ParadigmProvider> providers() {
        return PROVIDERS;
    }

    private static Map<ParadigmTag, ParadigmProvider> discoverProviders() {
        Map<ParadigmTag, ParadigmProvider> result = new LinkedHashMap<>();
        probeImperative().ifPresent(p -> result.put(p.paradigm(), p));
        // Future paradigm modules: add one probeX() per module.
        // probeReactor().ifPresent(p -> result.put(p.paradigm(), p));
        // probeRxJava3().ifPresent(p -> result.put(p.paradigm(), p));
        // probeCoroutines().ifPresent(p -> result.put(p.paradigm(), p));
        return Map.copyOf(result);
    }

    private static Optional<ParadigmProvider> probeImperative() {
        return instantiateProvider(
                "eu.inqudium.imperative.runtime.SyncParadigmProvider");
    }

    /**
     * Probe-and-instantiate a paradigm provider by FQN. Two-step
     * pattern (analogous to {@code DetectionAsync} in
     * {@code inqudium-proxy}, ADR-037):
     * <ol>
     *   <li>Probe existence via {@code Class.forName(fqn, false, loader)}.
     *       Returns {@link Optional#empty()} if the class is not on
     *       the classpath (its module is not a runtime dependency).</li>
     *   <li>Load and instantiate via the public no-arg constructor.
     *       Escalates {@link IllegalStateException} for instantiation
     *       failures (configuration errors, not classpath conditions).</li>
     * </ol>
     */
    private static Optional<ParadigmProvider> instantiateProvider(String fqn) {
        try {
            Class.forName(fqn, false, ProviderDiscovery.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }

        try {
            Class<? extends ParadigmProvider> providerClass =
                    Class.forName(fqn).asSubclass(ParadigmProvider.class);
            return Optional.of(providerClass.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Paradigm provider class " + fqn + " is on the classpath but "
                            + "cannot be instantiated. Provider classes must have a public "
                            + "no-arg constructor and implement " + ParadigmProvider.class.getName()
                            + ".",
                    e);
        } catch (ClassCastException e) {
            throw new IllegalStateException(
                    "Class " + fqn + " is on the classpath but does not implement "
                            + ParadigmProvider.class.getName() + ".",
                    e);
        }
    }
}
