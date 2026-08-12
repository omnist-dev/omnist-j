# omnist-j API Reference

Comprehensive API reference for `omnist-j`, the Java implementation of the Omnist data-interchange specification.

---

## Overview & Package Architecture

`omnist-j` is organized into the following public packages:

- [`dev.omnist.oml`](#oml-reader--writer): OML parsing and serialization.
- [`dev.omnist.schema`](#osd-reader--writer): OSD schema parsing and serialization.
- [`dev.omnist.document`](#document-model): Document tree model (`Document`, `Node`, `Edge`, `Value`, `Target`, `Scalar`, `Limits`).
- [`dev.omnist.validation`](#validation--materialization): Schema validation (`Validator`) and value materialization (`Materializer`).
- [`dev.omnist.algebra`](#schema-algebra): Schema algebra operations (`SchemaAlgebra`).
- [`dev.omnist.codec`](#format-codecs): Third-party format codecs (`JsonCodec`, `YamlCodec`, `TomlCodec`, `XmlCodec`).

---

## Document Model

### `Document`
Root sealed interface representing an Omnist Document (`Node` or `Value`).

### `Node`
Record representing an ordered map of edges: `public record Node(List<Edge> edges) implements Document`

### `Edge`
Record representing a labeled edge: `public record Edge(String label, Target target)`

### `Value`
Record representing a scalar value: `public record Value(Scalar scalar) implements Document, Target`

### `Scalar`
Scalar values (`StringScalar`, `IntScalar`, `FloatScalar`, `BoolScalar`, `NullScalar`, `DateValue`, `TimeValue`, `DateTimeValue`).

<!-- test-backed: dev.omnist.DocTest#testDocumentConstructionExample -->
```java
Node node = new Node(List.of(
    new Edge("title", new Scalar.StringScalar("Project"))
));
assertEquals("title", node.edges().get(0).label());
```

### `Limits`
Guards for parser recursion depth, node count, and integer digit limits.

#### `new Limits(int maxDepth, int maxNodeCount, int maxIntegerDigits)`

<!-- test-backed: dev.omnist.DocTest#testLimitsExample -->
```java
Limits limits = new Limits(2, 50, 100);
assertEquals(2, limits.maxDepth());
assertEquals(50, limits.maxNodeCount());
assertEquals(100, limits.maxIntegerDigits());
```

---

## OML Reader & Writer

### `OmlReader`
Parses Omnist Markup Language (OML) text into a `Document`.

#### `OmlReader.read(String text)`
#### `OmlReader.read(String text, Limits limits)`

<!-- test-backed: dev.omnist.DocTest#testOmlReaderExample -->
```java
String oml = "name: \"Alice\"\nage: 30\n";
Document doc = OmlReader.read(oml);
assertTrue(doc instanceof Node);
Node root = (Node) doc;
assertEquals("name", root.edges().get(0).label());
```

### `OmlWriter`
Serializes a `Document` to canonical OML string.

#### `OmlWriter.write(Document doc)`

<!-- test-backed: dev.omnist.DocTest#testOmlWriterExample -->
```java
Document doc = OmlReader.read("name: \"Alice\"\nage: 30\n");
String oml = OmlWriter.write(doc);
assertTrue(oml.contains("name: \"Alice\""));
```

---

## OSD Reader & Writer

### `OsdReader`
Parses Omnist Schema Definition (OSD) text into a `Schema`.

#### `OsdReader.read(String text)`

<!-- test-backed: dev.omnist.DocTest#testOsdReaderExample -->
```java
String schemaText = "schema = record Person { name: string, age: int }\n";
Schema schema = OsdReader.read(schemaText);
assertEquals("Person", schema.root());
```

### `OsdWriter`
Serializes a `Schema` to canonical OSD text.

#### `OsdWriter.write(Schema schema)`

<!-- test-backed: dev.omnist.DocTest#testOsdWriterExample -->
```java
String schemaText = "schema = record Person { name: string, age: int }\n";
Schema schema = OsdReader.read(schemaText);
String written = OsdWriter.write(schema);
assertTrue(written.contains("record Person"));
```

---

## Validation & Materialization

### `Validator`
Validates an Omnist `Document` against an OSD `Schema`.

#### `Validator.validate(Document doc, Schema schema)`

<!-- test-backed: dev.omnist.DocTest#testValidatorExample -->
```java
Schema schema = OsdReader.read("schema = record Person { name: string, age: int }\n");
Document validDoc = OmlReader.read("name: \"Bob\"\nage: 25\n");
ValidationResult res = Validator.validate(validDoc, schema);
assertTrue(res.isValid());
assertTrue(res.diagnostics().isEmpty());
```

### `Materializer`
Upgrades scalar types (e.g. string to date/datetime) according to schema target types.

#### `Materializer.materialize(Document doc, Schema schema)`

<!-- test-backed: dev.omnist.DocTest#testMaterializerExample -->
```java
Schema schema = OsdReader.read("schema = record Item { created: date }\n");
Document doc = OmlReader.read("created: \"2024-01-01\"\n");
Document materialized = Materializer.materialize(doc, schema);
assertNotNull(materialized);
```

---

## Schema Algebra

`dev.omnist.algebra.SchemaAlgebra` provides formal schema set-theoretic and algebraic operations.

### `satisfiableSet(Schema schema)`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraSatisfiableSet -->
```java
Schema schema = OsdReader.read("schema = record Root { id: int }\n");
Set<String> set = SchemaAlgebra.satisfiableSet(schema);
assertTrue(set.contains("Root"));
```

### `isEmpty(Schema schema)`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraIsEmpty -->
```java
Schema schema = OsdReader.read("schema = record Root { id: int }\n");
assertFalse(SchemaAlgebra.isEmpty(schema));
```

### `prune(Schema schema)`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraPrune -->
```java
Schema schema = OsdReader.read("schema = record Root { id: int }\nrecord Unused { x: string }\n");
Schema pruned = SchemaAlgebra.prune(schema);
assertFalse(pruned.records().containsKey("Unused"));
```

### `compatibleWith(Schema s1, Schema s2)`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraCompatibleWith -->
```java
Schema s1 = OsdReader.read("schema = record Root { id: int }\n");
Schema s2 = OsdReader.read("schema = record Root { id: int, name?: string }\n");
assertTrue(SchemaAlgebra.compatibleWith(s1, s2));
```

### `equivalent(Schema s1, Schema s2)`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraEquivalent -->
```java
Schema s1 = OsdReader.read("schema = record Root { id: int }\n");
Schema s2 = OsdReader.read("schema = record Root { id: int }\n");
assertTrue(SchemaAlgebra.equivalent(s1, s2));
```

### `normalize(Schema schema)`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraNormalize -->
```java
Schema schema = OsdReader.read("schema = record Root { id: int }\n");
Schema norm = SchemaAlgebra.normalize(schema);
assertNotNull(norm);
```

### `equivalenceClasses(Schema schema)`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraEquivalenceClasses -->
```java
Schema schema = OsdReader.read("schema = record Root { id: int }\n");
List<List<String>> classes = SchemaAlgebra.equivalenceClasses(schema);
assertFalse(classes.isEmpty());
```

### `extract(Schema schema, Set<String> fieldPaths)`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraExtract -->
```java
Schema schema = OsdReader.read("schema = record Root { id: int, secret?: string }\n");
Schema extracted = SchemaAlgebra.extract(schema, Set.of("id"));
assertNotNull(extracted);
```

### `lint(Schema schema)`
<!-- test-backed: dev.omnist.DocTest#testSchemaAlgebraLint -->
```java
Schema schema = OsdReader.read("schema = record Root { id: int }\nrecord Dead { x: string }\n");
List<LintFinding> findings = SchemaAlgebra.lint(schema);
assertEquals("lint.unreachable-record", findings.get(0).code());
```

### `infer(List<Document> samples)`
### `inferWithReport(List<Document> samples, String rootName, boolean allowAny)`
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

## Format Codecs

### `JsonCodec`
Read and write JSON documents.

<!-- test-backed: dev.omnist.DocTest#testJsonCodecExample -->
```java
String json = "{\"name\":\"Alice\",\"age\":30}";
Document doc = JsonCodec.read(json);
assertTrue(doc instanceof Node);
String jsonOut = JsonCodec.write(doc);
```

### `YamlCodec`
Read and write YAML documents (bounded by 2MB input size cap).

<!-- test-backed: dev.omnist.DocTest#testYamlCodecExample -->
```java
String yaml = "name: Alice\nage: 30\n";
Document doc = YamlCodec.read(yaml);
assertTrue(doc instanceof Node);
String yamlOut = YamlCodec.write(doc);
```

### `TomlCodec`
Read and write TOML documents (bounded by 2MB input size cap).

<!-- test-backed: dev.omnist.DocTest#testTomlCodecExample -->
```java
String toml = "name = \"Alice\"\nage = 30\n";
Document doc = TomlCodec.read(toml);
assertTrue(doc instanceof Node);
String tomlOut = TomlCodec.write(doc);
```

### `XmlCodec`
Read and write XML documents (secure configuration blocking XXE/DTD and bounded by 2MB input size cap).

<!-- test-backed: dev.omnist.DocTest#testXmlCodecExample -->
```java
String xml = "<Person><name>Alice</name><age>30</age></Person>";
Document doc = XmlCodec.read(xml);
assertNotNull(doc);
String xmlOut = XmlCodec.write(doc);
```
