package dev.omnist.oml;

import dev.omnist.document.Document;
import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OmlCoverageTest {

    @Test
    void testOmlWriterCompactAndAllTypes() {
        Node doc = new Node(List.of(
            new Edge("str", new Scalar.StringScalar("hello")),
            new Edge("intVal", new Scalar.IntegerScalar(BigInteger.valueOf(42))),
            new Edge("numVal", new Scalar.NumberScalar(3.14)),
            new Edge("boolVal", new Scalar.BooleanScalar(true)),
            new Edge("dateVal", new Scalar.DateScalar(LocalDate.of(2024, 5, 20))),
            new Edge("timeVal", new Scalar.TimeScalar(dev.omnist.document.TimeValue.of(LocalTime.of(12, 34, 56)))),
            new Edge("dateTimeVal", new Scalar.DateTimeScalar(dev.omnist.document.DateTimeValue.of(LocalDateTime.of(2024, 5, 20, 12, 34, 56)))),
            new Edge("recVal", new Node(List.of(new Edge("subField", new Scalar.StringScalar("sub")))))
        ));

        String compact = OmlWriter.writeCompact(doc);
        assertNotNull(compact);

        String full = OmlWriter.write(doc);
        assertNotNull(full);

        Document readCompact = OmlReader.read(compact);
        assertNotNull(readCompact);

        Document readFull = OmlReader.read(full);
        assertNotNull(readFull);
    }

    @Test
    void testOmlReaderEdgeCases() {
        // Comments and empty lines
        String oml = """
            # Top comment
            key: "val" # Inline comment
            
            # Another comment
            arr: 1
            arr: 2
            """;
        Document doc = OmlReader.read(oml);
        assertNotNull(doc);

        // Invalid escape in string
        assertThrows(Exception.class, () -> OmlReader.read("key: \"invalid \\x escape\"\n"));

        // Unexpected unclosed quote
        assertThrows(Exception.class, () -> OmlReader.read("key: \"unclosed string\n"));
    }
}
