# omnist-j

A from-scratch Java implementation of [Omnist](https://github.com/omnist-dev/omnist-spec), a spec-first data-interchange format built around one uniform document graph instead of one-struct-per-format. Read and write JSON, YAML, TOML, XML, and Omnist's own native text format (OML) through the same model, validate documents against a formal schema (OSD), and reason about schemas themselves — compatibility, equivalence, pruning, inference — via a real set-theoretic algebra instead of ad hoc heuristics.

Full docs: [j.omnist.dev](https://j.omnist.dev) · Javadoc: [j.omnist.dev/javadoc](https://j.omnist.dev/javadoc/)

## Installing

Available on [Maven Central](https://central.sonatype.com/artifact/dev.omnist/omnist-j) as `dev.omnist:omnist-j`.

**Maven:**
```xml
<dependency>
    <groupId>dev.omnist</groupId>
    <artifactId>omnist-j</artifactId>
    <version>0.3.0-alpha</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'dev.omnist:omnist-j:0.3.0-alpha'
```

This is a plain library jar with its real dependencies (Jackson, SnakeYAML, tomlj) resolved normally — nothing bundled or shaded. If you want to run `omnist` as a standalone CLI instead of using it as a library, use the `cli` classifier, which is a self-contained fat jar:
```bash
curl -O https://repo1.maven.org/maven2/dev/omnist/omnist-j/0.3.0-alpha/omnist-j-0.3.0-alpha-cli.jar
java -jar omnist-j-0.3.0-alpha-cli.jar format sample.oml --to json
```

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
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Engineering rules and contribution workflow for this repo |

## Status

**`v0.3.0-alpha`** — spec-first, built directly against [`vendor/omnist-spec`](https://github.com/omnist-dev/omnist-spec) (pinned as a git submodule, the normative source of truth for this port's behavior), currently pinned to v0.26.0-beta (commit `7744a5c`; the tag was not pushed when this was written).

- **Conformance**: 332 passing, 0 failures, 28 skipped against the shared spec test suite, across CLI fixtures (29, of which 19 comparable with the other ports) and JSON test vectors (303 pass), compared as (path, code) sets. The only skips are the not-yet-implemented OSD-OML extension (28); see [`docs/limitations.md`](docs/limitations.md).
- **YAML aliases**: bounded by the spec's two limits (D-18 expansion factor, default 50; D-22 expanded size, default 1,000,000 slots, only for input that uses an alias or merge key), checked on the parsed node graph before anything is expanded; configurable with `YamlCodec.readWithLimits(text, new YamlLimits(...))`. A mapping that merges a large block reads an expansion factor of about `(keys + 2) / 3`, so very large merges can need a higher maximum; see [`docs/limitations.md`](docs/limitations.md).
- **Tests**: 796 passing, 0 failures — JUnit plus jqwik property-based and fuzz testing.
- **Coverage**: 99.68% line / 99.24% branch (gated in CI). Every remaining gap is a documented, verified trip-wire, not an untested code path — see [`docs/limitations.md`](docs/limitations.md) for the full breakdown and why each one is unreachable.

## Sibling ports

Omnist has five implementations sharing one spec and one conformance suite:

- **Specification**: [omnist-spec](https://github.com/omnist-dev/omnist-spec)
- **Python** (reference): [omnist](https://github.com/omnist-dev/omnist)
- **TypeScript**: [omnist-ts](https://github.com/omnist-dev/omnist-ts)
- **Rust**: [omnist-rs](https://github.com/omnist-dev/omnist-rs)
- **Go**: [omnist-go](https://github.com/omnist-dev/omnist-go)

## License

[Apache 2.0](LICENSE)
