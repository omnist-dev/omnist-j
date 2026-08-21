package dev.omnist.benchmark;

import dev.omnist.schema.Field;
import dev.omnist.schema.Record;
import dev.omnist.schema.ScalarKind;
import dev.omnist.schema.Type;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class RecordFieldBenchmark {

    @Param({"50", "200", "1000", "5000"})
    private int fieldCount;

    private Record record;
    private String hitKey;
    private String missKey;

    @Setup(Level.Trial)
    public void setup() {
        List<Field> fields = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            fields.add(new Field("field_" + i, new Type.Scalar(ScalarKind.STRING, false), 1, 1));
        }
        this.record = new Record("BenchRecord", fields);
        this.hitKey = "field_" + (fieldCount - 1);
        this.missKey = "non_existent_field";
    }

    @Benchmark
    public Field benchmarkHitLastField() {
        return record.field(hitKey);
    }

    @Benchmark
    public Field benchmarkMissField() {
        return record.field(missKey);
    }
}
