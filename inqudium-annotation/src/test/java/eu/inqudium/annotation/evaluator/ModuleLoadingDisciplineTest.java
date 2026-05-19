package eu.inqudium.annotation.evaluator;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical verification of the ADR-046 §4 lazy-class-loading discipline
 * for {@link ParadigmClassifier} and its three probes.
 *
 * <p>The probes ({@link Reactive}, {@link RxJava3}, {@link Coroutines})
 * are loadable on any classpath: no class literal of an external paradigm
 * type appears in their bytecode. External types are resolved by
 * {@link Class#forName(String, boolean, ClassLoader)} with
 * {@code initialize=false} and wrapped in {@link java.util.Optional}.
 * This test makes the discipline empirical: in an isolated classloader
 * whose classpath has been stripped of the external paradigm libraries
 * (mirroring the production classpath of {@code inqudium-annotation}),
 * exercising the classifier on a sync-only method must load <strong>none
 * </strong> of the eleven paradigm library classes.</p>
 *
 * <p><strong>Why URLClassLoader isolation.</strong> Other test classes
 * in the same Surefire run (the {@link ParadigmClassifierTest}, the
 * existing evaluator tests) routinely load reactor/rxjava3/coroutines
 * classes via the system classloader. A fresh {@link URLClassLoader}
 * with parent set to the system classloader's parent (the platform
 * loader) sees a clean load-state for every {@code eu.inqudium.*} type:
 * {@link ClassLoader#findLoadedClass(String)} on the isolated loader
 * reports only classes <em>that loader</em> has loaded.</p>
 *
 * <p><strong>Pattern mirrored from</strong>
 * {@code inqudium-proxy/src/test/.../ModuleLoadingDisciplineTest}
 * (sub-step 3.13a). The proxy version asserts no async-imperative classes
 * load on the sync-only proxy path; this version asserts no paradigm
 * library classes load on the sync-only classifier path. Both use the
 * same {@code IntrospectingClassLoader} subclass trick to expose
 * {@code findLoadedClass} without
 * {@code --add-opens java.base/java.lang=ALL-UNNAMED}.</p>
 */
final class ModuleLoadingDisciplineTest {

    /**
     * Classes that must NOT be loaded by the isolated loader after a
     * sync-only {@code classify(...)} call. These are the eleven
     * external types the probes look for; if any one loads, the
     * lazy-class-loading discipline is broken.
     */
    private static final List<String> PARADIGM_LIBRARY_CLASSES = List.of(
            "reactor.core.publisher.Mono",
            "reactor.core.publisher.Flux",
            "io.reactivex.rxjava3.core.Single",
            "io.reactivex.rxjava3.core.Maybe",
            "io.reactivex.rxjava3.core.Completable",
            "io.reactivex.rxjava3.core.Flowable",
            "io.reactivex.rxjava3.core.Observable",
            "kotlin.coroutines.Continuation",
            "kotlinx.coroutines.Deferred",
            "kotlinx.coroutines.Job",
            "kotlinx.coroutines.flow.Flow"
    );

    /**
     * Maven artifact ids (and equivalent JAR filename prefixes) that
     * must be excluded from the isolated loader's classpath. This
     * matches what is absent from the production classpath of
     * {@code inqudium-annotation} (none of these are declared with
     * {@code compile} scope — they appear only as
     * {@code test} + {@code optional=true} for this very test, and
     * via Kotlin's {@code kotlin-stdlib} transitively).
     *
     * <p>{@code kotlin-stdlib} is on the list because
     * {@code kotlin.coroutines.Continuation} lives in stdlib (not in
     * {@code kotlinx-coroutines-core}); leaving stdlib on the
     * isolated classpath would let the Coroutines probe's static
     * init load {@code Continuation} and break the assertion.</p>
     */
    private static final Set<String> EXCLUDED_ARTIFACT_PREFIXES = Set.of(
            "reactor-core",
            "rxjava",
            "kotlin-stdlib",
            "kotlinx-coroutines-core",
            "kotlinx-coroutines-core-jvm"
    );

    @Test
    void no_paradigm_library_class_loads_when_classifier_handles_a_sync_only_method() throws Exception {
        // What is to be tested? — Construction and use of
        //   ParadigmClassifier on a sync-only method, with a
        //   classpath stripped of every external paradigm library
        //   (mirroring the production classpath of
        //   inqudium-annotation), must load none of the eleven
        //   external library classes the probes look for.
        // Successful when? — every entry in PARADIGM_LIBRARY_CLASSES
        //   returns null from findLoadedClass on the isolated loader,
        //   AND the classifier returns a SyncTag (sanity — confirms
        //   the classifier actually ran end-to-end).
        // Why important? — Lazy class-loading discipline is not
        //   compiler-enforced. One stray class literal in a probe
        //   would silently make inqudium-annotation depend on the
        //   external paradigm library, breaking the optional-
        //   dependency contract spelled out in ADR-046 §4.

        URL[] productionLikeClasspath = currentClasspathURLs();
        // parent = system classloader's parent (the platform loader);
        // prevents any eu.inqudium.* class from being inherited from
        // the parent and skipping our load-state observation.
        ClassLoader bootstrapParent = ClassLoader.getSystemClassLoader().getParent();

        try (IntrospectingClassLoader isolated =
                     new IntrospectingClassLoader(productionLikeClasspath, bootstrapParent)) {

            // Given — load the classifier inside the isolated loader
            Class<?> classifierClass = isolated.loadClass(
                    "eu.inqudium.annotation.evaluator.ParadigmClassifier");

            // A sync method from java.base — String.length() — so the
            // test fixture itself adds nothing paradigm-specific to
            // the isolated loader.
            Method syncFixture = String.class.getMethod("length");

            // When — invoke ParadigmClassifier.classify(syncFixture)
            //   via reflection. The static method is package-private;
            //   setAccessible makes it callable from the test.
            Method classifyMethod = classifierClass.getDeclaredMethod("classify", Method.class);
            classifyMethod.setAccessible(true);
            Object tag = classifyMethod.invoke(null, syncFixture);

            // Sanity — the classifier ran end-to-end and produced a
            //   SyncTag instance. Comparing class names (not instances)
            //   because tag was loaded by the isolated loader: the
            //   SyncTagDefault visible from the test class and the one
            //   from the isolated loader are two distinct Class
            //   objects. Post-Q.7.5 the runtime class is the
            //   package-private SyncTagDefault (the concrete singleton
            //   behind the SyncTag sealed interface).
            assertThat(tag).isNotNull();
            assertThat(tag.getClass().getName())
                    .isEqualTo("eu.inqudium.core.element.paradigm.SyncTagDefault");

            // Then — none of the paradigm-library classes loaded.
            //   peekLoadedClass exposes ClassLoader#findLoadedClass on
            //   the loader subclass; sidesteps the
            //   InaccessibleObjectException that setAccessible(true)
            //   on java.lang.ClassLoader raises under the standard
            //   module access rules.
            for (String fqn : PARADIGM_LIBRARY_CLASSES) {
                Class<?> loaded = isolated.peekLoadedClass(fqn);
                assertThat(loaded)
                        .as("Paradigm library class %s must NOT be loaded "
                                + "by the isolated URLClassLoader when only "
                                + "a sync-only classify(...) was called; "
                                + "lazy-class-loading discipline broken",
                                fqn)
                        .isNull();
            }
        }
    }

    /**
     * URL-classloader subclass exposing
     * {@link ClassLoader#findLoadedClass(String)} as a package-private
     * accessor. Lets the test peek at the loader's own load state
     * without needing
     * {@code --add-opens java.base/java.lang=ALL-UNNAMED} on the
     * Surefire JVM.
     */
    private static final class IntrospectingClassLoader extends URLClassLoader {

        IntrospectingClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        Class<?> peekLoadedClass(String name) {
            return findLoadedClass(name);
        }
    }

    /**
     * Returns the current process's classpath as a set of URLs, with
     * every entry that corresponds to one of the excluded paradigm
     * artifacts stripped. This produces a classpath equivalent to
     * the production-scope classpath of {@code inqudium-annotation}.
     */
    private static URL[] currentClasspathURLs() {
        String classpath = System.getProperty("java.class.path");
        String[] entries = classpath.split(File.pathSeparator);
        return Arrays.stream(entries)
                .filter(entry -> !isExcludedParadigmArtifact(entry))
                .map(File::new)
                .map(f -> {
                    try {
                        return f.toURI().toURL();
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "Cannot convert classpath entry to URL: " + f, e);
                    }
                })
                .toArray(URL[]::new);
    }

    /**
     * Returns true if the given classpath entry refers to one of the
     * excluded paradigm artifacts. Two canonical forms are
     * recognised:
     *
     * <ul>
     *   <li>A Maven repository directory: any path component equals
     *       the artifact id exactly (matches the artifact-id
     *       subdirectory under
     *       {@code .m2/repository/<group-path>/}).</li>
     *   <li>An artifact JAR: filename starts with
     *       {@code <artifact-id>-} and ends with {@code .jar}
     *       (matches any version, including {@code -SNAPSHOT}).</li>
     * </ul>
     *
     * <p>Platform-independent — uses {@link Path} iteration over
     * components rather than substring matching, so Maven and
     * IDE-shadowed classpaths on Windows and POSIX work identically.</p>
     */
    private static boolean isExcludedParadigmArtifact(String classpathEntry) {
        Path path = Paths.get(classpathEntry);
        for (Path component : path) {
            if (EXCLUDED_ARTIFACT_PREFIXES.contains(component.toString())) {
                return true;
            }
        }
        Path filename = path.getFileName();
        if (filename == null) {
            return false;
        }
        String name = filename.toString();
        if (!name.endsWith(".jar")) {
            return false;
        }
        for (String prefix : EXCLUDED_ARTIFACT_PREFIXES) {
            if (name.startsWith(prefix + "-")) {
                return true;
            }
        }
        return false;
    }
}
