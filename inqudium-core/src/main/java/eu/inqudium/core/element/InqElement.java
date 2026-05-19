package eu.inqudium.core.element;

import eu.inqudium.core.event.InqEventPublisher;

/**
 * Base interface implemented by all resilience elements across all paradigms.
 *
 * <p>Every element has a name (used for registry lookup and event correlation),
 * a type (used for pipeline ordering and event identification), and an event
 * publisher (used for observability).
 *
 * @since 0.1.0
 */
public interface InqElement {

    /**
     * Returns the name of this element instance.
     *
     * <p>The name is unique within a registry (ADR-015) and appears in all
     * events (ADR-003) and exceptions (ADR-009) emitted by this element.
     *
     * @return the instance name, e.g. "paymentService"
     */
    String name();

    /**
     * Returns the element type.
     *
     * @return the element kind
     */
    InqElementType elementType();

    /**
     * Returns the event publisher for this element instance.
     *
     * <p>Consumers subscribe via this publisher to receive events from this
     * specific element (ADR-003).
     *
     * @return the per-instance event publisher
     */
    InqEventPublisher eventPublisher();

    /**
     * Element-kind marker interfaces. Components implement
     * {@link Kind.Bulkhead}, {@link Kind.CircuitBreaker}, etc. to declare
     * their kind in the type system, parallel to the runtime
     * {@code elementType()} accessor.
     *
     * <p>Used as lower bounds for handle {@code target()} accessors to
     * ensure callers receive a value that is structurally of the expected
     * element kind, not an arbitrary element.</p>
     *
     * <p>The two-level nesting ({@code InqElement.Kind.<Name>}) avoids name
     * collisions with same-named top-level legacy types (e.g.
     * {@code eu.inqudium.imperative.bulkhead.Bulkhead<A, R>}) that would
     * otherwise be shadowed in subinterfaces of {@code InqElement}.</p>
     *
     * <p>Implementations of these markers need not override
     * {@link InqElement#elementType()}; each marker provides a default
     * that returns the matching {@link InqElementType}.</p>
     */
    interface Kind {

        /**
         * Marker for {@code InqElement} implementations of type
         * {@link InqElementType#BULKHEAD}.
         */
        interface Bulkhead extends InqElement {
            @Override
            default InqElementType elementType() {
                return InqElementType.BULKHEAD;
            }
        }

        /**
         * Marker for {@code InqElement} implementations of type
         * {@link InqElementType#CIRCUIT_BREAKER}.
         */
        interface CircuitBreaker extends InqElement {
            @Override
            default InqElementType elementType() {
                return InqElementType.CIRCUIT_BREAKER;
            }
        }

        /**
         * Marker for {@code InqElement} implementations of type
         * {@link InqElementType#RETRY}.
         */
        interface Retry extends InqElement {
            @Override
            default InqElementType elementType() {
                return InqElementType.RETRY;
            }
        }

        /**
         * Marker for {@code InqElement} implementations of type
         * {@link InqElementType#TIME_LIMITER}.
         */
        interface TimeLimiter extends InqElement {
            @Override
            default InqElementType elementType() {
                return InqElementType.TIME_LIMITER;
            }
        }

        /**
         * Marker for {@code InqElement} implementations of type
         * {@link InqElementType#RATE_LIMITER}.
         */
        interface RateLimiter extends InqElement {
            @Override
            default InqElementType elementType() {
                return InqElementType.RATE_LIMITER;
            }
        }

        /**
         * Marker for {@code InqElement} implementations of type
         * {@link InqElementType#TRAFFIC_SHAPER}.
         */
        interface TrafficShaper extends InqElement {
            @Override
            default InqElementType elementType() {
                return InqElementType.TRAFFIC_SHAPER;
            }
        }
    }
}
