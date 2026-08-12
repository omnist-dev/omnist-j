# Omnist Java (omnist-j) Guide & Quickstart

Welcome to `omnist-j`, the official Java implementation of the [Omnist data-interchange specification](https://github.com/omnist-dev/omnist-spec).

---

## 1. The Omnist Mental Model

Omnist is a spec-first data interchange system based on a simple, uniform document graph (§2.1).

### Key Concepts

1. **Document = Node | Value**:
   - A document is either a **`Node`** or a **`Value`** (a scalar or `null`).
2. **Ordered Edge Lists (No Native Arrays)**:
   - A `Node` is simply an ordered list of `Edge(label, target)` pairs.
   - Omnist has no separate array data structure: **repeated edge labels represent arrays**. For instance:
     ```oml
     items: 10
     items: 20
     ```
     This represents a single `Node` containing two edges with the label `"items"`.
3. **Lossless Multi-Format Interchange**:
   - Omnist Markup Language (OML) is the native human-readable text syntax for the Document model.
   - `omnist-j` provides bidirectional codecs for JSON, YAML, TOML, and XML, preserving ordering and structural intent.
4. **Formal Schema Algebra**:
   - Omnist Schema Definition (OSD) provides exact record type definitions, optionality, cardinalities (`[min,max]`), and set-theoretic schema operations (satisfiability, compatibility, equivalence, normalization, pruning, extraction, and inference).

---

## 2. Worked Quickstart Example

Here is a complete end-to-end example demonstrating OML parsing, OSD schema validation, materialization, and multi-format conversion.

<!-- test-backed: dev.omnist.DocTest#testQuickstartGuideExample -->
```java
// 1. Parse OML document
String oml = """
    name: "Alice"
    created: "2024-01-01"
    role: "Admin"
    """;
Document doc = OmlReader.read(oml);

// 2. Define OSD Schema
String osd = """
    record User {
        "name": string,
        "created": date,
        "role" [0,1]: string,
    }
    root User
    """;
Schema schema = OsdReader.read(osd);

// 3. Materialize String to Typed Date
Document materialized = Materializer.materialize(doc, schema);
assertNotNull(materialized);

// 4. Validate Materialized Document against Schema
ValidationResult valResult = Validator.validate(materialized, schema);
assertTrue(valResult.isValid());

// 5. Convert to Canonical JSON
String json = JsonCodec.write(materialized);
assertTrue(json.contains("\"Alice\""));
```

---

## 3. Overview of Core Operations

### Reading & Writing Formats
- **OML**: `OmlReader.read(text)` / `OmlWriter.write(doc)`
- **JSON**: `JsonCodec.read(text)` / `JsonCodec.write(doc)`
- **YAML**: `YamlCodec.read(text)` / `YamlCodec.write(doc)`
- **TOML**: `TomlCodec.read(text)` / `TomlCodec.write(doc)`
- **XML**: `XmlCodec.read(text)` / `XmlCodec.write(doc)`

### Validation & Materialization
- **`Validator.validate(doc, schema)`**: Performs structural and type-checking against schema constraints, returning a `ValidationResult`.
- **`Materializer.materialize(doc, schema)`**: Coerces scalar values (e.g. ISO-8601 string to `LocalDate` / `LocalDateTime`) based on target schema types.

### Schema Algebra (`SchemaAlgebra`)
- **`compatibleWith(s1, s2)`**: Checks if schema `s1` is forward-compatible with schema `s2`.
- **`equivalent(s1, s2)`**: Verifies set-theoretic equivalence of two schemas.
- **`normalize(schema)`**: Merges isomorphic records into a minimal canonical schema.
- **`prune(schema)`**: Removes unreachable record definitions.
- **`extract(schema, fields)`**: Projects a schema down to specified label paths.
- **`infer(samples)`**: Infers an OSD schema from sample document instances.

---

## 4. Documentation Index

- [`docs/01-api-reference.md`](01-api-reference.md): Complete Java API Reference for all public classes and methods.
- [`docs/02-cli-reference.md`](02-cli-reference.md): Complete Command-Line Interface (CLI) Reference.
- [`workflow-playbook.md`](../workflow-playbook.md): Development & Spec Alignment Playbook.
