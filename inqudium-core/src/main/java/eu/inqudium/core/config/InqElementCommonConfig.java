package eu.inqudium.core.config;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;

public record InqElementCommonConfig(
    String name,
    InqElementType elementType,
    InqEventPublisher eventPublisher
) implements InqElementConfig, ConfigExtension<InqElementCommonConfig> {

  @Override
  public InqElementCommonConfig inference() {
    InqEventPublisher eventPublisherDerived = eventPublisher;
    if (eventPublisher == null) {
      eventPublisherDerived = InqEventPublisher.create(name, elementType);
    }
    return new InqElementCommonConfig(name, elementType, eventPublisherDerived);
  }

  @Override
  public InqElementCommonConfig self() {
    return this;
  }
}

