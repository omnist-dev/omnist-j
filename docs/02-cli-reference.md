# omnist CLI Reference

`omnist` CLI reference documentation for `omnist-j`.

---

## Overview & Invocation

The CLI executable fat jar can be invoked via:

```bash
java -jar target/omnist-j-0.0.2-alpha.jar <command> [subcommand] [options]
```

Or via `./run-conformance` / shell wrapper `omnist`:

```bash
omnist <command> [subcommand] [options]
```

---


---

## Global Options

- `--compact`: write output in compact single-line form where supported.
- `-o <file>`: write output to a file instead of standard output.
- `--debug`, `-v`: enable verbose debug error output including JVM stack traces.
- `--json`: format errors and boolean results as machine-readable JSON.

## Commands & Subcommands

### 1. `format`
Formats input text between OML, JSON, YAML, TOML, or XML.

<!-- test-backed: dev.omnist.cli.CliDocTest#testCliFormatExample -->
```bash
$ omnist format - --to json < input.oml
{"name":"Alice","age":30}
```

### 2. `validate`
Validates an input document against an OSD schema.

<!-- test-backed: dev.omnist.cli.CliDocTest#testCliValidateExample -->
```bash
$ omnist validate input.oml --schema schema.osd --json
{"ok":true}
```

### 3. `convert` (materialize)
Upgrades scalar string values to typed dates/datetimes per schema target types.

<!-- test-backed: dev.omnist.cli.CliDocTest#testCliConvertExample -->
```bash
$ omnist convert input.oml --schema schema.osd
created: 2024-01-01
```

### 4. `schema normalize`
Merges isomorphic record types in an OSD schema.

<!-- test-backed: dev.omnist.cli.CliDocTest#testCliSchemaNormalizeExample -->
```bash
$ omnist schema normalize schema.osd
record Person {
  "name": string,
}
root Person
```

### 5. `schema prune`
Removes unreachable record definitions from an OSD schema.

<!-- test-backed: dev.omnist.cli.CliDocTest#testCliSchemaPruneExample -->
```bash
$ omnist schema prune schema.osd
record Root {
  "id": integer,
}
root Root
```

### 6. `schema extract`
Extracts a sub-schema projecting specified field paths.

<!-- test-backed: dev.omnist.cli.CliDocTest#testCliSchemaExtractExample -->
```bash
$ omnist schema extract schema.osd --keep id
record Person {
  "id": integer,
}
root Person
```

### 7. `schema is-empty`
Checks if an OSD schema is empty (satisfiable by zero documents).

<!-- test-backed: dev.omnist.cli.CliDocTest#testCliSchemaIsEmptyExample -->
```bash
$ omnist schema is-empty schema.osd --result-format json
{"empty":false}
```

### 8. `schema compatible-with`
Checks if schema S1 is compatible forward with schema S2.

<!-- test-backed: dev.omnist.cli.CliDocTest#testCliSchemaCompatibleWithExample -->
```bash
$ omnist schema compatible-with schema1.osd schema2.osd --result-format json
{"compatible":true}
```

### 9. `schema equivalent`
Checks set-theoretic equivalence between two OSD schemas.

<!-- test-backed: dev.omnist.cli.CliDocTest#testCliSchemaEquivalentExample -->
```bash
$ omnist schema equivalent schema1.osd schema2.osd --result-format json
{"equivalent":true}
```

### 10. `schema lint`
Runs static analysis lint rules against an OSD schema. `--severity info|warning`
sets the minimum finding severity to report (default `info`, i.e. everything);
the exit code and `ok`/`--json` result reflect only the findings that pass
this filter.

<!-- test-backed: dev.omnist.cli.CliDocTest#testCliSchemaLintExample -->
```bash
$ omnist schema lint schema.osd
WARNING [lint.unreachable-record] at $: Unreachable record Dead
```

<!-- test-backed: dev.omnist.cli.CliDocTest#testCliSchemaLintSeverityExample -->
```bash
$ omnist schema lint schema.osd --severity warning
WARNING [lint.unreachable-record] at $: Unreachable record Dead
```

### 11. `infer`
Infers a structural OSD schema from document samples.

<!-- test-backed: dev.omnist.cli.CliDocTest#testCliInferExample -->
```bash
$ omnist infer sample1.oml sample2.oml
record R {
  "id": integer,
  "name": string,
}
root R
```
