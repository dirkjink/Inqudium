package eu.inqudium.proxy.exception;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Objects;

/**
 * Recursively unwraps reflective wrapper exceptions
 * ({@link InvocationTargetException} and
 * {@link UndeclaredThrowableException}) to expose the real cause.
 * Stops at the first non-wrapper throwable, or at a wrapper with
 * a {@code null} cause (which is then returned as-is).
 *
 * <p>Per ARCHITECTURE.md §9 / ADR-035 §10 step 1.</p>
 */
final class ThrowableUnwrap {

    private ThrowableUnwrap() {
        // utility class
    }

    /**
     * Maximum unwrap depth. Real-world wrapper chains never exceed
     * two or three layers; the bound is purely defensive against a
     * pathological cyclic cause chain.
     */
    private static final int MAX_UNWRAP_DEPTH = 10;

    /**
     * Walks the cause chain through any sequence of
     * {@code InvocationTargetException} and
     * {@code UndeclaredThrowableException} wrappers, returning the
     * first throwable that is neither.
     *
     * <p>If a wrapper has a {@code null} cause (theoretically
     * possible, practically rare), the wrapper itself is returned —
     * there is nothing further to unwrap.</p>
     *
     * <p>The walk terminates after {@link #MAX_UNWRAP_DEPTH} steps
     * to defend against a cyclic cause chain. A standard
     * {@link Throwable#initCause(Throwable)} rejects self-reference,
     * so a cycle can only arise via reflective field manipulation;
     * the guard exists for defence-in-depth, not for any expected
     * input shape.</p>
     *
     * @param t the throwable to unwrap; must not be {@code null}
     * @throws NullPointerException if {@code t} is {@code null}
     */
    static Throwable unwrap(Throwable t) {
        Objects.requireNonNull(t, "t");
        Throwable current = t;
        int depth = 0;
        while (depth++ < MAX_UNWRAP_DEPTH
                && (current instanceof InvocationTargetException
                || current instanceof UndeclaredThrowableException)) {
            Throwable cause = current.getCause();
            if (cause == null) {
                return current;
            }
            current = cause;
        }
        return current;
    }
}
