# Status and limitations

**`v0.7.0-alpha`.** `omnist-j` implements the full Document model, Schema model, OML and OSD
grammars (read and write), `validate`, `materialize`, the full schema
algebra (`satisfiable_set`, `is_empty`, `prune`, `compatible_with`,
`equivalent`, `normalize`, `extract`, `lint`, `infer`), all four
interchange codecs (JSON/YAML/TOML/XML, read and write), and a CLI.

## Changes in 0.7.0-alpha

Adopts omnist-spec **v0.35.0-beta** (the suite grows from 522 to 731 vectors). New rejections and corrected output make
this a minor bump. Migration notes are on the lines marked **Migration**.

- **Document paths quote a label that needs it** (E-36, DIV-35). A label that is empty or holds `.` `[` `]` `"` or `\`,
  a control character, U+007F, U+0085, U+2028 or U+2029 is written `$["a.b"]` with the pinned escapes; every other label
  stays bare. One renderer, `PathUtils.child`, builds every Document path (validate, materialize, the codecs' read and
  write reports, the OML reader). **Migration:** code that matched a diagnostic path by splitting on `.` must accept the
  bracket form. Schema paths are unchanged.
- **Temporals are written unaltered, with their seconds** (E-37, DIV-36). TOML writes a time as `12:00:00`, a local
  date-time as `2024-01-15T12:00:00` and an offset date-time with its offset (`2024-01-15T12:00:00+01:00`); before, the
  seconds of a whole minute were dropped, which produced a literal TOML rejects. A TOML time with an offset has no
  literal: it is written as the quoted string `"12:00:00+01:00"` and reported `format.temporal-stringified` (before: the
  offset was dropped with no report). YAML writes a time double-quoted (before: single-quoted) and reports it. XML writes
  a time with its offset (before: dropped), reported as before. **Migration:** text produced by the TOML, YAML and XML
  writers for a time changes as above.
- **A lone CR in OML is rejected** (E-29, DIV-41): `parse.unexpected-token` at the CR, where it was whitespace and ended a
  comment. A CR inside a comment is comment text; `CRLF` is still one line break. **Migration:** an OML file with
  old-Mac line endings no longer reads.
- **YAML number spellings are YAML 1.1's** (C-17, DIV-39): `1e3` and `1.0e3` are strings (an exponent needs a point and a
  sign: `1.0e+3` is a number), `-.5` is a string. **Migration:** a YAML file that relied on `1e3` being a number now
  reads a string.
- **A long sexagesimal YAML integer no longer crashes the reader** (DIV-40): a 4,300-digit-plus literal such as
  `1:30:30:...` threw `StackOverflowError`; it is `document.limit.int-digits` at the integer's path, and a shorter one an
  integer. The literal is summed group by group and abandoned as soon as it is certainly past the limit.
- **A merge key's entries come first** (C-11, C-12, DIV-39): `{p: 0, <<: *a, k: 1}` reads the merged entries, then `p`, as
  it does when the merge key is written first. Before, a merge key that followed an explicit key placed its entries after
  it. **Migration:** edge order of such a mapping changes.
- **XML schema-directed pretyping follows the wrapper shape** (E-4a, DIV-42): the document element is a field of the root
  record (`record Root { "r": R }` for `<r>`), as in the spec's XML worked example, and an over-long integer leaf is
  `document.limit.int-digits` at the leaf's Document path (`$.r.n`, `$.r.n[1]` when repeated). Before, pretyping only
  worked when the schema root described the content of the document element, and the limit error was reported at `$`.
  **Migration:** a schema whose root record described the document element's own children no longer types those leaves;
  declare the element as a field of the root record.
- **`./omnist` finds the CLI jar** ([omnist-j#129](https://github.com/omnist-dev/omnist-j/issues/129)): the wrapper ran
  a jar named `omnist-j-0.2.4-alpha-cli.jar`, which has not existed for several releases. It now picks the newest
  `target/omnist-j-*-cli.jar` and says so when there is none. Track 1 of the conformance harness already ran the CLI
  in-process because that name never matched; the dead process branch is removed, and CI runs `./omnist format` against
  the built jar.

## Changes in 0.6.1-alpha

`0.6.0-alpha` was tagged but never published to Maven Central: its bundle was rejected ("content that does NOT have a
.pom file"). On the CI runner (Maven 3.10.0) the central-publishing plugin's staging step also wrote
`maven-metadata-local.xml` and `_remote.repositories` files into the bundle. The build now pins Maven 3.9.9 through
the Maven Wrapper (`./mvnw`, used by CI, the release workflow and `./run-conformance`), which yields the 30 expected
entries. `0.6.1-alpha` is the first published release of the code below; there is no other code change.

## Changes in 0.6.0-alpha (never published; first published as 0.6.1-alpha)

Adopts omnist-spec **v0.34.0-beta** (the suite grows from 367 to 522 vectors). New rejections, a new
code and corrected output make this a minor bump:

- **`Limits` reach every codec** ([omnist-j#127](https://github.com/omnist-dev/omnist-j/issues/127), DIV-29, D-9, E-4a).
  `JsonCodec`, `YamlCodec`, `TomlCodec` and `XmlCodec` honour the depth, node-count and integer-digit bounds of the
  `Limits` they are given, with the OML reader's diagnostics: `document.limit.depth` and `document.limit.nodes` at `$`
  (before: at the path of the node), `document.limit.int-digits` at the Document path of the integer (before: at `$`).
  An array element is one level below its node, as in OML (before: two). The root element of an XML document is
  level 1, so a limit now counts the same Document the same way in every format.
- **Digits are counted by value** (D-28). A TOML or YAML hexadecimal, octal or binary literal, an underscored one,
  one with leading zeros and a YAML sexagesimal integer are counted by the decimal digits of the number they denote: a
  3,600-digit hex literal (4,335 digits) is refused, a 4,301-digit binary one (1,295) is accepted. An XML integer leaf
  pretyped from a schema is counted the same way, at the leaf's path (indexed per E-10 when the element repeats).
- **A long YAML integer is an integer** (#127). SnakeYAML leaves a plain scalar of more than about 1,000 characters a
  string even when it is spelled as an integer or a float; that turned a 1,001 to 4,300-digit YAML integer into a
  `StringScalar` with no diagnostic. It is now an integer (or `document.limit.int-digits`), and a long float a number.
- **A repeated YAML key is rejected** (C-12): `parse.codec-syntax` at the repeated key. (Before: the edge moved to
  the last position.) A repeated JSON key keeps its first position and its last value (C-11, unchanged); a repeated
  TOML key is rejected (unchanged).
- **`!!omap`, `!!pairs` and `!!set` are refused** (C-13) with the new registered code `format.tag-forbidden` at `$`
  (before: `!!omap` was read as a sequence, and `!!pairs` and `!!set` threw an uncoded `IllegalArgumentException`).
  `!!str <<` is an ordinary key and `!!merge <<` a merge key (D-27, unchanged).
- **TOML `1e999` reads as +Infinity, `-1e999` as -Infinity, and `1e-999` as 0.0** (D-29); tomlj reports both as
  syntax errors, so the reader rewrites the token before parsing.
- **TOML edges are read in source order** (D-1). The reader took tomlj's `toMap()`, a `HashMap`, so top-level keys came
  back in hash order. Found by the order-sensitive Track 2 comparison below. The reader also no longer rewrites a
  number-shaped key or table name, and a string that starts with the reader's own wrapper for a long integer stays a
  string.
- **The JSON writer writes `\u001f` with lowercase hex digits** (C-14); the other writers already did.
- **A time or date-time is written with its seconds** everywhere: `12:00`, which `LocalTime.toString()` produces for
  a whole minute, is now `12:00:00` in OML, JSON, YAML and XML (`TimeValue.format()` and `DateTimeValue.format()`
  changed; `localText` is new on both). Found by the byte-exact `write` comparison.
- **XML drop reports carry E-10 paths** ([omnist-j#124](https://github.com/omnist-dev/omnist-j/issues/124)):
  `format.attribute-dropped` and `format.namespace-dropped` for `<r><a x="1"/><a x="2"/></r>` are at `$.r.a[0]` and
  `$.r.a[1]`; a single element stays bare.
- **Runners** ([omnist-j#125](https://github.com/omnist-dev/omnist-j/issues/125),
  [#126](https://github.com/omnist-dev/omnist-j/issues/126), DIV-33): Track 2 compares a parse result with edge
  order significant and a `write` vector's text byte for byte (after the outer-whitespace trim; XML per E-18; JSON with
  its insignificant whitespace removed), instead of re-parsing it; it passes `compact` to the OML writer (E-35) and
  runs every `limits-codecs` vector instead of skipping it; Track 1 compares a lint finding's `code` exactly.

Known caveat: Jackson refuses a JSON number token of more than 10,000 digits (or of more than `maxIntegerDigits` when
that is larger) itself, as `parse.codec-syntax`; between the limit and that cap the diagnostic is
`document.limit.int-digits` at the integer's path.

## Changes in 0.5.0-alpha

Breaking changes for library users (the version is an alpha minor bump for this reason):

- `MAX_INPUT_LENGTH` is removed from `OmlReader`, `OsdReader`, `JsonCodec`, `YamlCodec`, `TomlCodec` and `XmlCodec`.
- `Limits` gained a fourth component, `maxInputBytes`; the three-argument constructor is kept and uses the default.
- The default maximum input is now 64 MiB, counted in bytes, instead of 2,000,000 characters.
- The codes `parse.input-too-large` and `schema.input-too-large` (and `document.parse-error` for an oversized input) are
  replaced by `document.limit.input-size` at `$`.
- The OML writer now writes UTC as `+00:00` instead of `Z`, and the OML reader no longer accepts `Z`
  ([omnist-j#122](https://github.com/omnist-dev/omnist-j/issues/122)).
- Every writer now refuses a lone surrogate in a string or label with `write.unsupported-value` (C-9).

## Conformance

Both tracks of the conformance harness run against `vendor/omnist-spec`
**v0.35.0-beta**'s pinned suite (commit `237ef00`), comparing diagnostics as **(path, code) sets**
(omnist-spec §8.5.2 rules 1-3: code-aware, not code-agnostic; no path or code
loosening anywhere in the runner). **0 failures.**

| Track | Pass | Fail | Skip |
|---|---|---|---|
| 1: OML/OSD CLI fixtures | 29 (19 comparable\*) | 0 | 0 |
| 2: JSON vectors | 703 | 0 | 28 |

\* The Java harness folds the 10 `_referee-self-test/*` fixtures into its Track 1
headline ([omnist-j#110](https://github.com/omnist-dev/omnist-j/issues/110)); the other ports report 19.

**v0.34.0-beta to v0.35.0-beta (2026-10-13).** The suite grows from 522 to 731 vectors. With the new pin and no source
change Track 2 was 660 pass / 43 fail / 28 skip (headline 689 / 43 / 28) and Track 1 29 / 0 / 0; after the fixes it is
703 / 0 / 28 (headline 732 / 0 / 28). The 43 failures: 18 `validate/label-quoting` (E-36), 14 temporal `write` (E-37: 7 TOML, 5 YAML, 2 XML), 7 `oml-grammar/lone-cr`
(E-29), 2 YAML exponent spellings (C-17), 1 YAML merge-key order (C-12) and the 4,300-digit sexagesimal YAML
integer, which crashed the runner with a `StackOverflowError`. The digit-by-value (D-28) and TOML underflow (D-29)
vectors already passed.

**v0.33.0-beta to v0.34.0-beta (2026-10-11).** The suite grows from 367 to 522 vectors; Track 2 goes from 339 pass /
0 fail / 28 skip to 494 / 0 / 28, the headline from 368 / 0 / 28 to 523 / 0 / 28. Every vector now runs except the 28
OSD-OML ones (omnist-j#105): the old runner skipped the 42 vectors that carry a `declared_max_*` key outside OML (the 26
`limits-codecs` vectors and 16 of `limits-literals`). Measured red to green, in three steps:

| Step | Track 1 | Track 2 | Headline |
|---|---|---|---|
| The pin alone, old runner | 29 / 0 / 0 | 434 / 18 / 70 | 463 / 18 / 70 |
| The runner fixes (byte-exact `write`, ordered parse comparison, `compact`, limits vectors run), no code change | 29 / 0 / 0 | 448 / 46 / 28 | 477 / 46 / 28 |
| Plus the source fixes | 29 / 0 / 0 | 494 / 0 / 28 | 523 / 0 / 28 |

The 18 failures of the first step were the four default-limit literals (3,600-digit hex and 4,301-digit binary, in TOML
and in YAML), the 2 TOML overflowing floats, the 6 YAML repeated-key vectors and the 6 YAML `!!omap`, `!!pairs` and
`!!set` vectors. The runner fixes then added 28 more failures that the old comparison and the skips hid: the 14
`limits-codecs` one-past-the-limit vectors and the 8 `limits-literals` refusals now run (the 12 at-limit vectors passed
at once), the 2 lowercase-hex JSON vectors, the 2 OML time and date-time vectors (`12:00` written for `12:00:00`), and
2 TOML vectors that pin edge order (the reader returned top-level keys in hash order).

**v0.28.0-beta to v0.33.0-beta (2026-10-05).** The suite grows from 338 to 367 vectors; Track 2 goes from 310 pass /
0 fail / 28 skip to 339 / 0 / 28, the headline from 339 / 0 / 28 to 368 / 0 / 28. Of the 29 new vectors, 19 passed with no
code change (7 E-10 repeated-label paths, 7 OML-29 colon separators, 5 C-10 XML null leaf) and 10 are the
`document-model/input-size/*` vectors: with the pin alone they skipped (the runner did not honour
`declared_max_input_bytes`); now they run and pass (D-23, [Input size](#input-size-d-23-to-d-26)). The runner now also
follows E-20a: a vector with any `declared_*` key it does not honour, or on an operation other than `parse`, is skipped
with a reason, never run against a default.

**v0.27.0-beta to v0.28.0-beta (2026-10-02).** No vector changed: Track 2 stays 310 pass / 0 fail / 28 skip
of 338, Track 1 29 / 0 / 0, headline 339 / 0 / 28. The release adds four rules about programmatically built
schemas that no vector can reach (DIV-5), so they are pinned by `SchemaV028Test` only; see
[Programmatic schema rules](#programmatic-schema-rules-s-8-s-22-s-23-s-24-osd-16) below.

**v0.26.0-beta to v0.27.0-beta (2026-10-02).** Track 2 went from 303 pass / 0 fail / 28 skip (of 331
vectors) to 310 / 0 / 28 (of 338); the harness headline from 332 / 0 / 28 to 339 / 0 / 28. The 7 new vectors pin the
empty merge sequence `<<: []` (D-18a), a carrier that merges nothing; they passed with no code change.

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

## Programmatic schema rules (S-8, S-22, S-23, S-24, OSD-16)

Implemented in `0.4.0-alpha` against omnist-spec v0.28.0-beta (commit `1a7d0de`). No conformance vector
reaches any of them (DIV-5: OSD text arrives as declaration order and no text carries `max = 0`), so the
only pin is `src/test/java/dev/omnist/schema/SchemaV028Test.java`. A schema built through the Java API
(`new Schema`, `new Record`, `new Type.Ref`) is checked at construction, and the violation is a
`dev.omnist.schema.SchemaException` (an `IllegalArgumentException`) carrying `getCode()` and `getPath()`:

| Rule | Code | Path | Raised by |
|---|---|---|---|
| S-8: a record name, the root name or a `Ref` target is not `[A-Za-z_][A-Za-z0-9_]*` | `schema.invalid-name` | `$`, the name in the message only | `Record`, `Type.Ref`, `Schema` constructors |
| S-22: a field label does not encode to valid UTF-8 (a lone UTF-16 surrogate) | `schema.invalid-label` | the record name `R`; the label is never put in the path | `Record` constructor |
| OSD-16 / S-24: a field has `max = 0` | `write.unsupported-value` | the record name `R` | `OsdWriter.write` and `OsdWriter.writeCompact` (a `WriteException`) |

- **S-23 (`schema.unknown-record`) cannot arise.** The Java API has no caller-supplied record ordering:
  `Schema(root, records)` takes one map and keeps its iteration order, so no ordering entry can name a record
  absent from it.
- **`[0,0]` stays representable.** `new Field(label, type, 0, 0)` is accepted (S-15, omnist-spec#83 is open);
  only the OSD writer refuses it. `SchemaAlgebra.prune` removes such fields (an unsatisfiable root record is
  kept intact, as the spec says), `extract` and `normalize` never produce one, so `prune` before writing is the
  way to clear it. There is no OSD-OML schema writer in this port (omnist-j#105), so the OSD-OML half of
  OSD-16 does not apply yet.
- **OSD text from a `String`.** `OsdReader.read` of text holding a lone surrogate reports
  `schema.invalid-label` at the record name as an `OsdParseException`. Input bytes are a different matter:
  a malformed byte sequence is decoded or refused before the reader sees it (D-14, `parse.invalid-encoding`).
- **Several violations.** Construction reports the first one found; which is implementation-defined.
- **`infer` record names are ASCII.** Because a record name is a Name (S-8), `infer` now maps every
  character outside `[A-Za-z0-9_]` to `_` when it derives a record name from a label (a label with an accented
  letter used to yield a non-ASCII name, which S-8 rejects), and an all-digit label yields `Rec`. A malformed
  `rootName` passed to `infer` is `schema.invalid-name` at `$`.

## Known gaps

- **D-14 (input must be valid UTF-8).** The library API takes `String`, so decoding is the caller's.
  `Cli` decodes both files and stdin strictly (`CharsetDecoder` with `REPORT`) and refuses malformed
  input with `parse.invalid-encoding` at `1:1`; the `bytes_hex` vectors (E-27) cover it.
- **Codec syntax positions (JSON/YAML/TOML/XML)** keep each codec library's own position
  arithmetic. E-28's code-point column is implemented for OML and OSD text only; the codec
  positions are the open question [omnist-spec#114](https://github.com/omnist-dev/omnist-spec/issues/114).
- **Nesting past the parsers' own limits.** JSON nesting of 1000 or more levels is refused by Jackson before this
  port's depth limit (200) is consulted, and is reported as `parse.codec-syntax` rather than `document.limit.depth`;
  YAML nesting past the larger of 1000 and `2 * maxDepth + 2` collections is refused by SnakeYAML the same way. A JSON
  number token of more than 10,000 digits is likewise a syntax error (see Changes in 0.6.0-alpha below).
- **Input size (D-23 to D-26).** See [Input size](#input-size-d-23-to-d-26) below. Before `0.5.0-alpha`
  the readers refused input over 2,000,000 characters with codes that are not in the §8.3 taxonomy
  (`document.parse-error`, `parse.input-too-large`, `schema.input-too-large`); that cap is gone, replaced by the
  configurable byte maximum with the registered code `document.limit.input-size`.
- **OSD field label with a control character** has no OSD spelling (OSD-14): `OsdWriter` fails with
  `write.unsupported-value` at the record path, unconditionally.

## Input size (D-23 to D-26)

`omnist-spec` v0.30.0-beta added a bound on input size (§2.4.2). `omnist-j` enforces it in every reader
(OML, OSD, JSON, YAML, TOML, XML) and in the CLI.

| Item | Value |
|---|---|
| Code, path | `document.limit.input-size` at `$` |
| Unit | UTF-8 bytes, not characters. A `String` is counted without allocating an encoded copy; a lone surrogate counts as the three bytes of U+FFFD |
| Boundary | an input of exactly `maxInputBytes` is accepted; one byte more is refused |
| Byte-order mark | counted (three bytes): the check runs before it is stripped |
| Order | first: before decoding (CLI), before the BOM rules, before any parse. An oversized malformed input reports the size |
| Default | **64 MiB** (`Limits.DEFAULT_MAX_INPUT_BYTES`) |
| Range | 1 to 1 GiB (`Limits.MAX_INPUT_BYTES_CEILING`); anything else is `IllegalArgumentException`, as for the other limits |
| Library | `Limits.maxInputBytes`; `OmlReader.read(text, limits)`, `OsdReader.read(text, limits)`, `JsonCodec`/`TomlCodec`/`XmlCodec.readWithLimits`, `YamlCodec.readWithLimits(text, yamlLimits, limits)` |
| CLI | `--max-input-bytes N` on every command that reads input; stdin and files read at most `N + 1` bytes |

**Why 64 MiB, and why this is a behaviour change.** The spec names no reference number (D-24): the maximum is
configurable, an implementation documents the value it chooses and SHOULD measure its slowest codec on a worst-case
input of that size before raising it. Before `0.5.0-alpha` the readers refused anything over
2,000,000 characters. The default is now 32 times larger, so a document that was refused before is read. Measured on this port (`omnist format` on a flat mapping, JVM start-up included,
default heap of a 6 GB machine, one run each, not a benchmark): JSON 10.3 MB 2.4 s and 32.4 MB 6.4 s; YAML
9.0 MB 6.2 s and 27.7 MB 19.0 s. Parse time and memory grow with the input and with its shape (deep
nesting, many keys, YAML anchors); the cap bounds them, it does not make a parse fast. Lower it for untrusted
input. SnakeYAML's own 3 MiB code point limit is raised to the configured maximum, so it never refuses an
input D-23 accepts.

**What it does not bound (D-26).** The depth, node count and integer digit limits and the two YAML alias
limits bound a different cost. Alias-free YAML is exempt from D-22 by design. String, label and schema size have
no limit in this port.

## Strings with no UTF-8 encoding (C-9)

Every writer (`OmlWriter`, `JsonCodec`, `YamlCodec`, `TomlCodec`, `XmlCodec`) fails with
`write.unsupported-value`, unconditionally (`strict` or not), on a string value or an edge label that has no UTF-8
encoding. In Java that is a lone UTF-16 surrogate, for example `"a\uD800"`. A valid surrogate pair (an astral
character) and U+FFFD are ordinary text and still write. Before `0.5.0-alpha` the lone surrogate was written raw in
JSON, TOML and OML and as an escape in YAML (DIV-5); spelling it as an escape does not comply, so no writer emits it
in any form now.

- **Path.** The Document path of the node holding the string; for a label, the node holding the edge (a path cannot
  quote a label), so `{"bad\uD800": 1}` fails at `$`, and a bad string in the second `item` of `$.r` at
  `$.r.item[1]` (E-10). It is a `WriteException`; its `report()` carries the error.
- **Cost.** No path is built unless there is a failure. OML checks inside the one loop that escapes strings; JSON and
  TOML fold the test into the pass that already visits every node; YAML and XML run one extra pass
  (`Encodability.allEncodable`, iterative, no path). Measured on a document of 120 000 edges (100 sections of 300
  items; best of 20 iterations, three JVM runs, before and after, on a shared machine whose run-to-run noise is about
  10%): OML 0%, JSON +7%, YAML +1%, TOML +10%, XML +5%. The extra pass alone costs 6.6 ms on that document.
- **XML.** A label or string C-9 refuses is not reported a second time as an invalid XML name or character.
- No conformance vector pins C-9 (an input cannot carry a lone surrogate into a Document); `Utf8WriterTest` does,
  for all five writers.

## OML datetimes have no `Z` (omnist-j#122)

The grammar's `tz-offset` is `("+" / "-") HH ":" MM` (ABNF `oml.abnf`, §4.2.4 OML-10): there is no `Z`. Before
`0.5.0-alpha` the OML reader accepted `a: 2024-01-01T10:00:00.123456Z` (and `10:00:00Z`, `...T10:00Z`). It now reads
the literal up to the `Z`, which is left over, and fails with `parse.trailing-content` at the `Z`'s `line:col`
(`1:30` for that example), the code and position the Python reference reports (checked row by row against Python
omnist master). `+00:00` is the way to write UTC and reads as UTC.

The OML writer used to write a UTC time or date-time with `Z`, which no conforming reader (this one included, now)
accepts; it writes `+00:00`, as the Python writer does. The JSON, YAML, TOML and XML codecs are untouched: ISO `Z`
is valid there.

## Testing

**1194 tests passing**, 0 failures — JUnit unit/integration tests plus
jqwik property-based and fuzz tests (grammar-aware generators for TOML
radix literals, OML lexing, and YAML timestamp shapes; raw-input fuzzers
for every codec reader) run at thousands of iterations per property with
zero crashes. `NoRawByteOrderMarkTest` fails the build if any source file, tests included,
contains a raw U+FEFF.

## Code coverage (JaCoCo)

Gate-scoped (excludes `dev.omnist.conformance`, the harness itself, and
`CliMain`, which is a thin argument-parsing entry point). Numbers are from
`target/site/jacoco/jacoco.xml` after a fresh `mvn clean test`, measured three times
(spec v0.35.0-beta adoption, 2026-10-13): the three runs gave identical figures.

| Package | Line | Branch |
|---|---|---|
| Overall | **99.74%** (10 of 3832 missed) | **99.40%** (17 of 2814 missed) |
| `dev.omnist.document` | 100.0% | 98.6% |
| `dev.omnist.schema` | 100.0% | 99.6% |
| `dev.omnist.algebra` | 100.0% | 99.7% |
| `dev.omnist.cli` | 99.4% | 98.0% |
| `dev.omnist.codec` | 99.7% | 99.3% |
| `dev.omnist.validation` | 100.0% | 100.0% |
| `dev.omnist.oml` | 99.3% | 99.3% |

The CI gate (`pom.xml`) is set at 99.6% line / 99.1% branch.

**v0.35.0-beta (2026-10-13):** 1297 tests; 10 of 3832 lines and 17 of 2814 branches missed, three runs identical
(headroom 8 branches against the 99.1% gate; lines unchanged at 10). The new path renderer, the temporal writers,
the lone-CR scan, the sexagesimal recogniser and the wrapper-shaped XML pretyping are covered.

**v0.34.0-beta (2026-10-11):** 1272 tests; 10 of 3746 lines and 18 of 2712 branches missed, three runs identical
(headroom 6 branches against the 99.1% gate; lines unchanged at 10). The new `IntegerDigits`, the YAML integer
constructor and resolver, the TOML float and table walk and the XML path code are covered; the two branches added over
v0.33.0-beta are in the TOML float rewrite and the YAML integer constructor. The per-package table above is from the
v0.28.0-beta run and was not re-measured.

**v0.33.0-beta (2026-10-05):** 1194 tests; 10 of 3591 lines and 16 of 2508 branches missed, three runs identical
(headroom 4 lines and 6 branches against the 99.6% / 99.1% gate). The new `InputSize`, `Encodability` and writer paths
are fully covered. The per-package table above is from the v0.28.0-beta run and was not re-measured.

**v0.28.0-beta (2026-10-02):** 836 tests; 10 of 3451 lines and 17 of 2414 branches missed, three runs identical
(headroom 3 lines and 4 branches). The new `SchemaRules`, `SchemaException` and writer paths are fully covered.

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
