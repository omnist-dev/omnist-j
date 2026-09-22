# Status and limitations

**`v0.2.5-alpha`.** `omnist-j` implements the full Document model, Schema model, OML and OSD
grammars (read and write), `validate`, `materialize`, the full schema
algebra (`satisfiable_set`, `is_empty`, `prune`, `compatible_with`,
`equivalent`, `normalize`, `extract`, `lint`, `infer`), all four
interchange codecs (JSON/YAML/TOML/XML, read and write), and a CLI.

## Conformance

Both tracks of the conformance harness run against `vendor/omnist-spec`
**v0.21.0-beta**'s pinned suite, comparing diagnostics as **(path, code) sets**
(omnist-spec §8.5.2 rules 1-3: code-aware, not code-agnostic; no path or code
loosening anywhere in the runner). **0 failures.**

| Track | Pass | Fail | Skip |
|---|---|---|---|
| 1: OML/OSD CLI fixtures | 29 (19 comparable\*) | 0 | 0 |
| 2: JSON vectors | 239 | 0 | 34 |

\* The Java harness folds the 10 `_referee-self-test/*` fixtures into its Track 1
headline ([omnist-j#110](https://github.com/omnist-dev/omnist-j/issues/110)); the other ports report 19.

**v0.19.0-beta to v0.21.0-beta (2026-09-22).** Track 2 went from 215 pass / 0 fail / 34 skip
(of 249 vectors) to 239 pass / 0 fail / 34 skip (of 273): +14 `bytes_hex` D-14 vectors (all
presented through the CLI's byte-oriented entry point, per E-27; none decoded with
replacement), +5 OML-26/OML-27 stray-token vectors (already satisfied, unmeasured before this
sweep), +4 OSD-15 canonical-escaping vectors (already satisfied, unmeasured before this
sweep). Skip count is unchanged at 34 (28 OSD-OML + 6 alias-expansion, DIV-3), though its
composition shifted by +2 OSD-OML skips (parse_schema_oml/write_schema_oml still #105) net of
the 14 bytes_hex vectors moving from unrunnable to passing. Fixed a real conformance-runner
gap found during this sweep's comparison audit: `runParseSchemaVector` never compared
`expect.schema` on a successful `parse_schema` vector at all (not even structurally) --
confirmed by mutation, a corrupted `expect.schema` on
`osd-grammar/canonical-output/declaration-order-round-trips-exactly` still reported PASS
before the fix. Now compared byte-for-byte per Sec3.3/Sec5.9, and the same mutation now
fails as expected.

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
`target/site/jacoco/jacoco.xml` after a fresh `mvn clean test`, measured twice
(v0.21.0-beta adoption sweep, 2026-09-22): both runs gave identical figures.

| Package | Line | Branch |
|---|---|---|
| Overall | **99.66%** (11 of 3208 missed) | **99.19%** (18 of 2210 missed) |
| `dev.omnist.document` | 100.0% | 98.6% |
| `dev.omnist.schema` | 100.0% | 99.5% |
| `dev.omnist.algebra` | 99.8% | 99.4% |
| `dev.omnist.cli` | 99.4% | 98.0% |
| `dev.omnist.codec` | 99.6% | 99.2% |
| `dev.omnist.validation` | 100.0% | 100.0% |
| `dev.omnist.oml` | 99.3% | 99.3% |

The CI gate (`pom.xml`) is set at 99.6% line / 99.1% branch.

**The margin is thin, and this is a known risk.** At these numbers the gate has headroom of
only 1 more missed line (12 allowed, 11 missed) and 1 more missed branch (19 allowed, 18
missed) -- thinner than the previous v0.19.0-beta measurement (3 lines / 3 branches), because
this sweep's new code (Cli.java's D-14 strict-UTF-8 decode and its `--json` structured-error
branches, Track2Runner's bytes_hex routing and its canonical-schema byte-for-byte comparison)
added lines faster than the new CliTest/Osd14Osd15/property-test coverage could close every
branch. Two consecutive `mvn clean test` runs gave identical numbers (11 missed lines, 18
missed branches both times), so this is not run-to-run jqwik seed noise -- it is a real,
reproducible margin that the next behavior-changing PR should watch closely. `dev.omnist.cli`
in particular dropped from 100.0%/98.9% to 99.4%/98.0% branch: one defensive catch
(`Cli.java`'s `MAPPER.writeValueAsString` failure path inside the new `--json` error handler)
is confirmed-unreachable in practice (the `JsonResponse`/`JsonError` shapes serialize
unconditionally), the same class of documented trip-wire as the pre-existing gaps below.

The handful of remaining uncovered lines are documented trip-wires:
branches that are defensively correct but not reachable given the real
runtime behavior of the underlying libraries under this codebase's exact
configuration (verified empirically, not assumed) — e.g. a TOML parser
error path that can't be triggered without a malformed upstream parser
result, or an XML DOM check for a node type that `setCoalescing(true)`
already rules out before the DOM is exposed. Each is annotated in place
with the reasoning and, where relevant, the diagnostic that confirmed it.
