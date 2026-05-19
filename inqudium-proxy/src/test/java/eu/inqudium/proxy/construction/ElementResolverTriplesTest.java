package eu.inqudium.proxy.construction;

import eu.inqudium.annotation.evaluator.ElementRef;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.pipeline.InqPipeline;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the triple-keyed resolver helpers introduced by ADR-046
 * ({@code indexByTypeAndName}, {@code resolveTriples}, and the
 * {@code ElementTypeAndName} key record). Mirrors the structure of
 * {@link ElementResolverTest} so the name-only and triple-keyed
 * resolver paths are exercised side by side.
 *
 * <p>The cross-type name-collision tests pin the dissolution of
 * finding 1.1 from {@code REFACTORING_PROXY_POLISH.md}: keying the
 * pipeline index on the {@code (elementType, name)} pair makes
 * duplicate names across element types inherently safe.</p>
 */
class ElementResolverTriplesTest {

    @Nested
    class IndexByTypeAndName {

        @Test
        void should_index_a_single_element_under_its_type_and_name_pair() {
            // Given
            FakeDecorator bh = new FakeDecorator("foo", InqElementType.BULKHEAD);
            InqPipeline pipeline = InqPipeline.builder().shield(bh).build();

            // When
            Map<ElementResolver.ElementTypeAndName, InqElement> index =
                    ElementResolver.indexByTypeAndName(pipeline);

            // Then
            assertThat(index).hasSize(1);
            assertThat(index).containsEntry(
                    new ElementResolver.ElementTypeAndName(
                            InqElementType.BULKHEAD, "foo"),
                    bh);
        }

        @Test
        void should_accept_two_elements_with_the_same_name_but_different_types() {
            // What is to be tested? — Finding 1.1 from
            //   REFACTORING_PROXY_POLISH.md describes the legacy
            //   indexByName(...) crashing with a Collectors.toMap merge
            //   when two pipeline elements share a name across element
            //   types. The new indexByTypeAndName(...) must accept this
            //   configuration without merging or throwing.
            // Successful when? — both elements appear in the resulting
            //   index under their respective (type, name) keys.
            // Why important? — This is the structural fix that
            //   dissolves finding 1.1 (P.3) by construction; pre-Q.4
            //   the same fixture would have crashed during proxy
            //   construction.

            // Given
            FakeDecorator bh = new FakeDecorator("x", InqElementType.BULKHEAD);
            FakeDecorator cb = new FakeDecorator("x", InqElementType.CIRCUIT_BREAKER);
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(bh)
                    .shield(cb)
                    .build();

            // When
            Map<ElementResolver.ElementTypeAndName, InqElement> index =
                    ElementResolver.indexByTypeAndName(pipeline);

            // Then both pairs are present, neither replaced the other
            assertThat(index).hasSize(2);
            assertThat(index).containsEntry(
                    new ElementResolver.ElementTypeAndName(
                            InqElementType.BULKHEAD, "x"),
                    bh);
            assertThat(index).containsEntry(
                    new ElementResolver.ElementTypeAndName(
                            InqElementType.CIRCUIT_BREAKER, "x"),
                    cb);
        }

        @Test
        void should_throw_npe_when_pipeline_is_null() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ElementResolver.indexByTypeAndName(null))
                    .withMessage("pipeline");
        }
    }

    @Nested
    class ResolveTriples {

        @Test
        void should_resolve_each_ref_against_its_type_and_name() {
            // Given
            FakeDecorator bh = new FakeDecorator("bh", InqElementType.BULKHEAD);
            FakeDecorator cb = new FakeDecorator("cb", InqElementType.CIRCUIT_BREAKER);
            Map<ElementResolver.ElementTypeAndName, InqElement> index = Map.of(
                    new ElementResolver.ElementTypeAndName(
                            InqElementType.BULKHEAD, "bh"), bh,
                    new ElementResolver.ElementTypeAndName(
                            InqElementType.CIRCUIT_BREAKER, "cb"), cb);

            // When
            List<InqElement> resolved = ElementResolver.resolveTriples(
                    List.of(
                            new ElementRef(InqElementType.CIRCUIT_BREAKER, "cb"),
                            new ElementRef(InqElementType.BULKHEAD, "bh")),
                    index);

            // Then — order matches the refs list, not the index iteration order
            assertThat(resolved).containsExactly(cb, bh);
        }

        @Test
        void should_return_an_empty_list_when_refs_is_empty() {
            // Given
            Map<ElementResolver.ElementTypeAndName, InqElement> index = Map.of();

            // When
            List<InqElement> resolved = ElementResolver.resolveTriples(List.of(), index);

            // Then
            assertThat(resolved).isEmpty();
        }

        @Test
        void should_throw_illegal_state_when_a_ref_pair_is_not_in_the_index() {
            // What is to be tested? — When a ref's (type, name) pair is
            //   absent from the index, resolveTriples must throw an
            //   IllegalStateException naming both the type and the
            //   name and pointing at the evaluator (ADR-036) as the
            //   upstream contract.
            // Successful when? — the exception message contains the
            //   element type, the name, and "ADR-036".
            // Why important? — The resolver is the proxy module's
            //   defensive guard against evaluator/pipeline drift.
            //   Without a clear diagnostic, a missed pair would only
            //   surface as an obscure NullPointerException later
            //   during fold.

            // Given — index has BULKHEAD "bh", but the ref asks for RETRY "bh"
            Map<ElementResolver.ElementTypeAndName, InqElement> index = Map.of(
                    new ElementResolver.ElementTypeAndName(
                            InqElementType.BULKHEAD, "bh"),
                    new FakeDecorator("bh", InqElementType.BULKHEAD));

            // When / Then
            assertThatThrownBy(() -> ElementResolver.resolveTriples(
                    List.of(new ElementRef(InqElementType.RETRY, "bh")),
                    index))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RETRY")
                    .hasMessageContaining("bh")
                    .hasMessageContaining("ADR-036");
        }

        @Test
        void should_throw_npe_when_refs_is_null() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ElementResolver.resolveTriples(null, Map.of()))
                    .withMessage("refs");
        }

        @Test
        void should_throw_npe_when_by_type_and_name_is_null() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ElementResolver.resolveTriples(List.of(), null))
                    .withMessage("byTypeAndName");
        }
    }

    @Nested
    class ElementTypeAndNameRecord {

        @Test
        void should_throw_npe_when_element_type_is_null() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new ElementResolver.ElementTypeAndName(null, "bh"))
                    .withMessage("elementType");
        }

        @Test
        void should_throw_npe_when_name_is_null() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new ElementResolver.ElementTypeAndName(
                            InqElementType.BULKHEAD, null))
                    .withMessage("name");
        }
    }
}
