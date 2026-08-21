package dev.omnist.benchmark;

import dev.omnist.codec.JsonCodec;
import dev.omnist.document.Document;
import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar;
import org.openjdk.jmh.annotations.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class WriterIntermediateTreeBenchmark {

    @Param({"10000", "100000", "1000000"})
    private int nodeCount;

    private Document document;

    @Setup(Level.Trial)
    public void setup() {
        List<Edge> edges = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            edges.add(new Edge("k_" + (i % 100), new Scalar.IntegerScalar(BigInteger.valueOf(i))));
        }
        this.document = new Node(edges);
    }

    @Benchmark
    public String benchmarkJsonWrite() {
        return JsonCodec.write(document);
    }
}
