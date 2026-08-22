# omnist-j

A from-scratch Java implementation of [Omnist](https://github.com/omnist-dev/omnist-spec), a spec-first data-interchange format built around one uniform document graph instead of one-struct-per-format. Read and write JSON, YAML, TOML, XML, and Omnist's own native text format (OML) through the same model, validate documents against a formal schema (OSD), and reason about schemas themselves — compatibility, equivalence, pruning, inference — via a real set-theoretic algebra instead of ad hoc heuristics.

Full docs: [j.omnist.dev](https://j.omnist.dev) · Javadoc: [j.omnist.dev/javadoc](https://j.omnist.dev/javadoc/)

## Quickstart

```java
// Parse an OML document
String oml = """
    name: "Alice"
    created: "2024-01-01"
    role: "Admin"
    """;
Document doc = OmlReader.read(oml);

// Define a schema
String osd = """
    record User {
        "name": string,
        "created": date,
        "role" [0,1]: string,
    }
    root User
    """;
Schema schema = OsdReader.read(osd);

// Coerce strings to typed values per the schema, then validate
Document materialized = Materializer.materialize(doc, schema);
ValidationResult result = Validator.validate(materialized, schema);
assert result.isValid();

// Convert to any other supported format
String json = JsonCodec.write(materialized);
```

See [`docs/00-guide.md`](docs/00-guide.md) for the full mental model and a walkthrough of every operation.

## Building and running

Requires JDK 21 and Maven.

```bash
mvn clean package                              # runs tests, builds target/omnist-j-<version>.jar
java -jar target/omnist-j-<version>.jar format sample.oml --to json
```

Or use the `omnist` wrapper script in the repo root once built:

```bash
./omnist format sample.oml --to json
```

See [`docs/02-cli-reference.md`](docs/02-cli-reference.md) for every subcommand.

## Documentation

| | |
|---|---|
| [`docs/00-guide.md`](docs/00-guide.md) | Mental model and a full worked example |
| [`docs/01-api-reference.md`](docs/01-api-reference.md) | Complete Java API reference |
| [`docs/02-cli-reference.md`](docs/02-cli-reference.md) | Every CLI subcommand |
| [Javadoc](https://j.omnist.dev/javadoc/) | Generated API docs, straight from source |
| [`docs/limitations.md`](docs/limitations.md) | Current status, conformance, and coverage numbers |
| [`docs/workflow-playbook.md`](docs/workflow-playbook.md) | Engineering rules and contribution workflow for this repo |

## Status

**`v0.2.0-alpha`** — spec-first, built directly against [`vendor/omnist-spec`](https://github.com/omnist-dev/omnist-spec) (pinned as a git submodule, the normative source of truth for this port's behavior).

- **Conformance**: 181/181 (100%) passing against the shared spec test suite, across CLI fixtures and JSON test vectors.
- **Tests**: 584 passing, 0 failures — JUnit plus jqwik property-based and fuzz testing.
- **Coverage**: 99.83% line / 99.75% branch (gated in CI). Every remaining gap is a documented, verified trip-wire, not an untested code path — see [`docs/limitations.md`](docs/limitations.md) for the full breakdown and why each one is unreachable.

## Sibling ports

Omnist has five implementations sharing one spec and one conformance suite:

- **Specification**: [omnist-spec](https://github.com/omnist-dev/omnist-spec)
- **Python** (reference): [omnist](https://github.com/omnist-dev/omnist)
- **TypeScript**: [omnist-ts](https://github.com/omnist-dev/omnist-ts)
- **Rust**: [omnist-rs](https://github.com/omnist-dev/omnist-rs)
- **Go**: [omnist-go](https://github.com/omnist-dev/omnist-go)

## License

[Apache 2.0](LICENSE)
