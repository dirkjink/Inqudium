package eu.inqudium.proxy.invocation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Factory-level tests for {@link MethodInvoker#create}. The
 * {@code inqudium.proxy.invoker} JVM property is captured once at
 * class init (see {@link MethodInvoker#CACHED_INVOKER_PROPERTY}),
 * so property-mutation tests cannot drive different code paths
 * within a single JVM. The default ({@code mh}) and the
 * null-argument contract are covered here; coverage of the
 * {@code reflective} path lives in {@link ReflectiveInvokerTest},
 * and the malformed-value path is documented but not unit-tested
 * (would require a forked JVM with a malformed system property).
 */
class MethodInvokerFactoryTest {

    private static Method greetMethod() throws NoSuchMethodException {
        return TestSubject.class.getDeclaredMethod("greet", String.class);
    }

    @Test
    void should_create_a_method_handle_invoker_by_default() throws NoSuchMethodException {
        // Given — CI runs with the inqudium.proxy.invoker property
        // unset, so the cached value is "mh".
        TestSubject target = new TestSubject();

        // When
        MethodInvoker invoker = MethodInvoker.create(target, greetMethod());

        // Then
        assertThat(invoker).isInstanceOf(MethodHandleInvoker.class);
    }

    @Test
    void should_reject_null_target() throws NoSuchMethodException {
        // Given
        Method method = greetMethod();

        // When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> MethodInvoker.create(null, method))
                .withMessage("target");
    }

    @Test
    void should_reject_null_method() {
        // Given
        TestSubject target = new TestSubject();

        // When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> MethodInvoker.create(target, null))
                .withMessage("method");
    }
}
