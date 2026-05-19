/**
 * Imperative paradigm runtime container and provider.
 *
 * <p>{@link eu.inqudium.imperative.runtime.DefaultImperative DefaultImperative} is the concrete
 * {@link eu.inqudium.config.runtime.Sync Sync} container — a name-keyed map of
 * {@code InqBulkhead} instances. {@link eu.inqudium.imperative.runtime.SyncParadigmProvider
 * SyncParadigmProvider} is the {@link eu.inqudium.config.spi.ParadigmProvider ParadigmProvider}
 * registered via {@code META-INF/services} that the runtime builder discovers via
 * {@link java.util.ServiceLoader}.
 */
package eu.inqudium.imperative.runtime;
