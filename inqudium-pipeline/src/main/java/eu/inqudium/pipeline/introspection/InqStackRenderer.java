package eu.inqudium.pipeline.introspection;

import eu.inqudium.core.element.InqElement;

import java.util.List;
import java.util.Objects;

/**
 * Paradigm-agnostic rendering of {@link InqStackInfo} DTOs
 * per ADR-039.
 *
 * <p>Two output formats:</p>
 *
 * <ul>
 *   <li>{@link #toTree(InqStackInfo)} — Unicode ASCII tree
 *       suitable for log output and developer
 *       documentation. Consistent with the
 *       {@code AbstractBaseWrapper.toStringHierarchy()}
 *       style used elsewhere in the library.</li>
 *   <li>{@link #toJson(InqStackInfo)} — JSON object,
 *       machine-readable. Written directly via
 *       {@link StringBuilder} (no JSON library
 *       dependency).</li>
 * </ul>
 *
 * <p>Both methods produce deterministic output: the
 * same {@code InqStackInfo} always produces the same
 * string. Suitable for golden-test assertions.</p>
 *
 * <p>{@link InqElement#eventPublisher()} is internal
 * infrastructure and is omitted from both renderings;
 * {@link MethodLayers#method()} is reflective state and
 * is also omitted (the public-facing equivalent is
 * {@link MethodLayers#methodSignature()}, which is
 * included).</p>
 *
 * @since 0.10.0
 */
public final class InqStackRenderer {

    private InqStackRenderer() {
        // utility class
    }

    /**
     * Renders the given {@link InqStackInfo} as a Unicode
     * tree string. The output starts with the stack
     * header ({@code Stack-ID} and {@code Target}), then
     * the {@code Elements} section, then the {@code
     * Methods} section. Empty collections render as
     * {@code (none)}.
     *
     * <p>Sample output for a two-element proxy stack
     * with one method:</p>
     *
     * <pre>
     * Stack-ID: 42
     * Target: eu.inqudium.example.OrderService
     * Elements:
     *   &#x251c;&#x2500;&#x2500; TL: orderTl
     *   &#x2514;&#x2500;&#x2500; BH: orderBh
     * Methods:
     *   &#x2514;&#x2500;&#x2500; OrderService.greet(String)
     *         &#x251c;&#x2500;&#x2500; TIME_LIMITER(orderTl)
     *         &#x2514;&#x2500;&#x2500; BULKHEAD(orderBh)
     * </pre>
     *
     * @param info the introspection DTO to render; must
     *             not be {@code null}
     * @return the tree-formatted string (no trailing
     *         newline)
     * @throws NullPointerException if {@code info} is
     *                              {@code null}
     */
    public static String toTree(InqStackInfo info) {
        Objects.requireNonNull(info, "info");
        StringBuilder sb = new StringBuilder();

        sb.append("Stack-ID: ").append(info.stackId()).append('\n');
        sb.append("Target: ").append(renderTargetType(info)).append('\n');

        sb.append("Elements:");
        List<InqElement> elements = info.elements();
        if (elements.isEmpty()) {
            sb.append(" (none)\n");
        } else {
            sb.append('\n');
            for (int i = 0; i < elements.size(); i++) {
                InqElement element = elements.get(i);
                boolean last = (i == elements.size() - 1);
                sb.append("  ")
                        .append(last ? "└── " : "├── ")
                        .append(element.elementType().symbol())
                        .append(": ")
                        .append(element.name())
                        .append('\n');
            }
        }

        sb.append("Methods:");
        List<MethodLayers> methods = info.methodLayers();
        if (methods.isEmpty()) {
            sb.append(" (none)");
        } else {
            sb.append('\n');
            for (int i = 0; i < methods.size(); i++) {
                MethodLayers ml = methods.get(i);
                boolean lastMethod = (i == methods.size() - 1);
                String methodBranch = lastMethod ? "└── " : "├── ";
                String childPrefix = lastMethod ? "      " : "  │   ";

                sb.append("  ").append(methodBranch)
                        .append(ml.methodSignature()).append('\n');

                List<String> descriptions = ml.layerDescriptions();
                if (descriptions.isEmpty()) {
                    sb.append(childPrefix).append("(no layers)");
                    if (!lastMethod) {
                        sb.append('\n');
                    }
                } else {
                    for (int j = 0; j < descriptions.size(); j++) {
                        boolean lastLayer = (j == descriptions.size() - 1);
                        sb.append(childPrefix)
                                .append(lastLayer ? "└── " : "├── ")
                                .append(descriptions.get(j));
                        if (!(lastMethod && lastLayer)) {
                            sb.append('\n');
                        }
                    }
                }
            }
        }

        return sb.toString();
    }

