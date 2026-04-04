package eu.inqudium.core.pipeline;

import java.util.concurrent.atomic.AtomicLong;

public final class ChainIdGenerator {
  /**
   * Global counter for chain IDs — unique per JVM, monotonically increasing.
   */
  public static final AtomicLong CHAIN_ID_COUNTER = new AtomicLong();

  private ChainIdGenerator() {}
}
