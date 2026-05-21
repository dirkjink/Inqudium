package eu.inqudium.pipeline.introspection;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.paradigm.ParadigmTag;
import eu.inqudium.core.paradigm.SyncTag;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Golden-string tests for {@link InqStackRenderer}.
 *
 * <p>The renderer produces deterministic output, so test
 * assertions compare against literal strings. If a
 * maintainer edits the format intentionally, these tests
 * must be updated to match — they are the format's
 * specification.</p>
 */
class InqStackRendererTest {

    /** Fixture interface used in golden-string assertions. */
    interface OrderService {
        String greet(String name);
    }

    /** Minimal {@link InqElement} fixture. */
    static final class FakeElement implements InqElement {
        private final String name;
        private final InqElementType type;

        FakeElement(String name, InqElementType type) {
            this.name = name;
            this.type = type;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public InqElementType elementType() {
            return type;
        }

        @Override
        public InqEventPublisher eventPublisher() {
            return null;
        }

        @Override
        public java.util.Set<ParadigmTag> paradigmTags() {
            return java.util.Set.of(SyncTag.INSTANCE);
        }
    }

    private static ProxyStackInfo sampleProxyStack() {
        return new ProxyStackInfo(
                42L,
                Optional.of(OrderService.class),
                List.of(
                        new FakeElement("orderTl", InqElementType.TIME_LIMITER),
                        new FakeElement("orderBh", InqElementType.BULKHEAD)),
                List.of(new MethodLayers(
                        "OrderService.greet(String)",
                        List.of("TIME_LIMITER(orderTl)", "BULKHEAD(orderBh)"),
                        Optional.empty())));
    }

    private static FunctionStackInfo sampleEmptyFunctionStack() {
        return new FunctionStackInfo(
                7L,
                List.of(),
                List.of());
    }

    @Nested
    class Tree_format {

        @Test
        void should_render_a_proxy_stack_info_with_unicode_tree_characters() {
            // Given
            ProxyStackInfo info = sampleProxyStack();

            // When
            String tree = InqStackRenderer.toTree(info);

            // Then
            String expected = """
                    Stack-ID: 42
                    Target: %s
                    Elements:
                      ├── TL: orderTl
                      └── BH: orderBh
                    Methods:
                      └── OrderService.greet(String)
                          ├── TIME_LIMITER(orderTl)
                          └── BULKHEAD(orderBh)""".formatted(
                    OrderService.class.getName());
            assertThat(tree).isEqualTo(expected);
        }

        @Test
        void should_render_function_stack_info_with_none_placeholders_for_empty_optional_and_lists() {
            // What is to be tested?
            //   FunctionStackInfo always reports
            //   targetType() == Optional.empty(). The renderer
            //   must produce a sensible '<none>' marker rather
            //   than NPE or print 'Optional.empty'. Empty
            //   elements and methodLayers lists must produce
            //   '(none)' inline.
            // How will the test case be deemed successful and why?
            //   Output matches the golden string literally.
            // Why is it important to test this test case?
            //   FunctionStackInfo is the documented future
            //   permit (B.2). Even before its adapter exists,
            //   client code may construct one for testing and
            //   try to render it — the renderer must not crash.

            // Given
            FunctionStackInfo info = sampleEmptyFunctionStack();

            // When
            String tree = InqStackRenderer.toTree(info);

            // Then
            String expected = """
                    Stack-ID: 7
                    Target: <none>
                    Elements: (none)
                    Methods: (none)""";
            assertThat(tree).isEqualTo(expected);
        }

        @Test
        void should_render_multiple_methods_with_continuation_lines() {
            // What is to be tested?
            //   When two or more methods are listed, the
            //   non-last entries must use '├──' branches and
            //   the layers under them must use '│' continuation
            //   lines so the tree structure is unambiguous.
            // How will the test case be deemed successful and why?
            //   The output uses '│' under the non-last method
            //   and a plain-space continuation under the last
            //   method; layer branches use '├──'/'└──'
            //   correctly.
            // Why is it important to test this test case?
            //   Pins the canonical tree-drawing convention. A
            //   regression here would produce ambiguous output
            //   that reviewers and tools could misparse.

            // Given
            ProxyStackInfo info = new ProxyStackInfo(
                    1L,
                    Optional.of(OrderService.class),
                    List.of(),
                    List.of(
                            new MethodLayers(
                                    "OrderService.greet(String)",
                                    List.of("BULKHEAD(bh)"),
                                    Optional.empty()),
                            new MethodLayers(
                                    "OrderService.farewell(String)",
                                    List.of("RETRY(rt)"),
                                    Optional.empty())));

            // When
            String tree = InqStackRenderer.toTree(info);

            // Then
            String expected = """
                    Stack-ID: 1
                    Target: %s
                    Elements: (none)
                    Methods:
                      ├── OrderService.greet(String)
                      │   └── BULKHEAD(bh)
                      └── OrderService.farewell(String)
                          └── RETRY(rt)""".formatted(
                    OrderService.class.getName());
            assertThat(tree).isEqualTo(expected);
        }

