package eu.inqudium.config.dsl;

import eu.inqudium.config.patch.BulkheadPatch;
import eu.inqudium.config.spi.ParadigmSectionPatches;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared mutable state for the per-paradigm DSL sections
 * ({@link DefaultSyncSection}, {@link DefaultAsyncSection}). Both
 * sections feed their builders' {@link BulkheadPatch} output into the
 * same accumulator so that {@code .sync(...).bulkhead("foo", ...)}
 * and {@code .async(...).bulkhead("foo", ...)} configure one
 * underlying component (Q.5a façade design — one backing instance,
 * multiple typed views).
 *
 * <p>Per ADR-026, operations on the same name within one builder
 * traversal collapse to the last writer: a {@code bulkhead(...)}
 * call rescinds a prior {@code removeBulkhead(...)} for that name,
 * and vice versa.</p>
 *
 * <p>Package-private — only DSL sections and the top-level builders
 * touch this class.</p>
 *
 * @since 0.10.0
 */
final class BulkheadPatchAccumulator {

    private final Map<String, BulkheadPatch> patches = new LinkedHashMap<>();
    private final Set<String> removals = new LinkedHashSet<>();

    void applyBulkheadPatch(String name, BulkheadPatch patch) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(patch, "patch");
        patches.put(name, patch);
        removals.remove(name);
    }

    void markBulkheadRemoval(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        patches.remove(name);
        removals.add(name);
    }

    ParadigmSectionPatches finish() {
        return new ParadigmSectionPatches(patches, removals);
    }
}
