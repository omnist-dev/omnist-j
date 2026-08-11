package dev.omnist.algebra;

import dev.omnist.schema.Schema;
import java.util.List;

public record InferResult(Schema schema, List<AnyFallback> fallbacks) {}
