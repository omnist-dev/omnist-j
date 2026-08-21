package dev.omnist.benchmark;

import dev.omnist.algebra.InferResult;
import dev.omnist.algebra.LintFinding;
import dev.omnist.algebra.SchemaAlgebra;
import dev.omnist.schema.Field;
import dev.omnist.schema.Record;
import dev.omnist.schema.ScalarKind;
import dev.omnist.schema.Schema;
import dev.omnist.schema.Type;
import org.openjdk.jmh.annotations.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class SchemaAlgebraBenchmark {

    @Param({"50", "200", "1000", "5000"})
    private int recordCount;

    private Schema schema1;
    private Schema schema2;

    @Setup(Level.Trial)
    public void setup() {
        Map<String, Record> records = new LinkedHashMap<>(recordCount);
        for (int i = 0; i < recordCount; i++) {
            String next = "R" + ((i + 1) % recordCount);
            List<Field> fields = List.of(
                new Field("id", new Type.Scalar(ScalarKind.INTEGER, false), 1, 1),
                new Field("next", new Type.Ref(next), 1, 1)
            );
            records.put("R" + i, new Record("R" + i, fields));
        }
        this.schema1 = new Schema("R0", records);
        this.schema2 = new Schema("R0", new LinkedHashMap<>(records));
    }

    @Benchmark
    public boolean benchmarkIsEmpty() {
        return SchemaAlgebra.isEmpty(schema1);
    }

    @Benchmark
    public Schema benchmarkPrune() {
        return SchemaAlgebra.prune(schema1);
    }

    @Benchmark
    public Schema benchmarkNormalize() {
        return SchemaAlgebra.normalize(schema1);
    }

    @Benchmark
    public List<LintFinding> benchmarkLint() {
        return SchemaAlgebra.lint(schema1);
    }

    @Benchmark
    public boolean benchmarkEquivalent() {
        return SchemaAlgebra.equivalent(schema1, schema2);
    }
}
