package eu.inqudium.proxy.entries;

import eu.inqudium.proxy.folding.FoldedSyncChain;
import eu.inqudium.proxy.handler.InqInvocationHandler;

import java.util.List;
import java.util.Objects;

/**
 * Dispatches a synchronous service method through its pre-folded
 * resilience chain. Used when the annotation evaluator's plan is
 * {@code MethodPlan.Decorated} and the method's return type is
 * synchronous (i.e. not {@code CompletionStage}, not {@code Mono}).
 *
 * <p>Per-call work: pull {@code callId} from the handler, call
 * {@link FoldedSyncChain#run}. All folding work happened once at
 * proxy-construction time.</p>
 *
 * <p>Package-private — proxy code constructs these via the
 * {@link MethodDispatchEntry#syncCache(FoldedSyncChain, List)}
 * static factory.</p>
 */
record SyncCacheEntry(FoldedSyncChain chain, List<String> layerDescriptions)
        implements MethodDispatchEntry {

    SyncCacheEntry {
        Objects.requireNonNull(chain, "chain");
        layerDescriptions = List.copyOf(
                Objects.requireNonNull(layerDescriptions, "layerDescriptions"));
    }

    @Override
    public Object dispatch(Object proxy, InqInvocationHandler handler, Object[] args)
            throws Throwable {
        return chain.run(handler.stackId(), handler.nextCallId(), args);
    }
}
