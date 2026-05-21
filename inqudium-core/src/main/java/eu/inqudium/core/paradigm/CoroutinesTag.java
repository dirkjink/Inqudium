package eu.inqudium.core.paradigm;

/**
 * The Kotlin coroutines paradigm. Sealed sub-family with four
 * permitted concrete tags, one per resilience-relevant shape:
 *
 * <ul>
 *   <li>{@link CoroutinesSuspendTag} — {@code suspend fun}
 *       methods detected by the {@code Continuation} parameter
 *       on the JVM-level signature.</li>
 *   <li>{@link CoroutinesDeferredTag} — methods returning
 *       {@code kotlinx.coroutines.Deferred<T>}; awaitable
 *       handle.</li>
 *   <li>{@link CoroutinesJobTag} — methods returning
 *       {@code kotlinx.coroutines.Job}; lifecycle handle
 *       without result.</li>
 *   <li>{@link CoroutinesFlowTag} — methods returning
 *       {@code kotlinx.coroutines.flow.Flow<T>}; cold stream.</li>
 * </ul>
 *
 * <p>Permit-release happens at completion via the matching hook
 * ({@code finally} for {@code suspend},
 * {@code invokeOnCompletion} for {@code Deferred}/{@code Job},
 * {@code onCompletion} for {@code Flow}).</p>
 */
public sealed interface CoroutinesTag extends ParadigmTag
        permits CoroutinesSuspendTag, CoroutinesDeferredTag,
                CoroutinesJobTag, CoroutinesFlowTag {

    /** The {@code suspend fun} sub-shape. */
    CoroutinesSuspendTag SUSPEND = CoroutinesSuspendTagDefault.INSTANCE;

    /** The {@code Deferred<T>} sub-shape. */
    CoroutinesDeferredTag DEFERRED = CoroutinesDeferredTagDefault.INSTANCE;

    /** The {@code Job} sub-shape. */
    CoroutinesJobTag JOB = CoroutinesJobTagDefault.INSTANCE;

    /** The {@code Flow<T>} sub-shape. */
    CoroutinesFlowTag FLOW = CoroutinesFlowTagDefault.INSTANCE;
}
