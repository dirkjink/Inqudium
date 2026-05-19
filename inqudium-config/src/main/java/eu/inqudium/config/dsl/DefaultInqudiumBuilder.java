package eu.inqudium.config.dsl;

import eu.inqudium.config.ConfigurationException;
import eu.inqudium.config.runtime.DefaultInqRuntime;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.runtime.ParadigmContainer;
import eu.inqudium.config.runtime.ParadigmUnavailableException;
import eu.inqudium.config.snapshot.ComponentSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.spi.ParadigmProvider;
import eu.inqudium.config.spi.ParadigmSectionPatches;
import eu.inqudium.config.spi.ProviderDiscovery;
import eu.inqudium.config.validation.BuildReport;
import eu.inqudium.config.validation.ConsistencyRule;
import eu.inqudium.config.validation.ConsistencyRulePipeline;
import eu.inqudium.config.validation.CrossComponentRule;
import eu.inqudium.config.validation.ValidationFinding;
import eu.inqudium.core.element.paradigm.ParadigmTag;
import eu.inqudium.core.element.paradigm.SyncTag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Default implementation of {@link InqudiumBuilder}.
 *
 * <p>Accumulates the user's {@code .general(...)} configuration plus per-paradigm sections, then
 * runs the build pipeline in {@link #build()}: discover paradigm providers via
 * {@link ProviderDiscovery}, materialize each declared section through its provider, and assemble
 * the {@link DefaultInqRuntime}. If a paradigm section is declared but no provider is on the
 * classpath, build raises {@link ParadigmUnavailableException} naming the missing module.
 */
public final class DefaultInqudiumBuilder implements InqudiumBuilder {

    private final GeneralSnapshotBuilder generalBuilder = new GeneralSnapshotBuilder();
    private final Map<ParadigmTag, ParadigmProvider> providers;
    private BulkheadPatchAccumulator accumulator;
    private boolean strict;

    public DefaultInqudiumBuilder() {
        this.providers = ProviderDiscovery.providers();
    }

    /**
     * Load every {@link CrossComponentRule} discoverable via {@link ServiceLoader} on the runtime
     * classpath. Iteration order is the {@code ServiceLoader} default — declaration order in each
     * {@code META-INF/services/eu.inqudium.config.validation.CrossComponentRule} file, in the
     * order the loader walks the classpath. Diagnose iterates the rules in this same order so the
     * resulting findings list is deterministic for a given classpath.
     */
    private static List<CrossComponentRule> loadCrossComponentRules() {
        List<CrossComponentRule> result = new ArrayList<>();
        for (CrossComponentRule rule : ServiceLoader.load(CrossComponentRule.class)) {
            result.add(rule);
        }
        return result;
    }

    /**
     * Load every class-3 {@link ConsistencyRule} discoverable via {@link ServiceLoader}.
     */
    private static List<ConsistencyRule<?>> loadConsistencyRules() {
        List<ConsistencyRule<?>> result = new ArrayList<>();
        for (ConsistencyRule<?> rule : ServiceLoader.load(ConsistencyRule.class)) {
            result.add(rule);
        }
        return result;
    }

    @Override
    public InqudiumBuilder general(Consumer<GeneralSnapshotBuilder> configurer) {
        configurer.accept(generalBuilder);
        return this;
    }

    @Override
    public InqudiumBuilder sync(Consumer<SyncSection> configurer) {
        ParadigmProvider provider = requireImperativeProvider();
        configurer.accept(new DefaultSyncSection(ensureAccumulator(), provider));
        return this;
    }

    @Override
    public InqudiumBuilder async(Consumer<AsyncSection> configurer) {
        ParadigmProvider provider = requireImperativeProvider();
        configurer.accept(new DefaultAsyncSection(ensureAccumulator(), provider));
        return this;
    }

    private BulkheadPatchAccumulator ensureAccumulator() {
        if (accumulator == null) {
            accumulator = new BulkheadPatchAccumulator();
        }
        return accumulator;
    }

    private ParadigmProvider requireImperativeProvider() {
        ParadigmProvider provider = providers.get(SyncTag.INSTANCE);
        if (provider == null) {
            throw new ParadigmUnavailableException(
                    "The 'sync' / 'async' paradigms require module 'inqudium-imperative' "
                            + "on the classpath. Add it as a dependency or remove "
                            + ".sync(...) / .async(...) sections from your configuration.");
        }
        return provider;
    }

    @Override
    public InqudiumBuilder strict() {
        this.strict = true;
        return this;
    }

    @Override
    public InqRuntime build() {
        GeneralSnapshot general = generalBuilder.build();

        Map<ParadigmTag, ParadigmContainer<?>> containers = new LinkedHashMap<>();

        // Imperative paradigm: materialize from accumulated patches, or supply an empty
        // container when the provider is on the classpath but no .sync(...) / .async(...)
        // was declared. Per ADR-026, "an empty paradigm is a normal state".
        ParadigmProvider imperativeProvider = providers.get(SyncTag.INSTANCE);
        if (imperativeProvider != null) {
            ParadigmSectionPatches patches = accumulator != null
                    ? accumulator.finish()
                    : new ParadigmSectionPatches(Map.of());
            containers.put(
                    SyncTag.INSTANCE,
                    imperativeProvider.createContainer(general, patches));
        }

        // Class-3 validation.
        Stream<? extends ComponentSnapshot> snapshots = containers.values().stream()
                .flatMap(ParadigmContainer::snapshots);
        List<ValidationFinding> findings =
                ConsistencyRulePipeline.apply(snapshots, loadConsistencyRules());
        if (strict) {
            findings = ConsistencyRulePipeline.elevateWarningsToErrors(findings);
        }
        BuildReport report = new BuildReport(
                Instant.now(), findings, List.of(), Map.of());
        if (!report.isSuccess()) {
            throw new ConfigurationException(report);
        }

        return new DefaultInqRuntime(
                general, containers, providers, report, loadCrossComponentRules());
    }

    /**
     * @return whether {@link #strict()} was called. Exposed for tests and phase-2 wiring.
     */
    public boolean isStrict() {
        return strict;
    }
}
