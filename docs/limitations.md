# Status and limitations

**`v0.0.2-alpha`.** `omnist-j` implements the full Document model, Schema model, OML and OSD
grammars (read and write), `validate`, `materialize`, the full schema
algebra (`satisfiable_set`, `is_empty`, `prune`, `compatible_with`,
`equivalent`, `normalize`, `extract`, `lint`, `infer`), all four
interchange codecs (JSON/YAML/TOML/XML, read and write), and a CLI.

## Conformance

Both tracks of the conformance harness pass at **181 / 181 (100%)** —
Track 1 CLI fixtures and Track 2 JSON test vectors, run against
`vendor/omnist-spec`'s pinned `test-suite/`. Zero fails, zero skips.

## Testing

**513 tests passing**, 0 failures — JUnit unit/integration tests plus
jqwik property-based and fuzz tests (grammar-aware generators for TOML
radix literals, OML lexing, and YAML timestamp shapes; raw-input fuzzers
for every codec reader) run at thousands of iterations per property with
zero crashes.

## Code coverage (JaCoCo)

Gate-scoped (excludes `dev.omnist.conformance`, the harness itself, and
`CliMain`, which is a thin argument-parsing entry point):

| Package | Line | Branch |
|---|---|---|
| Overall | **99.83%** | **99.85%** |
| `dev.omnist.document` | 100.0% | 100.0% |
| `dev.omnist.schema` | 100.0% | 100.0% |
| `dev.omnist.algebra` | 100.0% | 100.0% |
| `dev.omnist.cli` | 100.0% | 100.0% |
| `dev.omnist.codec` | 99.8% | 99.9% |
| `dev.omnist.validation` | 100.0% | 99.2% |
| `dev.omnist.oml` | 99.5% | 99.7% |

The CI gate (`pom.xml`) is set at 99.8% line / 99.8% branch — just below
the real achieved number, so it catches actual regressions rather than
sitting on a stale, looser floor.

The handful of remaining uncovered lines are documented trip-wires:
branches that are defensively correct but not reachable given the real
runtime behavior of the underlying libraries under this codebase's exact
configuration (verified empirically, not assumed) — e.g. a TOML parser
error path that can't be triggered without a malformed upstream parser
result, or an XML DOM check for a node type that `setCoalescing(true)`
already rules out before the DOM is exposed. Each is annotated in place
with the reasoning and, where relevant, the diagnostic that confirmed it.
