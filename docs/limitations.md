# Status and limitations

**`v0.3.0-alpha`.** `omnist-j` implements the full Document model, Schema model, OML and OSD
grammars (read and write), `validate`, `materialize`, the full schema
algebra (`satisfiable_set`, `is_empty`, `prune`, `compatible_with`,
`equivalent`, `normalize`, `extract`, `lint`, `infer`), all four
interchange codecs (JSON/YAML/TOML/XML, read and write), and a CLI.

## Conformance

Both tracks of the conformance harness run against `vendor/omnist-spec`
**v0.26.0-beta**'s pinned suite (commit `7744a5c`; the tag was not pushed when this was written), comparing diagnostics as **(path, code) sets**
(omnist-spec §8.5.2 rules 1-3: code-aware, not code-agnostic; no path or code
loosening anywhere in the runner). **0 failures.**

| Track | Pass | Fail | Skip |
|---|---|---|---|
| 1: OML/OSD CLI fixtures | 29 (19 comparable\*) | 0 | 0 |
| 2: JSON vectors | 303 | 0 | 28 |

\* The Java harness folds the 10 `_referee-self-test/*` fixtures into its Track 1
headline ([omnist-j#110](https://github.com/omnist-dev/omnist-j/issues/110)); the other ports report 19.

**v0.22.0-beta to v0.26.0-beta (2026-10-01).** Track 2 went from 253 pass / 0 fail / 34 skip (of 287
vectors) to 303 pass / 0 fail / 28 skip (of 331); the harness headline from 282 / 0 / 34 to 332 / 0 / 28
(Track 1 unchanged at 29 / 0 / 0). Measured red to green: the bump alone, with the old runner, gave 264 pass /
8 fail / 59 skip (the 8 are the E-32 `line:col` placeholder vectors the runner did not yet understand, 4 of them
in YAML's alias-expansion file; 31 vectors carrying `declared_max_alias_expansion` or `declared_max_expanded_slots`
were skipped); with the runner honouring both keys and the E-32 placeholder, but D-18 and D-22 not yet
enforced, 287 pass / 16 fail / 28 skip (the 16 are every alias-expansion and expanded-size vector that expects
a refusal); with D-18, D-18a, D-19, D-20 and D-22 enforced, 303 / 0 / 28. The only skips left are the 28
OSD-OML vectors (omnist-j#105). All 35 vectors of `formats-yaml/alias-expansion.json` run and pass,
each against the number it declares (the runner passes a declared maximum to the reader only for a vector
that carries the key), including the 5 expanded-size ones and the 5 malformed-merge syntax ones; the E-32
placeholder is matched as the spec's E-32b says (same code, a well-formed `line:col`, nothing closer).

**v0.21.0-beta to v0.22.0-beta (2026-09-29).** Track 2 went from 241 pass / 12 fail / 34 skip
(of 287 vectors, measured with the bump applied and no code change) to 253 pass / 0 fail / 34 skip;
the harness headline from 270 / 12 / 34 to 282 / 0 / 34 (Track 1 unchanged at 29 / 0 / 0). The 12
failures were DIV-7's: ten OML-26 separator-then-stray-token vectors
(`shape/separator-then-closing-brace-...`, `semicolon-then-closing-brace-...`,
`separator-then-comma-...`, `separator-then-closing-bracket-...`, `separator-then-a-non-label-token-...`,
`separator-then-nan-...`, `separator-then-opening-brace-...`, `separator-then-an-array-...`,
`separator-then-colon-...`, and `closing-brace-after-a-braced-edge-value-...`, all reported
`parse.unexpected-token`) and the two E-28 code-point column vectors
(`oml-grammar/errors/column-counts-code-points-after-an-astral-character` at `1:13` for `1:12`,
`osd-grammar/errors/...` at `2:19` for `2:18`). Changes: after a complete top-level edge the edge
list continues only if a separator is followed by a `STRING` or `IDENT`, and any other leftover token
is `parse.trailing-content` with or without a separator (OML-26, OML-25); inside `{...}` and `[...]`
it stays `parse.unexpected-token` (OML-27); the OML and OSD lexers count the column in Unicode code
points (E-28), so the second half of a surrogate pair adds nothing. Skips are unchanged at 34.
Measured on one-line inputs of about 1.9 million characters (the input cap is 2,000,000), before
and after, best of three: 200k-token array 207 ms / 184 ms, 100k edges 347 ms / 285 ms, a 1.9M-character
string 19 ms / 21 ms, an OSD line of 100k fields 98 ms / 73 ms. The column computation stays linear.

**v0.19.0-beta to v0.21.0-beta (2026-09-22).** Track 2 went from 215 pass / 0 fail / 34 skip
(of 249 vectors) to 239 pass / 0 fail / 34 skip (of 273): 215 + 14 `bytes_hex` D-14 vectors
(all presented through the CLI's byte-oriented entry point, per E-27; none decoded with
replacement) + 5 OML-26/OML-27 stray-token vectors (4 `shape/*` + 1 `arrays/*`, already
satisfied, unmeasured before this sweep) + 4 OSD-15 canonical-escaping vectors (already
satisfied, unmeasured before this sweep) + 1
`infer/allow-any/mixed-object-and-scalar-shapes-open-to-any-when-allowed` vector (already
satisfied) = 239. Skip count and its breakdown are unchanged at 34 (28 OSD-OML + 6
alias-expansion, DIV-3) -- both the pre-sweep and post-sweep runs give the identical
25 `parse_schema_oml` + 3 `write_schema_oml` + 6 alias-expansion split. Fixed a real
conformance-runner gap found during this sweep's comparison audit: `runParseSchemaVector`
never compared
`expect.schema` on a successful `parse_schema` vector at all (not even structurally) --
confirmed by mutation, a corrupted `expect.schema` on
`osd-grammar/canonical-output/declaration-order-round-trips-exactly` still reported PASS
before the fix. Now compared byte-for-byte per Sec3.3/Sec5.9, and the same mutation now
fails as expected.

Every skip is a real, cited reason (omnist-spec §8.5.5, E-20), never a run against a wrong default:

| Skips | Reason |
|---|---|
| 28 | Not yet implemented: the OSD-OML extension ([omnist-j#105](https://github.com/omnist-dev/omnist-j/issues/105)); 25 `parse_schema_oml`, 3 `write_schema_oml` |

A vector whose operation the runner has never heard of is a failure, and a failure whose exception
carries no structured `code`/`path` is a failure, not a guess. There is no runner-side "known failing"
list: the CI gate fails on any nonzero fail count (E-22).

## YAML alias limits (D-18, D-18a, D-19, D-20, D-22)

Implemented in `0.3.0-alpha` ([omnist-j#113](https://github.com/omnist-dev/omnist-j/issues/113)),
against omnist-spec v0.26.0-beta (commit `7744a5c`). `YamlCodec.read(text)` composes the text into
SnakeYAML's node graph, which describes anchors and aliases without expanding them, and checks that
graph **before** anything is constructed from it. The check is one iterative pass in time linear in
the input, with each container's counts memoized, and saturating arithmetic.

Two limits, two codes, both finite and both configurable through `YamlLimits` (D-10, D-11):

| Limit | Default | Option range | Code | What it bounds |
|---|---|---|---|---|
| Expansion factor `E = W / S` of every mapping and sequence | 50 | 1 to 10 000 | `document.limit.alias-expansion` | Amplification: how many value slots a written construct materializes per slot written |
| Expanded size `W(root)` | 1 000 000 | 1 to 10 000 000 | `document.limit.expanded-size` | Absolute size of an input that uses anchors |

- **What is checked.** Every mapping and every sequence in a value position, the document root, an
  inline merge source and every anchored definition. Scalars are never checked. A sequence in
  merge-value position (`<<: [*a, *b]`, anchored or not) is a carrier: no slot, not a candidate (D-18a).
- **The ratio of a merge is not 1.00.** A mapping that merges a block of `k` keys and writes one key of
  its own has `E` of about `(k + 2) / 3`: `job: {<<: *base, script: x}` writes three slots and
  materializes `k + 2`. So 100 services merging a 20-key block read at most 7.33 and 100 merging a
  60-key block 20.67, but one `job` merging a 150-key base reads 50.67 and is refused at the default,
  and so is a 100-key block aliased 100 times at the root (50.50). A workload that needs more raises
  the maximum (up to 10 000); the previous behaviour, SnakeYAML's global count of 50 aliases to
  collections, refused any configuration that held more than 50 of them, however harmless.
- **The two limits are independent.** An input can pass the ratio and fail the size (a large document
  whose every container sits under 50), or pass the size and fail the ratio (a small, very amplifying
  one). An input that fails both is reported as `document.limit.alias-expansion`.
- **Exemption.** `document.limit.expanded-size` applies only to an input that contains at least one
  alias or merge key (a `<<` key, as the spec uses the term; a quoted `"<<"` is a plain key). A YAML
  input with neither is treated as a JSON or OML input of the same size is: the 2,000,000-character
  input cap and the node limit govern it. The cliff is deliberate in the spec: a plain file of
  two million slots passes and adding one alias subjects it to the cap. In this port the exemption
  applies regardless, but a plain file that large cannot reach the reader: the 2,000,000-character input cap
  and the 1,000,000-node limit refuse it first, so in practice no YAML input here is exempt from a cap.
- **`W` is conservative.** It ignores key collisions, so a document whose merged keys are overridden
  can be refused though it materializes fewer slots (D-19).
- **Malformed merges** (`<<: 1`, `<<: [1]`, `<<: [[{a: 1}]]`, `<<: *s` over scalars) are
  `parse.codec-syntax` and win over both limit codes, wherever they sit in the document.
- **Cycles.** An anchor that refers to itself, directly, through other anchors or through a merge
  key (`a: &a {<<: *a}`), is `document.limit.alias-expansion` (D-20).
- **Parse cost.** The check is cheap next to composing the text, which is SnakeYAML's own work and is paid
  first, in proportion to the input; for an input near the 2,000,000-character cap composing is the dominant cost
  of refusing it. Measured (best of seven, on the composed graph, JDK 21, this repository's test JVM): the
  fan-out bombs 4^10, 2^30 and 50^4 (465 to 2 009 characters) compose in 2.7 to 3.8 ms and are checked in 0.2 to
  0.5 ms; the unanchored merge fan-in of 2 000 merges of a 2 000-key block (31 803 characters) composes in
  15 ms and is checked in 2.7 ms; a root list of 100 000 `{k: *b}` over 1 000 scalars (1.2 MB) composes in 325 ms
  and is checked in 56 ms (the merge-shape pass reads the whole document before counting); a legitimate 1 MB
  file of 40 000 services merging a 20-key block composes in 295 ms, is checked in 50 ms and is accepted, and
  its full read, which materializes about 880 000 slots, takes 0.9 s.
- **Interaction with SnakeYAML's own limits.** The library's global cap (`maxAliasesForCollections`,
  default 50) is lifted so that the spec's per-node ratio decides. Its `codePointLimit` (3 MiB) sits above
  this port's 2 000 000-character cap and never fires first. Its nesting cap is 1000 levels, as before; an
  alias chain can nest the materialized tree deeper than anything that can be written, and one deeper than
  1000 levels is refused as `document.limit.depth` at `$` instead of being constructed. **This guard is a
  port-specific addition beyond the spec**, there to avoid a stack overflow in SnakeYAML's constructor when the
  alias maximum is raised. Up to 1000 levels the document model's own depth limit (200) decides, with the real
  path of the offending node; only above 1000 is the path `$`.
- **Complex keys.** A mapping or sequence used as a key (`? [a, b]`) is counted as if it were a value, so
  it cannot hide an expansion; the document model refuses such a key afterwards in any case.
- **Surfaces.** Every YAML read goes through `YamlCodec`: `YamlCodec.read` (reference defaults),
  `YamlCodec.readWithLimits` (configured), the CLI (defaults; there is no flag, and an over-limit input
  exits 2 with `Error: ...` or, with `--json`, the code and path) and the conformance runner. The OML and
  OSD readers have no alias mechanism.

## Known gaps

- **D-14 (input must be valid UTF-8).** The library API takes `String`, so decoding is the caller's.
  `Cli` decodes both files and stdin strictly (`CharsetDecoder` with `REPORT`) and refuses malformed
  input with `parse.invalid-encoding` at `1:1`; the `bytes_hex` vectors (E-27) cover it.
- **Codec syntax positions (JSON/YAML/TOML/XML)** keep each codec library's own position
  arithmetic. E-28's code-point column is implemented for OML and OSD text only; the codec
  positions are the open question [omnist-spec#114](https://github.com/omnist-dev/omnist-spec/issues/114).
- **Nesting past the parsers' own limits.** JSON and YAML nesting of 1000 or more levels is refused
  by Jackson / SnakeYAML before this port's depth limit (200) is consulted, and is reported as
  `parse.codec-syntax` rather than `document.limit.depth`.
- **Input-size guard.** Input over 2,000,000 characters is refused with `document.parse-error`
  (OML/OSD: `parse.input-too-large` / `schema.input-too-large`), codes that are not in the §8.3
  taxonomy; the spec has no code for this cap.
- **OSD field label with a control character** has no OSD spelling (omnist-spec#104); the OSD writer's
  behaviour for such labels is left as is.

## Testing

**796 tests passing**, 0 failures — JUnit unit/integration tests plus
jqwik property-based and fuzz tests (grammar-aware generators for TOML
radix literals, OML lexing, and YAML timestamp shapes; raw-input fuzzers
for every codec reader) run at thousands of iterations per property with
zero crashes. `NoRawByteOrderMarkTest` fails the build if any source file, tests included,
contains a raw U+FEFF.

## Code coverage (JaCoCo)

Gate-scoped (excludes `dev.omnist.conformance`, the harness itself, and
`CliMain`, which is a thin argument-parsing entry point). Numbers are from
`target/site/jacoco/jacoco.xml` after a fresh `mvn clean test`, measured three times
(YAML alias limits, 2026-10-01): the three runs gave identical figures.

| Package | Line | Branch |
|---|---|---|
| Overall | **99.68%** (11 of 3404 missed) | **99.24%** (18 of 2354 missed) |
| `dev.omnist.document` | 100.0% | 98.6% |
| `dev.omnist.schema` | 100.0% | 99.6% |
| `dev.omnist.algebra` | 99.8% | 99.4% |
| `dev.omnist.cli` | 99.4% | 98.0% |
| `dev.omnist.codec` | 99.7% | 99.3% |
| `dev.omnist.validation` | 100.0% | 100.0% |
| `dev.omnist.oml` | 99.3% | 99.3% |

The CI gate (`pom.xml`) is set at 99.6% line / 99.1% branch.

**The margin is thin, and this is a known risk.** At these numbers the gate has headroom of
only 2 more missed lines (13 allowed, 11 missed) and 3 more missed branches (21 allowed, 18
missed). The YAML alias limits added lines and branches (3214 to 3404 lines, 2228 to 2354
branches), and every new line and branch of `YamlAliasCheck`, `YamlLimits` and the new `YamlCodec` path is
covered: the missed counts are unchanged at 11 / 18. (A first draft of `YamlAliasCheck` left 3 lines and 7
branches uncovered, all defensive null or default arms that no input can reach; they were removed rather than
excluded, which brought the branch ratio from 98.94%, under the gate, to 99.24%.) The headroom was thinner at v0.21.0-beta than at
v0.19.0-beta (3 lines / 3 branches), because that sweep's new code (Cli.java's D-14 strict-UTF-8 decode and its `--json` structured-error
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
