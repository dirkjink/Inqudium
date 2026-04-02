package eu.inqudium.imperative.bulkhead.config;

import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfigBuilder;
import eu.inqudium.imperative.bulkhead.strategy.SemaphoreBulkheadStrategy;

import java.time.Duration;

public class InqImperativeBulkheadConfigBuilder
    extends InqBulkheadConfigBuilder<InqImperativeBulkheadConfigBuilder,
    InqImperativeBulkheadConfig> {

  InqImperativeBulkheadConfigBuilder() {
  }

  public static InqImperativeBulkheadConfigBuilder bulkhead() {
    return standard();
  }

  public static InqImperativeBulkheadConfigBuilder standard() {
    int maxConcurrentCalls = 25;
    return new InqImperativeBulkheadConfigBuilder()
        .maxConcurrentCalls(maxConcurrentCalls)
        .maxWaitDuration(Duration.ZERO)
        .strategy(new SemaphoreBulkheadStrategy(maxConcurrentCalls));
  }

  @Override
  public InqImperativeBulkheadConfig build() {
    return new InqImperativeBulkheadConfig(generalConfig, common()).inference();
  }

  @Override
  protected InqImperativeBulkheadConfigBuilder self() {
    return this;
  }
}
