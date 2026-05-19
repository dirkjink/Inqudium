package eu.inqudium.config.dsl;

import eu.inqudium.config.runtime.ParadigmUnavailableException;
import eu.inqudium.config.spi.ParadigmProvider;
import eu.inqudium.config.spi.ParadigmSectionPatches;
import eu.inqudium.core.element.paradigm.ParadigmTag;
import eu.inqudium.core.element.paradigm.SyncTag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Default implementation of {@link InqudiumUpdateBuilder}.
 *
 * <p>Re-uses {@link BulkheadPatchAccumulator} for patch accumulation — the update path uses
 * the same DSL section types as initial configuration (per ADR-025: same builders, only the
 * starting snapshot differs). After the user's lambda finishes, {@link #toSectionPatches()}
 * yields the accumulated per-paradigm patches that {@code runtime.update} dispatches to each
 * paradigm container's {@code applyUpdate} entry point.
 *
 * <h2>Provider vs. container — two distinct concerns</h2>
 *
 * <p>The update builder accumulates <em>patches</em> and never owns or touches a live component.
 * The split is deliberate:
 *
 * <ul>
 *   <li>{@link ParadigmProvider} — paradigm-side DSL factory and materialization recipe. The
 *       update builder holds the provider map so the same provider that built the runtime also
 *       drives the update DSL — one source of truth for paradigm-specific factories.</li>
 *   <li>{@link eu.inqudium.config.runtime.ParadigmContainer ParadigmContainer} — runtime-side
 *       live registry of components. Owned by the {@code InqRuntime}; the update builder never
 *       references it.</li>
 * </ul>
 */
public final class DefaultInqudiumUpdateBuilder implements InqudiumUpdateBuilder {

    private final Map<ParadigmTag, ParadigmProvider> providers;
    private BulkheadPatchAccumulator accumulator;

    public DefaultInqudiumUpdateBuilder(Map<ParadigmTag, ParadigmProvider> providers) {
        this.providers = providers;
    }

    @Override
    public InqudiumUpdateBuilder sync(Consumer<SyncSection> configurer) {
        ParadigmProvider provider = requireImperativeProvider();
        configurer.accept(new DefaultSyncSection(ensureAccumulator(), provider));
        return this;
    }

    @Override
    public InqudiumUpdateBuilder async(Consumer<AsyncSection> configurer) {
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
                            + "on the classpath.");
        }
        return provider;
    }

    /**
     * @return the accumulated patches grouped by paradigm tag, in declaration order.
     */
    public Map<ParadigmTag, ParadigmSectionPatches> toSectionPatches() {
        Map<ParadigmTag, ParadigmSectionPatches> result = new LinkedHashMap<>();
        if (accumulator != null) {
            result.put(SyncTag.INSTANCE, accumulator.finish());
        }
        return result;
    }
}
