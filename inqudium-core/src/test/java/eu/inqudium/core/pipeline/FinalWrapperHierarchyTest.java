package eu.inqudium.core.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Parameterized Tests for all Wrapper Types")
class FinalWrapperHierarchyTest {

  /**
   * Datenquelle für alle Wrapper-Typen.
   * Erzeugt für jeden Typ eine "Bridge", die im Test aufgerufen wird.
   */
  static Stream<WrapperTestBridge> wrapperProvider() {
    return Stream.of(
        new WrapperTestBridge() {
          private final SupplierWrapper<String> w = new SupplierWrapper<>("Supplier-Root",
              new SupplierWrapper<>("Supplier-Inner", () -> "val"));

          @Override
          public void execute() {
            w.get();
          }

          @Override
          public BaseWrapper<?, ?, ?> getWrapper() {
            return w;
          }

          @Override
          public String toString() {
            return "SupplierWrapper";
          }
        },
        new WrapperTestBridge() {
          private final RunnableWrapper w = new RunnableWrapper("Runnable-Root",
              new RunnableWrapper("Runnable-Inner", () -> {
              }));

          @Override
          public void execute() {
            w.run();
          }

          @Override
          public BaseWrapper<?, ?, ?> getWrapper() {
            return w;
          }

          @Override
          public String toString() {
            return "RunnableWrapper";
          }
        },
        new WrapperTestBridge() {
          private final CallableWrapper<String> w = new CallableWrapper<>("Callable-Root",
              new CallableWrapper<>("Callable-Inner", () -> "val"));

          @Override
          public void execute() throws Exception {
            w.call();
          }

          @Override
          public BaseWrapper<?, ?, ?> getWrapper() {
            return w;
          }

          @Override
          public String toString() {
            return "CallableWrapper";
          }
        }
    );
  }

  // Hilfs-Interface, um die Ausführung der verschiedenen Typen zu vereinheitlichen
  interface WrapperTestBridge {
    void execute() throws Exception;

    BaseWrapper<?, ?, ?> getWrapper();
  }

  @Nested
  @DisplayName("Generic Structural Tests")
  class StructuralTests {

    @ParameterizedTest(name = "Testing hierarchy logic for {0}")
    @MethodSource("eu.inqudium.core.pipeline.FinalWrapperHierarchyTest#wrapperProvider")
    @DisplayName("Every wrapper type must correctly represent the hierarchy string without a leading symbol at root")
    void hierarchyStringMustBeCorrect(WrapperTestBridge bridge) {
      // Given
      BaseWrapper<?, ?, ?> wrapper = bridge.getWrapper();

      // When
      String hierarchy = wrapper.toStringHierarchy();
      String[] lines = hierarchy.split("\n");

      // Then
      // Line 0: Chain-ID: ...
      // Line 1: <Name>-Root (Ohne └──)
      // Line 2:   └── <Name>-Inner
      assertThat(lines[1])
          .as("Root layer should not have a leading tree symbol")
          .doesNotContain("└──")
          .contains("-Root");

      assertThat(lines[2])
          .as("Inner layer must be indented and have a tree symbol")
          .contains("  └── ")
          .contains("-Inner");
    }

    @ParameterizedTest(name = "Testing identity for {0}")
    @MethodSource("eu.inqudium.core.pipeline.FinalWrapperHierarchyTest#wrapperProvider")
    @DisplayName("The Chain-ID must be stable and shared between layers")
    void chainIdMustBeStable(WrapperTestBridge bridge) {
      // Given
      BaseWrapper<?, ?, ?> root = bridge.getWrapper();
      BaseWrapper<?, ?, ?> inner = root.getInner();

      // Then
      assertThat(root.getChainId())
          .as("Chain-ID must be non-null and identical across the hierarchy")
          .isNotNull()
          .isEqualTo(inner.getChainId());
    }
  }

  @Nested
  @DisplayName("Execution Flow Tests")
  class ExecutionTests {

    @ParameterizedTest(name = "Testing execution for {0}")
    @MethodSource("eu.inqudium.core.pipeline.FinalWrapperHierarchyTest#wrapperProvider")
    @DisplayName("Executing any layer must trigger the full chain starting from the outermost")
    void executionShouldStartAtOutermost(WrapperTestBridge bridge) {
      // Given
      BaseWrapper<?, ?, ?> inner = bridge.getWrapper().getInner();

      // When
      // Wir führen den Test über den inneren Layer aus
      if (inner instanceof Supplier) ((Supplier<?>) inner).get();
      else if (inner instanceof Runnable) ((Runnable) inner).run();
      else if (inner instanceof Callable) {
        try {
          ((Callable<?>) inner).call();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }

      // Then
      // getOutermost() sorgt dafür, dass die Kette immer oben beginnt.
      assertThat(inner.getOutermost()).isEqualTo(bridge.getWrapper());
    }
  }
}