    /**
     * Renders the given {@link InqStackInfo} as a JSON
     * object string. Keys mirror the record component
     * names of {@link InqStackInfo} ({@code stackId},
     * {@code targetType}, {@code elements},
     * {@code methodLayers}); empty {@link java.util.Optional}
     * targetType renders as JSON {@code null}.
     *
     * <p>Pretty-printed with two-space indentation for
     * golden-test readability. No trailing newline.</p>
     *
     * <p>Sample output:</p>
     *
     * <pre>
     * {
     *   "stackId": 42,
     *   "targetType": "eu.inqudium.example.OrderService",
     *   "elements": [
     *     {"name": "orderTl", "elementType": "TL"},
     *     {"name": "orderBh", "elementType": "BH"}
     *   ],
     *   "methodLayers": [
     *     {
     *       "methodSignature": "OrderService.greet(String)",
     *       "layerDescriptions": ["TIME_LIMITER(orderTl)", "BULKHEAD(orderBh)"]
     *     }
     *   ]
     * }
     * </pre>
     *
     * @param info the introspection DTO to render; must
     *             not be {@code null}
     * @return the JSON-formatted string (no trailing
     *         newline)
     * @throws NullPointerException if {@code info} is
     *                              {@code null}
     */
    public static String toJson(InqStackInfo info) {
        Objects.requireNonNull(info, "info");
        StringBuilder sb = new StringBuilder();

        sb.append("{\n");
        sb.append("  \"stackId\": ").append(info.stackId()).append(",\n");

        sb.append("  \"targetType\": ");
        if (info.targetType().isEmpty()) {
            sb.append("null");
        } else {
            sb.append('"').append(jsonEscape(info.targetType().get().getName())).append('"');
        }
        sb.append(",\n");

        sb.append("  \"elements\": ");
        List<InqElement> elements = info.elements();
        if (elements.isEmpty()) {
            sb.append("[]");
        } else {
            sb.append("[\n");
            for (int i = 0; i < elements.size(); i++) {
                InqElement element = elements.get(i);
                sb.append("    {\"name\": \"")
                        .append(jsonEscape(element.name()))
                        .append("\", \"elementType\": \"")
                        .append(jsonEscape(element.elementType().symbol()))
                        .append("\"}");
                if (i < elements.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            sb.append("  ]");
        }
        sb.append(",\n");

        sb.append("  \"methodLayers\": ");
        List<MethodLayers> methods = info.methodLayers();
        if (methods.isEmpty()) {
            sb.append("[]");
        } else {
            sb.append("[\n");
            for (int i = 0; i < methods.size(); i++) {
                MethodLayers ml = methods.get(i);
                sb.append("    {\n");
                sb.append("      \"methodSignature\": \"")
                        .append(jsonEscape(ml.methodSignature()))
                        .append("\",\n");
                sb.append("      \"layerDescriptions\": [");
                List<String> descriptions = ml.layerDescriptions();
                for (int j = 0; j < descriptions.size(); j++) {
                    sb.append('"').append(jsonEscape(descriptions.get(j))).append('"');
                    if (j < descriptions.size() - 1) {
                        sb.append(", ");
                    }
                }
                sb.append("]\n");
                sb.append("    }");
                if (i < methods.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            sb.append("  ]");
        }
        sb.append('\n');

        sb.append('}');
        return sb.toString();
    }

    private static String renderTargetType(InqStackInfo info) {
        return info.targetType()
                .map(Class::getName)
                .orElse("<none>");
    }

    private static String jsonEscape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
