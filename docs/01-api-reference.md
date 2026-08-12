# omnist-j API Reference

Comprehensive API reference for `omnist-j`, verified directly against the underlying Java source declarations.

---

## Package Index

- [`dev.omnist.document`](#document-model): Document graph types (`Document`, `Target`, `Node`, `Edge`, `Value`, `Scalar`, `Limits`).
- [`dev.omnist.oml`](#oml-reader--writer): Native OML reader and writer (`OmlReader`, `OmlWriter`, `OmlParseException`).
- [`dev.omnist.schema`](#osd-reader--writer): OSD schema definition types (`Schema`, `Record`, `Field`, `Cardinality`, `TargetType`, `OsdReader`, `OsdWriter`, `OsdParseException`).
- [`dev.omnist.validation`](#validation--materialization): Validation engine (`Validator`, `ValidationResult`, `ValidationDiagnostic`, `Materializer`).
- [`dev.omnist.algebra`](#schema-algebra): Formal schema algebra operations (`SchemaAlgebra`, `InferResult`, `AnyFallback`, `LintFinding`).
- [`dev.omnist.codec`](#format-codecs): External format codecs (`JsonCodec`, `YamlCodec`, `TomlCodec`, `XmlCodec`).

---

## Document Model (`dev.omnist.document`)

### `Document`
`public sealed interface Document permits Node, Value`

Root interface representing an Omnist Document (omnist-spec §2.2). A Document is either a `Node` or a `Value`.

### `Target`
`public sealed interface Target permits Node, Value`

Target of a labeled `Edge`.

### `Node`
`public record Node(List<Edge> edges) implements Target, Document`

Represents a node containing an ordered list of labeled edges.

### `Edge`
`public record Edge(String label, Target target)`

Represents a labeled edge connecting a `Node` to a `Target` (`Node` or `Value`).

### `Value`
`public sealed interface Value extends Target, Document permits Scalar, Value.NullValue`

Sealed interface representing a value in the Document model (`Scalar` or `Value.NullValue`).
- `Value.NULL`: `public static final NullValue NULL = NullValue.INSTANCE;`
- `Value.NullValue`: `public record NullValue() implements Value`

### `Scalar`
`public sealed interface Scalar extends Value`

Sealed interface representing scalar values (omnist-spec §2.2.1). Permitted record variants:
1. `Scalar.StringScalar(String value)` — `kind() = ScalarKind.STRING`
2. `Scalar.IntegerScalar(BigInteger value)` — `kind() = ScalarKind.INTEGER`
3. `Scalar.NumberScalar(double value)` — `kind() = ScalarKind.NUMBER`
4. `Scalar.BooleanScalar(boolean value)` — `kind() = ScalarKind.BOOLEAN`
5. `Scalar.DateScalar(LocalDate value)` — `kind() = ScalarKind.DATE`
6. `Scalar.TimeScalar(TimeValue value)` — `kind() = ScalarKind.TIME` (`TimeValue(LocalTime time, ZoneOffset offset)`)
7. `Scalar.DateTimeScalar(DateTimeValue value)` — `kind() = ScalarKind.DATE_TIME` (`DateTimeValue(LocalDateTime dateTime, ZoneOffset offset)`)

<!-- test-backed: dev.omnist.DocTest#testDocumentConstructionExample -->
```java
Node node = new Node(List.of(
    new Edge("title", new Scalar.StringScalar("Project"))
));
assertEquals("title", node.edges().get(0).label());
```

### `TimeValue`
`public record TimeValue(LocalTime time, ZoneOffset offset)`

Represents a time of day with an optional UTC offset (omnist-spec §2.2.1).
- `public String format()` — Formats the time as a canonical ISO-8601 string (e.g. `12:30:00Z` or `12:30:00+02:00`).

### `DateTimeValue`
`public record DateTimeValue(LocalDateTime dateTime, ZoneOffset offset)`

Represents a combined date and time with an optional UTC offset (omnist-spec §2.2.1).
- `public String format()` — Formats the date-time as a canonical ISO-8601 string (e.g. `2024-01-01T12:30:00Z`).

<!-- test-backed: dev.omnist.DocTest#testTemporalValueFormattingExample -->
```java
TimeValue tv = TimeValue.of(java.time.LocalTime.of(12, 30, 0), java.time.ZoneOffset.UTC);
assertEquals("12:30Z", tv.format());

DateTimeValue dtv = DateTimeValue.of(java.time.LocalDateTime.of(2024, 1, 1, 12, 30, 0), java.time.ZoneOffset.UTC);
assertEquals("2024-01-01T12:30Z", dtv.format());
```

### `Limits`
`public record Limits(int maxDepth, int maxNodeCount, int maxIntegerDigits)`

Guard parameters for parser recursion depth, node count, and integer digit limits. Default limits: `maxDepth = 200`, `maxNodeCount = 1_000_000`, `maxIntegerDigits = 4300`.

<!-- test-backed: dev.omnist.DocTest#testLimitsExample -->
```java
Limits limits = new Limits(2, 50, 100);
assertEquals(2, limits.maxDepth());
assertEquals(50, limits.maxNodeCount());
assertEquals(100, limits.maxIntegerDigits());
```

---

## OML Reader & Writer (`dev.omnist.oml`)

### `OmlReader`
- `public static Document read(String text)`
- `public static Document read(String text, Limits limits)`

Parses OML text into a `Document` tree. Throws `OmlParseException` on invalid syntax or limit violations.

<!-- test-backed: dev.omnist.DocTest#testOmlReaderExample -->
```java
String oml = "name: \"Alice\"\nage: 30\n";
Document doc = OmlReader.read(oml);
assertTrue(doc instanceof Node);
Node root = (Node) doc;
assertEquals("name", root.edges().get(0).label());
```

### `OmlWriter`
- `public static String write(Document doc)`

Serializes a `Document` tree into canonical OML text format.

<!-- test-backed: dev.omnist.DocTest#testOmlWriterExample -->
```java
Document doc = OmlReader.read("name: \"Alice\"\nage: 30\n");
String oml = OmlWriter.write(doc);
assertTrue(oml.contains("name: \"Alice\""));
```

---

## OSD Reader & Writer (`dev.omnist.schema`)

### `Schema`
`public record Schema(String root, Map<String, Record> records)`

### `Record`
`public record Record(String name, List<Field> fields)`

### `Field`
`public record Field(String label, Cardinality cardinality, TargetType targetType)`

### `OsdReader`
- `public static Schema read(String text)`

Parses OSD schema text into a `Schema`. Throws `OsdParseException` on syntax errors.

<!-- test-backed: dev.omnist.DocTest#testOsdReaderExample -->
```java
String schemaText = "record Person {\n  \"name\": string,\n  \"age\": integer,\n}\nroot Person\n";
Schema schema = OsdReader.read(schemaText);
assertEquals("Person", schema.root());
```

### `OsdWriter`
- `public static String write(Schema schema)`

Serializes a `Schema` to canonical OSD text syntax.

<!-- test-backed: dev.omnist.DocTest#testOsdWriterExample -->
```java
String schemaText = "record Person {\n  \"name\": string,\n  \"age\": integer,\n}\nroot Person\n";
Schema schema = OsdReader.read(schemaText);
String written = OsdWriter.write(schema);
assertTrue(written.contains("record Person"));
```

---

## Validation & Materialization (`dev.omnist.validation`)

### `ValidationResult`
`public record ValidationResult(boolean isValid, List<ValidationDiagnostic> diagnostics)`

### `ValidationDiagnostic`
`public record ValidationDiagnostic(String path, String code, String message)`

### `Validator`
- `public static ValidationResult validate(Document doc, Schema schema)`

Validates a `Document` against an OSD `Schema`.

<!-- test-backed: dev.omnist.DocTest#testValidatorExample -->
```java
Schema schema = OsdReader.read("record Person {\n  \"name\": string,\n  \"age\": integer,\n}\nroot Person\n");
Document validDoc = OmlReader.read("name: \"Bob\"\nage: 25\n");
ValidationResult res = Validator.validate(validDoc, schema);
assertTrue(res.isValid());
assertTrue(res.diagnostics().isEmpty());
```

### `Materializer`
- `public static Document materialize(Document doc, Schema schema)`

Upgrades scalar values (e.g. ISO-8601 strings to `DateScalar` / `DateTimeScalar`) per schema target types.

<!-- test-backed: dev.omnist.DocTest#testMaterializerExample -->
```java
Schema schema = OsdReader.read("record Item {\n  \"created\": date,\n}\nroot Item\n");
Document doc = OmlReader.read("created: \"2024-01-01\"\n");
Document materialized = Materializer.materialize(doc, schema);
assertNotNull(materialized);
```

---

## Schema Algebra (`dev.omnist.algebra`)

### `SchemaAlgebra`

#### `satisfiableSet(Schema schema)` -> `Set<String>`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraSatisfiableSet -->
```java
Schema schema = OsdReader.read("record Root {\n  \"id\": integer,\n}\nroot Root\n");
Set<String> set = SchemaAlgebra.satisfiableSet(schema);
assertTrue(set.contains("Root"));
```

#### `isEmpty(Schema schema)` -> `boolean`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraIsEmpty -->
```java
Schema schema = OsdReader.read("record Root {\n  \"id\": integer,\n}\nroot Root\n");
assertFalse(SchemaAlgebra.isEmpty(schema));
```

#### `prune(Schema schema)` -> `Schema`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraPrune -->
```java
Schema schema = OsdReader.read("record Root {\n  \"id\": integer,\n}\nrecord Dead {\n  \"x\": string,\n}\nroot Root\n");
Schema pruned = SchemaAlgebra.prune(schema);
assertFalse(pruned.records().containsKey("Dead"));
```

#### `compatibleWith(Schema s1, Schema s2)` -> `boolean`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraCompatibleWith -->
```java
Schema s1 = OsdReader.read("record Root {\n  \"id\": integer,\n}\nroot Root\n");
Schema s2 = OsdReader.read("record Root {\n  \"id\": integer,\n}\nroot Root\n");
assertTrue(SchemaAlgebra.compatibleWith(s1, s2));
```

#### `equivalent(Schema s1, Schema s2)` -> `boolean`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraEquivalent -->
```java
Schema s1 = OsdReader.read("record Root {\n  \"id\": integer,\n}\nroot Root\n");
Schema s2 = OsdReader.read("record Root {\n  \"id\": integer,\n}\nroot Root\n");
assertTrue(SchemaAlgebra.equivalent(s1, s2));
```

#### `normalize(Schema schema)` -> `Schema`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraNormalize -->
```java
Schema schema = OsdReader.read("record Root {\n  \"id\": integer,\n}\nroot Root\n");
Schema norm = SchemaAlgebra.normalize(schema);
assertNotNull(norm);
```

#### `equivalenceClasses(Schema schema)` -> `List<List<String>>`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraEquivalenceClasses -->
```java
Schema schema = OsdReader.read("record Root {\n  \"id\": integer,\n}\nroot Root\n");
List<List<String>> classes = SchemaAlgebra.equivalenceClasses(schema);
assertFalse(classes.isEmpty());
```

#### `extract(Schema schema, Set<String> fieldPaths)` -> `Schema`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraExtract -->
```java
Schema schema = OsdReader.read("record Root {\n  \"id\": integer,\n  \"secret\" [0,1]: string,\n}\nroot Root\n");
Schema extracted = SchemaAlgebra.extract(schema, Set.of("id"));
assertNotNull(extracted);
```

#### `lint(Schema schema)` -> `List<LintFinding>`
`public record LintFinding(String code, String severity, String location, String message)`

<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraLint -->
```java
Schema schema = OsdReader.read("record Root {\n  \"id\": integer,\n}\nrecord Dead {\n  \"x\": string,\n}\nroot Root\n");
List<LintFinding> findings = SchemaAlgebra.lint(schema);
assertEquals("lint.unreachable-record", findings.get(0).code());
```

#### `infer(List<Document> samples)` -> `Schema`
#### `inferWithReport(List<Document> samples, String rootName, boolean allowAny)` -> `InferResult`
`public record InferResult(Schema schema, List<AnyFallback> fallbacks)`

<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraInfer -->
```java
Document doc1 = OmlReader.read("id: 1\nname: \"A\"\n");
Document doc2 = OmlReader.read("id: 2\nname: \"B\"\n");
Schema inferred = SchemaAlgebra.infer(List.of(doc1, doc2));
assertTrue(inferred.records().containsKey("Root"));

InferResult res = SchemaAlgebra.inferWithReport(List.of(doc1, doc2), "Root", false);
assertNotNull(res.schema());
```

---

## Format Codecs (`dev.omnist.codec`)

### `JsonCodec`
- `public static Document read(String text)`
- `public static String write(Document doc)`

### `YamlCodec`
- `public static Document read(String text)` (bounded by 2MB `MAX_INPUT_LENGTH` cap)
- `public static String write(Document doc)`

### `TomlCodec`
- `public static Document read(String text)` (bounded by 2MB `MAX_INPUT_LENGTH` cap)
- `public static String write(Document doc)`

### `XmlCodec`
- `public static Document read(String text)` (secure XXE/DTD protection, bounded by 2MB `MAX_INPUT_LENGTH` cap)
- `public static String write(Document doc)`
