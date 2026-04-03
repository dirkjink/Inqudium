package eu.inqudium.core.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Tests for SupplierWrapper Implementation")
class SupplierWrapperTest {

  @Nested
  @DisplayName("Hierarchy and Identity Tests")
  class IdentityTests {

    @Test
    @DisplayName("A chain of suppliers must share the same Chain-ID from bottom to top")
    void chainShouldShareSameId() {
      // Given
      Supplier<String> core = () -> "CoreData";
      SupplierWrapper<String> database = new SupplierWrapper<>("Database", core);
      SupplierWrapper<String> cache = new SupplierWrapper<>("Cache", database);

      // When
      String dbId = database.getChainId();
      String cacheId = cache.getChainId();

      // Then
      assertThat(dbId)
          .as("The Chain-ID must be propagated to the outer layer")
          .isEqualTo(cacheId);
    }
  }

  @Nested
  @DisplayName("Execution and Navigation Tests")
  class ExecutionTests {

    @Test
    @DisplayName("Calling get on any layer should correctly return the core value via the top layer")
    void shouldReturnValueFromCore() {
      // Given
      SupplierWrapper<Integer> layer1 = new SupplierWrapper<>("L1", () -> 100);
      SupplierWrapper<Integer> layer2 = new SupplierWrapper<>("L2", layer1);

      // When
      Integer result = layer1.get();

      // Then
      assertThat(result).isEqualTo(100);
      assertThat(layer1.getOutermost()).isEqualTo(layer2);
    }
  }

  @Nested
  @DisplayName("Visualization Tests")
  class VisualTests {

    @Test
    @DisplayName("The hierarchy string should correctly represent the nested structure")
    void shouldPrintCorrectHierarchy() {
      // Given
      SupplierWrapper<String> chain = new SupplierWrapper<>("Outer",
          new SupplierWrapper<>("Inner", () -> "Value"));

      // When
      String hierarchy = chain.toStringHierarchy();

      // Then
      assertThat(hierarchy)
          .contains("└── Outer")
          .contains("  └── Inner");
    }
  }
}
