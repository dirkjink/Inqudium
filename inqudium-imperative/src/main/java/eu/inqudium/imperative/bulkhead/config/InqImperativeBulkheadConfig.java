package eu.inqudium.imperative.bulkhead.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfig;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;

public record InqImperativeBulkheadConfig(
    GeneralConfig general,
    InqBulkheadConfig common
) implements ConfigExtension<InqImperativeBulkheadConfig>, InqElementConfig {
  @Override
  public String name() {
    return common.name();
  }

  public BulkheadStrategy strategy() {
    return common.strategy();
  }

  public int maxConcurrentCalls() {
    return common.maxConcurrentCalls();
  }

  public Duration maxWaitDuration() {
    return common.maxWaitDuration();
  }

  public InqLimitAlgorithm limitAlgorithm() {
    return common.limitAlgorithm();
  }

  @Override
  public InqElementType elementType() {
    return common.elementType();
  }

  @Override
  public InqEventPublisher eventPublisher() {
    return common.eventPublisher();
  }

  @Override
  public InqImperativeBulkheadConfig self() {
    return this;
  }
}

