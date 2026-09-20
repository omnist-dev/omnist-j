# Status and limitations

**`v0.2.4-alpha`.** `omnist-j` implements the full Document model, Schema model, OML and OSD
grammars (read and write), `validate`, `materialize`, the full schema
algebra (`satisfiable_set`, `is_empty`, `prune`, `compatible_with`,
`equivalent`, `normalize`, `extract`, `lint`, `infer`), all four
interchange codecs (JSON/YAML/TOML/XML, read and write), and a CLI.

## Conformance

Both tracks of the conformance harness run against `vendor/omnist-spec`
**v0.19.0-beta**'s pinned suite, comparing diagnostics as **(path, code) sets**
(omnist-spec §8.5.2 rules 1-3: code-aware, not code-agnostic; no path or code
loosening anywhere in the runner). **0 failures.**

| Track | Pass | Fail | Skip |
|---|---|---|---|
| 1: OML/OSD CLI fixtures | 29 (19 comparable\*) | 0 | 0 |
| 2: JSON vectors | 215 | 0 | 34 |

\* The Java harness folds the 10 `_referee-self-test/*` fixtures into its Track 1
headline ([omnist-j#110](https://github.com/omnist-dev/omnist-j/issues/110)); the other ports report 19.

Every skip is a real, cited reason (omnist-spec §8.5.5, E-20), never a run against a wrong default:

| Skips | Reason |
|---|---|
| 28 | Not yet implemented: the OSD-OML extension ([omnist-j#105](https://github.com/omnist-dev/omnist-j/issues/105)); 25 `parse_schema_oml`, 3 `write_schema_oml` |
| 6 | Not yet implemented: the YAML alias expansion limit, D-18/D-19/D-20 (omnist-spec §9.4 **DIV-3**, [omnist-j#113](https://github.com/omnist-dev/omnist-j/issues/113)). Every vector carrying `declared_max_alias_expansion` is skipped, as the suite README's declared-limit rule requires: the key is on the runner's allowlist and this port has no way to configure that limit to the vector's value. Four of the six expect `ok: true` at a limit at or above what the cap enforces and would pass if run, but they would be run against the wrong number, so a pass would prove nothing; the skip is deliberate, not a hidden failure |

A vector whose operation the runner has never heard of is a failure, and a failure whose exception
carries no structured `code`/`path` is a failure, not a guess. There is no runner-side "known failing"
list: the CI gate fails on any nonzero fail count (E-22).

## Known gaps

- **D-18/D-19/D-20 (YAML alias expansion), DIV-3; tracked in
  [omnist-j#113](https://github.com/omnist-dev/omnist-j/issues/113).** Not implemented. The only
  protection today is SnakeYAML's own global cap, `maxAliasesForCollections` (default 50), a
  stop-gap that is to be replaced by the per-anchor expansion factor once D-18 lands:
  - **It rejects legitimate input.** It counts alias uses that point at collections; it is not the
    spec's per-anchor E(a) = W(a) / S(a), which deliberately admits ordinary merge-key configs.
    Measured: a config with 50 `<<: *defaults` references is accepted, 51, 60 and 100 are refused as
    `parse.codec-syntax` ("Number of aliases for non-scalar nodes exceeds the specified max=50");
    100 aliases to a scalar anchor are accepted. Each such merge has E = 1.00 under D-18.
  - **It is not a complete bomb defence.** A bomb with fewer alias uses than the cap is not refused
    until this port's walk reaches the 1,000,000-node limit (measured: 48 alias uses, fan-out 3, about
    1.2 s of CPU), and is then reported as `document.limit.nodes`, which E-4a says an over-expansion
    must not be. SnakeYAML preserves alias identity, so nothing is expanded when the text is decoded.
  - A self-referential merge (`a: &A {<<: *A, x: 1}`) is accepted as `{x: 1}`, as in the reference.
  - The cap is deliberately not changed in this release: raising it would let a bomb burn that CPU
    before the node limit fires, and it is the only protection until D-18 exists.
- **D-14 (input must be valid UTF-8).** The library API takes `String`, so decoding is the caller's.
  `Cli` reads files with `Files.readString` (rejects malformed input) but decodes **stdin** with
  `new String(bytes, UTF_8)`, which silently substitutes U+FFFD. There is no conformance vector for
  D-14 yet (omnist-spec#105).
- **Nesting past the parsers' own limits.** JSON and YAML nesting of 1000 or more levels is refused
  by Jackson / SnakeYAML before this port's depth limit (200) is consulted, and is reported as
  `parse.codec-syntax` rather than `document.limit.depth`.
- **Input-size guard.** Input over 2,000,000 characters is refused with `document.parse-error`
  (OML/OSD: `parse.input-too-large` / `schema.input-too-large`), codes that are not in the §8.3
  taxonomy; the spec has no code for this cap.
- **OSD field label with a control character** has no OSD spelling (omnist-spec#104); the OSD writer's
  behaviour for such labels is left as is.

## Testing

**686 tests passing**, 0 failures — JUnit unit/integration tests plus
jqwik property-based and fuzz tests (grammar-aware generators for TOML
radix literals, OML lexing, and YAML timestamp shapes; raw-input fuzzers
for every codec reader) run at thousands of iterations per property with
zero crashes. `NoRawByteOrderMarkTest` fails the build if any source file, tests included,
contains a raw U+FEFF.

## Code coverage (JaCoCo)

Gate-scoped (excludes `dev.omnist.conformance`, the harness itself, and
`CliMain`, which is a thin argument-parsing entry point). Numbers are from
`target/site/jacoco/jacoco.xml` after a fresh `mvn clean test` (two consecutive runs
gave identical figures; a further six runs by an independent reviewer varied only in
branch coverage, 99.23% to 99.27% overall):

| Package | Line | Branch |
|---|---|---|
| Overall | **99.72%** (9 of 3169 missed) | **99.27%** (16 of 2194 missed) |
| `dev.omnist.document` | 100.0% | 98.6% |
| `dev.omnist.schema` | 100.0% | 99.5% |
| `dev.omnist.algebra` | 99.8% | 99.4% |
| `dev.omnist.cli` | 100.0% | 98.9% |
| `dev.omnist.codec` | 99.6% | 99.2% |
| `dev.omnist.validation` | 100.0% | 100.0% |
| `dev.omnist.oml` | 99.3% | 99.3% |

The CI gate (`pom.xml`) is set at 99.6% line / 99.1% branch.

**The margin is thin, and this is a known risk.** At these numbers the gate has headroom of
only 3 more missed lines (12 allowed, 9 missed) and 3 more missed branches (19 allowed, 16
missed); across the reviewer's six runs it was 2 to 3 branches. Any change that adds a few
uncovered branches can fail `mvn clean test` in CI. BRANCH is measurably sensitive to jqwik's
`RANDOMIZED` fuzz-test seeding (a fresh seed every run can legitimately hit a slightly
different set of combinatorial branch outcomes), which is why it has more margin than LINE,
and still not much.

The handful of remaining uncovered lines are documented trip-wires:
branches that are defensively correct but not reachable given the real
runtime behavior of the underlying libraries under this codebase's exact
configuration (verified empirically, not assumed) — e.g. a TOML parser
error path that can't be triggered without a malformed upstream parser
result, or an XML DOM check for a node type that `setCoalescing(true)`
already rules out before the DOM is exposed. Each is annotated in place
with the reasoning and, where relevant, the diagnostic that confirmed it.
