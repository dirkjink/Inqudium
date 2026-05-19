package eu.inqudium.config.dsl;

import eu.inqudium.config.patch.BulkheadPatch;
import eu.inqudium.config.spi.ParadigmProvider;
import eu.inqudium.config.spi.ParadigmSectionPatches;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Default implementation of {@link ImperativeSection}.
 *
 * <p>Accumulates a name-keyed map of {@link BulkheadPatch} instances and a name-set of
 * structural removals as the user calls {@code .bulkhead("name", ...)} and
 * {@code .removeBulkhead("name")}. The two collections are kept mutually exclusive per name
 * within one traversal: a {@code removeBulkhead} call rescinds any prior {@code bulkhead}
 * configuration for that name, and a {@code bulkhead} call rescinds any prior
 * {@code removeBulkhead}. Last writer wins, in line with ADR-026.
 *
 * <p>Builders for each {@code bulkhead(...)} call come from the
 * {@link ParadigmProvider#createBulkheadBuilder(String)} factory; the section casts each
 * returned builder to {@link ImperativeBulkheadBuilder} before handing it to the user's
 * configurer.
 */
public final class DefaultImperativeSection implements ImperativeSection {

    private final ParadigmProvider provider;
    private final Map<String, BulkheadPatch> bulkheadPatches = new LinkedHashMap<>();
    private final Set<String> bulkheadRemovals = new LinkedHashSet<>();

    /**
     * @param provider the imperative paradigm provider supplying bulkhead builders.
     */
    public DefaultImperativeSection(ParadigmProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public ImperativeSection bulkhead(String name, Consumer<ImperativeBulkheadBuilder> configurer) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(configurer, "configurer");
        BulkheadBuilderBase<?> base = provider.createBulkheadBuilder(name);
        // The cast is safe by SPI contract: the imperative provider returns a builder that
        // implements ImperativeBulkheadBuilder.
        ImperativeBulkheadBuilder builder = (ImperativeBulkheadBuilder) base;
        configurer.accept(builder);
        applyBulkheadPatch(name, base.toPatch());
        return this;
    }

    @Override
    public ImperativeSection removeBulkhead(String name) {
        markBulkheadRemoval(name);
        return this;
    }

    /**
     * Apply a fully-formed {@link BulkheadPatch} into the shared
     * accumulator under the given name and rescind any prior removal
     * for it. Used by {@link DefaultSyncSection} and
     * {@link DefaultAsyncSection} to feed their builders' patches into
     * the same accumulator as {@link ImperativeSection} (ADR-046 / Q.5b:
     * the three DSL surfaces are independent inputs that share one
     * underlying patch state).
     *
     * <p>Package-private — only sibling section classes call this.</p>
     *
     * @since 0.9.0
     */
    void applyBulkheadPatch(String name, BulkheadPatch patch) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(patch, "patch");
        bulkheadPatches.put(name, patch);
        bulkheadRemovals.remove(name);
    }

    /**
     * Mark the named bulkhead for structural removal in the shared
     * accumulator, rescinding any prior {@code bulkhead(...)} call.
     * Used by sibling sections via the same shared-state pattern as
     * {@link #applyBulkheadPatch(String, BulkheadPatch)}.
     *
     * <p>Package-private — only sibling section classes call this.</p>
     *
     * @since 0.9.0
     */
    void markBulkheadRemoval(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        bulkheadPatches.remove(name);
        bulkheadRemovals.add(name);
    }

    /**
     * @return the accumulated patches and removals, frozen into a {@link ParadigmSectionPatches}.
     */
    public ParadigmSectionPatches finish() {
        return new ParadigmSectionPatches(bulkheadPatches, bulkheadRemovals);
    }
}
