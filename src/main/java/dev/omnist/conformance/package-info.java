/**
 * The omnist-spec conformance test harness: runs Track 1 (CLI fixtures) and
 * Track 2 (JSON vectors) from the vendored spec fixtures against this
 * implementation's CLI, verifying it matches the spec's documented behavior
 * (omnist-spec §8.5). Excluded from the JaCoCo coverage gate — its job is to
 * drive external test infrastructure, not logic the unit suite exercises.
 */
package dev.omnist.conformance;
