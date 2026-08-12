package dev.omnist.codec;

import dev.omnist.document.Document;
import dev.omnist.document.Edge;
import dev.omnist.document.Node;
import dev.omnist.document.Scalar;
import dev.omnist.document.Value;

import dev.omnist.schema.OsdReader;
import dev.omnist.schema.Schema;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodecCoverageTest {

    @Test
    void testJsonCodecAllTypes() {
        Node doc = new Node(List.of(
            new Edge("str", new Scalar.StringScalar("hello")),
            new Edge("intVal", new Scalar.IntegerScalar(BigInteger.valueOf(42))),
            new Edge("numVal", new Scalar.NumberScalar(3.14)),
            new Edge("boolVal", new Scalar.BooleanScalar(true)),
            new Edge("nullVal", Value.NullValue.INSTANCE),
            new Edge("dateVal", new Scalar.DateScalar(LocalDate.of(2024, 5, 20))),
            new Edge("timeVal", new Scalar.TimeScalar(dev.omnist.document.TimeValue.of(LocalTime.of(12, 34, 56)))),
            new Edge("dateTimeVal", new Scalar.DateTimeScalar(dev.omnist.document.DateTimeValue.of(LocalDateTime.of(2024, 5, 20, 12, 34, 56)))),
            new Edge("listVal", new Scalar.IntegerScalar(BigInteger.valueOf(1))),
            new Edge("listVal", new Scalar.IntegerScalar(BigInteger.valueOf(2))),
            new Edge("recVal", new Node(List.of(new Edge("subField", new Scalar.StringScalar("sub")))))
        ));

        String json = JsonCodec.write(doc);
        assertNotNull(json);
        Document read = JsonCodec.read(json);
        assertNotNull(read);

        // Invalid JSON read
        assertThrows(Exception.class, () -> JsonCodec.read("{ invalid json "));
    }

    @Test
    void testYamlCodecAllTypes() {
        Node doc = new Node(List.of(
            new Edge("str", new Scalar.StringScalar("hello")),
            new Edge("intVal", new Scalar.IntegerScalar(BigInteger.valueOf(42))),
            new Edge("numVal", new Scalar.NumberScalar(3.14)),
            new Edge("boolVal", new Scalar.BooleanScalar(true)),
            new Edge("nullVal", Value.NullValue.INSTANCE),
            new Edge("dateVal", new Scalar.DateScalar(LocalDate.of(2024, 5, 20))),
            new Edge("timeVal", new Scalar.TimeScalar(dev.omnist.document.TimeValue.of(LocalTime.of(12, 34, 56)))),
            new Edge("dateTimeVal", new Scalar.DateTimeScalar(dev.omnist.document.DateTimeValue.of(LocalDateTime.of(2024, 5, 20, 12, 34, 56)))),
            new Edge("listVal", new Scalar.IntegerScalar(BigInteger.valueOf(1))),
            new Edge("listVal", new Scalar.IntegerScalar(BigInteger.valueOf(2))),
            new Edge("recVal", new Node(List.of(new Edge("subField", new Scalar.StringScalar("sub")))))
        ));

        String yaml = YamlCodec.write(doc);
        assertNotNull(yaml);
        Document read = YamlCodec.read(yaml);
        assertNotNull(read);

        // Invalid YAML read
        assertThrows(Exception.class, () -> YamlCodec.read(": : : invalid yaml"));
    }

    @Test
    void testTomlCodecAllTypes() {
        Node doc = new Node(List.of(
            new Edge("str", new Scalar.StringScalar("hello")),
            new Edge("intVal", new Scalar.IntegerScalar(BigInteger.valueOf(42))),
            new Edge("numVal", new Scalar.NumberScalar(3.14)),
            new Edge("boolVal", new Scalar.BooleanScalar(true)),
            new Edge("dateVal", new Scalar.DateScalar(LocalDate.of(2024, 5, 20))),
            new Edge("timeVal", new Scalar.TimeScalar(dev.omnist.document.TimeValue.of(LocalTime.of(12, 34, 56)))),
            new Edge("dateTimeVal", new Scalar.DateTimeScalar(dev.omnist.document.DateTimeValue.of(LocalDateTime.of(2024, 5, 20, 12, 34, 56)))),
            new Edge("listVal", new Scalar.IntegerScalar(BigInteger.valueOf(1))),
            new Edge("listVal", new Scalar.IntegerScalar(BigInteger.valueOf(2))),
            new Edge("recVal", new Node(List.of(new Edge("subField", new Scalar.StringScalar("sub")))))
        ));

        String toml = TomlCodec.write(doc);
        assertNotNull(toml);
        Document read = TomlCodec.read(toml);
        assertNotNull(read);

        // Invalid TOML read
        assertThrows(Exception.class, () -> TomlCodec.read("invalid = ["));
    }

    @Test
    void testXmlCodecAllTypes() {
        Schema schema = OsdReader.read("""
            record Sub {
                "subField": string,
            }
            record Root {
                "str": string,
                "intVal": integer,
                "numVal": number,
                "boolVal": boolean,
                "dateVal": date,
                "timeVal": time,
                "dateTimeVal": datetime,
                "listVal" [0,]: integer,
                "recVal" [0,1]: Sub,
            }
            root Root
            """);

        Node rootNode = new Node(List.of(
            new Edge("str", new Scalar.StringScalar("hello")),
            new Edge("intVal", new Scalar.IntegerScalar(BigInteger.valueOf(42))),
            new Edge("numVal", new Scalar.NumberScalar(3.14)),
            new Edge("boolVal", new Scalar.BooleanScalar(true)),
            new Edge("dateVal", new Scalar.DateScalar(LocalDate.of(2024, 5, 20))),
            new Edge("timeVal", new Scalar.TimeScalar(dev.omnist.document.TimeValue.of(LocalTime.of(12, 34, 56)))),
            new Edge("dateTimeVal", new Scalar.DateTimeScalar(dev.omnist.document.DateTimeValue.of(LocalDateTime.of(2024, 5, 20, 12, 34, 56)))),
            new Edge("listVal", new Scalar.IntegerScalar(BigInteger.valueOf(1))),
            new Edge("listVal", new Scalar.IntegerScalar(BigInteger.valueOf(2))),
            new Edge("recVal", new Node(List.of(new Edge("subField", new Scalar.StringScalar("sub")))))
        ));

        Node doc = new Node(List.of(new Edge("Root", rootNode)));

        String xml = XmlCodec.write(doc);
        assertNotNull(xml);
        Document read = XmlCodec.read(xml, schema);
        assertNotNull(read);

        Document readSchemaless = XmlCodec.read(xml);
        assertNotNull(readSchemaless);

        // Invalid XML read
        assertThrows(Exception.class, () -> XmlCodec.read("<unclosed>"));
    }
}
