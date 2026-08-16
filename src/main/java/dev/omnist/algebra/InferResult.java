package dev.omnist.algebra;

import dev.omnist.schema.Schema;
import java.util.List;

/**
 * The result of {@link SchemaAlgebra#inferWithReport(List, String, boolean)}: the
 * inferred schema plus a report of every field that fell back to {@code any}.
 *
 * @param schema    the inferred schema
 * @param fallbacks one entry per field that was typed {@code any} because its sample
 *                  values mixed incompatible scalar kinds; empty if none did
 */
public record InferResult(Schema schema, List<AnyFallback> fallbacks) {}
