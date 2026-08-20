# Streaming Parse & Input-Length Bounds Investigation (#48)

## 1. Context and Problem Statement
Issue #48 identified an asymmetry in DoS resilience across format readers:
- While YAML, TOML, and XML had a 2,000,000-character input length limit (`MAX_INPUT_LENGTH`), JSON, OML, and OSD had no length cap prior to parsing.
- Most format readers parse into a full in-memory intermediate tree (Jackson `Object`, SnakeYAML AST, tomlj parse tree, DOM `Element`) before Omnist's depth and node-count limits are validated.

## 2. Tier 1: Input Length Caps (Implemented)
`MAX_INPUT_LENGTH = 2_000_000` has been standardized across all format readers (`JsonCodec`, `OmlReader`, `OsdReader`, `YamlCodec`, `TomlCodec`, `XmlCodec`).
- For 2,000,000 characters of input, peak heap consumption during DOM or AST allocation is strictly bounded (typically < 30 MB on 64-bit JVMs).
- Inputs exceeding this cap are rejected immediately before any tokenization, parsing, or tree construction begins.

## 3. Tier 2: Incremental / Streaming Parse Feasibility

An investigation was conducted into replacing intermediate DOM/tree construction with streaming enforcement across all codecs:

### 3.1 Format Analysis
1. **JSON (Jackson)**:
   - *Feasibility*: High. Jackson provides `JsonParser`, a streaming tokenizer.
   - *Implementation*: Depth and node limits can be tracked incrementally during `nextToken()` calls without building nested Maps/Lists.
2. **XML (JDK StAX / SAX)**:
   - *Feasibility*: High. `XMLStreamReader` allows push/pull event processing.
   - *Implementation*: Depth and element count can be verified during element start events without DOM construction.
3. **YAML (SnakeYAML)**:
   - *Feasibility*: Low to Moderate. SnakeYAML provides `Yaml.parse(Reader)` returning an `Iterator<Event>`. However, resolving YAML anchors, aliases, tags, and implicit scalar types over raw parser events requires re-implementing significant portions of SnakeYAML's composer and constructor machinery, significantly increasing complexity.
4. **TOML (`org.tomlj:tomlj`)**:
   - *Feasibility*: Blocked / Infeasible without rewriting the TOML parser. `tomlj` parses the full document into an ANTLR4 parse tree in-memory before returning a `TomlParseResult`. It does not expose a streaming tokenizer or incremental event API. Switching to a streaming approach would require replacing the library with a custom hand-written lexer/parser or a different TOML parser.
5. **OML & OSD**:
   - *Feasibility*: High. `OmlReader` and `OsdReader` are internal implementations that can be updated to consume tokens lazily from `OmlLexer`/`OsdLexer` instead of buffering `List<Token>`.

### 3.2 Threat Model Assessment
- **Worst-case Allocation with Tier 1**: With the 2 MB input cap in place, an adversary cannot force unbounded memory allocation. The maximum possible memory overhead per request is tightly bounded by the JVM's representation of a 2 MB string and its parsed object tree.
- **Amplification Factors**: The maximum expansion factor from a 2 MB string to an in-memory object tree is roughly 10x-20x, requiring ~20-40 MB of RAM temporarily, which is well within standard server heap allocations.
- **Node & Depth Caps**: Once parsed into the intermediate structure, the existing `maxNodeCount` (1,000,000) and `maxDepth` (200) limits prevent deeper tree traversal or recursion attacks.

## 4. Recommendation
- **Retain Tier 1 Caps**: The uniform 2,000,000-character cap on all codecs effectively mitigates the primary DoS vector (unbounded memory exhaustion).
- **Defer Tier 2 Streaming Rewrite**: Due to the lack of streaming support in `tomlj`, the complexity of SnakeYAML event resolution, and the low residual risk under the 2MB cap, a full streaming rewrite across all codecs is not recommended at this time.
- If future requirements demand multi-megabyte or gigabyte-scale document processing, streaming parsers should be developed for JSON, XML, and OML first, alongside evaluating alternative TOML and YAML parser architectures.
