package eu.inqudium.core.config;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;

import java.util.concurrent.atomic.AtomicInteger;

public record InqElementCommonConfig(
    String name,
    InqElementType elementType,
    InqEventPublisher eventPublisher,
    Boolean enableExceptionOptimization
) implements InqElementConfig, ConfigExtension<InqElementCommonConfig> {

  private final static AtomicInteger counter = new AtomicInteger(1);

  @Override
  public InqElementCommonConfig inference() {
    String nameInference = name;
    if (name == null) {
      nameInference = elementType.name() + "-" + counter.getAndIncrement();
    }

    InqEventPublisher eventPublisherInference = eventPublisher;
    if (eventPublisher == null) {
      eventPublisherInference = InqEventPublisher.create(nameInference, elementType);
    }

    Boolean enableExceptionOptimizationInference = enableExceptionOptimization;
    if (enableExceptionOptimization == null) {
      enableExceptionOptimizationInference = true;
    }

    return new InqElementCommonConfig(nameInference,
        elementType,
        eventPublisherInference,
        enableExceptionOptimizationInference);
  }

  @Override
  public InqElementCommonConfig self() {
    return this;
  }
}

