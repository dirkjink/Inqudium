package eu.inqudium.imperative.runtime;

import eu.inqudium.config.dsl.BulkheadBuilderBase;
import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.patch.BulkheadPatch;
import eu.inqudium.config.runtime.ParadigmContainer;
import eu.inqudium.config.runtime.UpdateDispatcher;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.config.spi.ParadigmProvider;
import eu.inqudium.config.spi.ParadigmSectionPatches;
import eu.inqudium.core.paradigm.ParadigmTag;
import eu.inqudium.core.paradigm.SyncTag;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import eu.inqudium.imperative.bulkhead.dsl.DefaultAsyncBulkheadBuilder;
import eu.inqudium.imperative.bulkhead.dsl.DefaultSyncBulkheadBuilder;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The sync-paradigm provider materialising bulkhead containers for the imperative
 * dispatch path. Discovered by {@link eu.inqudium.config.spi.ProviderDiscovery}
 * via direct class-loading probe — no SPI registration, mirroring the
 * {@code DetectionAsync} pattern from ADR-037.
 *
 * <p>Provides:
 *
 * <ul>
 *   <li>The paradigm tag, {@link SyncTag#INSTANCE} — the sync paradigm is the
 *       primary identity of the imperative module's container (ADR-046),</li>
 *   <li>The sync and async bulkhead-builder factories used by the DSL section, and</li>
 *   <li>The {@link DefaultImperative} container assembly that takes a paradigm-section's worth
 *       of {@link BulkheadPatch} instances plus the {@link GeneralSnapshot} and produces the
 *       live {@code InqBulkhead} components.</li>
 * </ul>
 *
 * <p>Also exposes the package-private {@link #materializeBulkhead} helper that the
 * {@link DefaultImperative#applyUpdate} path uses to build new components when an update
 * introduces a previously-unknown name.</p>
 *
 * <p>Functionally, this provider materialises components that serve both the sync
 * and async dispatch paths through a single {@link InqBulkhead} backing instance per name
 * (façade design: Q.5a runtime split into {@code Sync} / {@code Async} typed views over one
 * registry).</p>
 */
public final class SyncParadigmProvider implements ParadigmProvider {

    /**
     * System-default snapshot used as the apply-base for incoming bulkhead patches. Touched
     * fields take the patch's value; untouched fields fall back to these defaults — currently
     * the same baseline as the {@code balanced} preset to keep "user wrote nothing" behaviour
     * predictable.
     *
     * @param name the bulkhead's name; the patch's {@code NAME} field touch overrides this on
     *             apply, so the placeholder here is never observable.
     */
    private static BulkheadSnapshot defaultSnapshot(String name) {
        return new BulkheadSnapshot(
                name, 50, Duration.ofMillis(500), Set.of(), null,
                BulkheadEventConfig.disabled(), new SemaphoreStrategyConfig());
    }

    @Override
    public ParadigmTag paradigm() {
        return SyncTag.INSTANCE;
    }

    @Override
    public BulkheadBuilderBase<?> createSyncBulkheadBuilder(String name) {
        return new DefaultSyncBulkheadBuilder(name);
    }

    @Override
    public BulkheadBuilderBase<?> createAsyncBulkheadBuilder(String name) {
        return new DefaultAsyncBulkheadBuilder(name);
    }

    @Override
    public ParadigmContainer<?> createContainer(
            GeneralSnapshot general, ParadigmSectionPatches patches) {
        Map<String, DefaultImperative.Entry> entries = new LinkedHashMap<>();
        for (Map.Entry<String, BulkheadPatch> entry : patches.bulkheadPatches().entrySet()) {
            entries.put(entry.getKey(),
                    materializeBulkhead(general, entry.getKey(), entry.getValue()));
        }
        // Wire the dispatcher's LoggerFactory through from the runtime's general snapshot so
        // listener / internal-check throws absorbed by the dispatcher are routed to the same
        // log destination the rest of the framework uses.
        return new DefaultImperative(this, entries,
                new UpdateDispatcher(general.loggerFactory()));
    }

    /**
     * Materialize a single bulkhead from a default snapshot + patch. Used both at initial
     * container construction time (in {@link #createContainer}) and at runtime-update time
     * (from {@link DefaultImperative#applyUpdate}) when a previously-unknown name appears.
     *
     * @param general the runtime-level snapshot supplying clock and event publisher.
     * @param name    the bulkhead's name.
     * @param patch   the patch describing the user's configuration.
     * @return the live entry pairing the new bulkhead with its backing live container.
     */
    DefaultImperative.Entry materializeBulkhead(
            GeneralSnapshot general, String name, BulkheadPatch patch) {
        BulkheadSnapshot initial = patch.applyTo(defaultSnapshot(name));
        LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(initial);
        InqBulkhead<?, ?> bulkhead = new InqBulkhead<>(live, general);
        return new DefaultImperative.Entry(bulkhead, live);
    }

    /**
     * Validate a hypothetical bulkhead materialization without registering anything. Used by the
     * {@link DefaultImperative#dryRunUpdate dryRunUpdate} path to confirm the patch yields a
     * well-formed snapshot — the snapshot's compact constructor enforces every class-2
     * invariant — before reporting {@link eu.inqudium.config.validation.ApplyOutcome#ADDED ADDED}
     * to the caller. The snapshot is constructed and immediately discarded; no live container,
     * bulkhead handle, component publisher, or subscription is created.
     *
     * @param name  the bulkhead's name.
     * @param patch the patch describing the user's configuration.
     * @throws IllegalArgumentException if the patch produces a snapshot that violates any class-2
     *                                  invariant.
     */
    void dryMaterializeBulkhead(String name, BulkheadPatch patch) {
        BulkheadSnapshot ignored = patch.applyTo(defaultSnapshot(name));
    }
}
