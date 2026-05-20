/**
 * ADR-039 stack-introspection DTOs and the sealed
 * {@link eu.inqudium.pipeline.introspection.InqStackInfo
 * InqStackInfo} hierarchy. Paradigm-specific adapters
 * (in their respective modules) populate the permits
 * and return them via this module's central dispatch
 * utility ({@code InqIntrospector}, see sub-step B.4).
 *
 * @since 0.10.0
 */
package eu.inqudium.pipeline.introspection;
