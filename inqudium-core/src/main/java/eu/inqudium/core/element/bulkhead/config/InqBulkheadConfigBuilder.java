package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.ExtensionBuilder;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;

public abstract class InqBulkheadConfigBuilder
    <B extends InqBulkheadConfigBuilder<B, E>, E extends ConfigExtension>
    extends ExtensionBuilder<E> {
  protected GeneralConfig generalConfig;
  private String name;
  private InqEventPublisher eventPublisher;
  private BulkheadStrategy strategy;
  private int maxConcurrentCalls;
  private Duration maxWaitDuration;
  private InqLimitAlgorithm limitAlgorithm;

  @Override
  protected void general(GeneralConfig generalConfig) {
    this.generalConfig = generalConfig;
  }

  protected InqBulkheadConfigBuilder() {}

  public B name(String name) {
    this.name = name;
    return (B) this;
  }

  public B strategy(BulkheadStrategy strategy) {
    this.strategy = strategy;
    return (B) this;
  }

  public B maxWaitDuration(Duration maxWaitDuration) {
    this.maxWaitDuration = maxWaitDuration;
    return (B) this;
  }

  public B maxConcurrentCalls(int maxConcurrentCalls) {
    this.maxConcurrentCalls = maxConcurrentCalls;
    return (B) this;
  }

  public B limitAlgorithm(InqLimitAlgorithm limitAlgorithm) {
    this.limitAlgorithm = limitAlgorithm;
    return (B) this;
  }

  public B eventPublisher(InqEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
    return (B) this;
  }

  protected InqBulkheadConfig common() {
    return new InqBulkheadConfig(
        this.generalConfig,
        name,
        InqElementType.BULKHEAD,
        eventPublisher,
        maxConcurrentCalls,
        strategy,
        maxWaitDuration,
        limitAlgorithm
    );
  }
}
