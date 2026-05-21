package eu.inqudium.core.element;

import eu.inqudium.core.paradigm.AsyncTag;
import eu.inqudium.core.paradigm.ParadigmTag;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.paradigm.ReactiveFluxTag;
import eu.inqudium.core.paradigm.ReactiveMonoTag;
import eu.inqudium.core.paradigm.SyncTag;

import java.util.Set;

/**
 * Base interface implemented by all resilience elements across all paradigms.
 *
 * <p>Every element has a name (used for registry lookup and event correlation),
 * a type (used for pipeline ordering and event identification), an event
 * publisher (used for observability), and an explicit set of paradigm tags
 * (used by pipeline reference validation to confirm that an annotation-driven
 * plan can be wrapped by this element).</p>
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
     * Returns the paradigm tags this element supports.
     *
     * <p>Every concrete element declares explicitly which paradigms it can
     * wrap. A bulkhead that handles both synchronous and asynchronous calls
     * returns both {@link SyncTag SyncTag}
     * and {@link AsyncTag AsyncTag}; a
     * future reactive bulkhead returns
     * {@link ReactiveMonoTag ReactiveMonoTag}
     * and {@link ReactiveFluxTag ReactiveFluxTag};
     * a synchronous-only traffic shaper returns just {@code SyncTag}.</p>
     *
     * <p>This is an abstract method by design: every element must declare
     * its paradigm coverage. There is no sensible default — a "paradigm-less"
     * element cannot be wrapped by any annotation, and silent misclassification
     * would defer the problem to runtime stack traces. The compile-time error
     * from omitting this method is the correct enforcement.</p>
     *
     * <p>The annotation evaluator builds plans referring to elements by
     * {@code (elementType, name)} pair plus a per-method
     * {@link ParadigmTag}. Pipeline reference validation checks that each
     * reference resolves to an element whose {@link #paradigmTags()} contains
     * the method's paradigm.</p>
     *
     * @return the immutable set of paradigm tags this element supports;
     *         never {@code null}, never empty in production code (an empty
     *         set is legal only for deprecated/test fixtures and results in
     *         the element being unreachable from any annotation-driven plan)
     *
     * @since 0.10.0
     */
    Set<ParadigmTag> paradigmTags();

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
