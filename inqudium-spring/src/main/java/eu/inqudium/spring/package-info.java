/**
 * Spring AOP integration for Inqudium. This module is
 * intentionally empty in 0.10.x — its previous content
 * depended on the legacy {@code eu.inqudium.core.pipeline}
 * stack, which was removed in Phase A of the post-polish
 * refactor sequence. A future Phase B (ADR-039) rebuilds
 * this module against the new pipeline interface.
 *
 * <p>The POM stays in place so dependent modules can keep
 * their dependency declaration; the artifact ships as an
 * empty jar.</p>
 *
 * @see <a href="https://github.com/dirkjink/inqudium/blob/main/REFACTORING_LEGACY_PROXY_REMOVAL.md">Phase A plan</a>
 */
package eu.inqudium.spring;
