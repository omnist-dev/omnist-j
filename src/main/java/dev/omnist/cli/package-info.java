/**
 * The {@code omnist} command-line interface: parses arguments and dispatches to
 * every subcommand ({@code format}, {@code validate}, {@code convert},
 * {@code schema normalize/prune/extract/lint/compatible-with/equivalent/is-empty},
 * {@code infer}) documented in {@code docs/02-cli-reference.md}, wiring the codec,
 * schema, and validation packages together into the tool end users run.
 */
package dev.omnist.cli;
