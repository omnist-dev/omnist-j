/**
 * Validating and materializing documents against an OSD schema: checking a
 * {@link dev.omnist.document.Document} conforms to a {@link dev.omnist.schema.Schema}
 * and reporting diagnostics when it doesn't (omnist-spec §3.6), and the
 * materialization pass that produces a schema-conformant document from one
 * that's structurally compatible but not yet exact (§6.3, §8.2).
 */
package dev.omnist.validation;
