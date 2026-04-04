package eu.inqudium.core.pipeline;

import java.util.concurrent.atomic.AtomicLong;

import static eu.inqudium.core.pipeline.ChainIdGenerator.CHAIN_ID_COUNTER;

/**
 * Package-private ID generator for standalone executions (not part of a wrapper chain).
 * Uses static counters — one CAS per call, zero object allocation.
 */
public final class StandaloneIdGenerator {

  private static final AtomicLong CALL_ID_COUNTER = new AtomicLong();

  private StandaloneIdGenerator() {
  }

  public static long nextChainId() {
    return CHAIN_ID_COUNTER.incrementAndGet();
  }

  public static long nextCallId() {
    return CALL_ID_COUNTER.incrementAndGet();
  }
}
