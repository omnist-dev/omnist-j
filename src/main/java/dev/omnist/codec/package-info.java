/**
 * Reading and writing the {@link dev.omnist.document.Document} model as the
 * non-OML wire formats — JSON, YAML, TOML, and XML (omnist-spec §7.3) — including
 * the lossy-adjustment reporting ({@link dev.omnist.codec.WriteReport}) that
 * records when a format's constraints force a value to be adjusted on write.
 */
package dev.omnist.codec;