        @Test
        void should_render_a_method_with_no_layers_as_no_layers_marker() {
            // Given
            ProxyStackInfo info = new ProxyStackInfo(
                    9L,
                    Optional.of(OrderService.class),
                    List.of(),
                    List.of(new MethodLayers(
                            "OrderService.toString()",
                            List.of(),
                            Optional.empty())));

            // When
            String tree = InqStackRenderer.toTree(info);

            // Then
            String expected = """
                    Stack-ID: 9
                    Target: %s
                    Elements: (none)
                    Methods:
                      └── OrderService.toString()
                          (no layers)""".formatted(
                    OrderService.class.getName());
            assertThat(tree).isEqualTo(expected);
        }

        @Test
        void should_reject_null_input_with_npe() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> InqStackRenderer.toTree(null))
                    .withMessage("info");
        }
    }

    @Nested
    class Json_format {

        @Test
        void should_render_a_proxy_stack_info_with_record_component_keys() {
            // Given
            ProxyStackInfo info = sampleProxyStack();

            // When
            String json = InqStackRenderer.toJson(info);

            // Then
            String expected = """
                    {
                      "stackId": 42,
                      "targetType": "%s",
                      "elements": [
                        {"name": "orderTl", "elementType": "TL"},
                        {"name": "orderBh", "elementType": "BH"}
                      ],
                      "methodLayers": [
                        {
                          "methodSignature": "OrderService.greet(String)",
                          "layerDescriptions": ["TIME_LIMITER(orderTl)", "BULKHEAD(orderBh)"]
                        }
                      ]
                    }""".formatted(OrderService.class.getName());
            assertThat(json).isEqualTo(expected);
        }

        @Test
        void should_render_empty_optional_target_type_as_json_null() {
            // What is to be tested?
            //   FunctionStackInfo always reports Optional.empty
            //   targetType. JSON must render it as the literal
            //   token 'null' (not '"null"' or '"<none>"').
            // How will the test case be deemed successful and why?
            //   The output contains the literal token 'null'
            //   at the targetType position.
            // Why is it important to test this test case?
            //   JSON consumers distinguish null from string;
            //   confusing the two would break machine readers.

            // Given
            FunctionStackInfo info = sampleEmptyFunctionStack();

            // When
            String json = InqStackRenderer.toJson(info);

            // Then
            String expected = """
                    {
                      "stackId": 7,
                      "targetType": null,
                      "elements": [],
                      "methodLayers": []
                    }""";
            assertThat(json).isEqualTo(expected);
        }

        @Test
        void should_escape_quotes_and_backslashes_in_string_values() {
            // What is to be tested?
            //   Element names and method signatures may contain
            //   characters that need JSON escaping (quotes,
            //   backslashes, control characters). The renderer
            //   must produce a valid JSON token for each.
            // How will the test case be deemed successful and why?
            //   The output's escaped characters match the JSON
            //   spec: '\\"' for ", '\\\\' for \, '\\n' for newline.
            // Why is it important to test this test case?
            //   Pins the escape rules so a future format edit
            //   does not silently produce invalid JSON.

            // Given
            ProxyStackInfo info = new ProxyStackInfo(
                    1L,
                    Optional.of(OrderService.class),
                    List.of(new FakeElement("name with \"quote\" and \\backslash",
                            InqElementType.BULKHEAD)),
                    List.of(new MethodLayers(
                            "Foo.bar(\"baz\")\nnext",
                            List.of("a\tb"),
                            Optional.empty())));

            // When
            String json = InqStackRenderer.toJson(info);

            // Then
            assertThat(json)
                    .contains("\"name\": \"name with \\\"quote\\\" and \\\\backslash\"")
                    .contains("\"methodSignature\": \"Foo.bar(\\\"baz\\\")\\nnext\"")
                    .contains("\"layerDescriptions\": [\"a\\tb\"]");
        }

        @Test
        void should_reject_null_input_with_npe() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> InqStackRenderer.toJson(null))
                    .withMessage("info");
        }
    }

    @Nested
    class Determinism {

        @Test
        void should_produce_identical_tree_output_on_repeated_calls() {
            // Given
            ProxyStackInfo info = sampleProxyStack();

            // When
            String first = InqStackRenderer.toTree(info);
            String second = InqStackRenderer.toTree(info);

            // Then
            assertThat(first).isEqualTo(second);
        }

        @Test
        void should_produce_identical_json_output_on_repeated_calls() {
            // Given
            ProxyStackInfo info = sampleProxyStack();

            // When
            String first = InqStackRenderer.toJson(info);
            String second = InqStackRenderer.toJson(info);

            // Then
            assertThat(first).isEqualTo(second);
        }
    }
}
