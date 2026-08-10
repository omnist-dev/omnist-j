# omnist-j workflow playbook

This document is binding for this repository. It defines the engineering rules,
architectural choices, and workflow constraints for `omnist-j`. Every contributor
and agent working on this codebase MUST follow this document.

---

## 0. Spec-first, not reference-implementation-first

This port operates under a strict specification-first model.

- **Primary source**: `vendor/omnist-spec` normative documentation. The specification
  is the sole contract.
- **Secondary, tie-breaking only, and only after a spec issue is filed**: Source code
  from sibling ports (`omnist`, `omnist-ts`, `omnist-rs`, `omnist-go`) MAY be read ONLY
  to resolve ambiguities where the spec prose is not yet precise enough to implement, or to write
  an informed spec issue. Their implementation choice is evidence of intent, not proof.
- **Never**: Treat "Python does X" (or Rust, TypeScript, or Go) as sufficient justification
  for a Java behavior when the specification does not state X.

**When a spec gap is load-bearing** (shapes an architectural choice or affects core behavior):
File an issue on `omnist-spec`, then pause that specific work item until the specification issue resolves.

**When a gap is narrow and cosmetic**: File the `omnist-spec` issue, proceed with the most plainly-correct reading, and explicitly document the assumption in code and in the PR description.

Do not open sibling repositories (`~/dev/omnist`, `~/dev/omnist-ts`, `~/dev/omnist-rs`, `~/dev/omnist-go`) except as a step-3 tie-breaker on a gap that already has an open `omnist-spec` issue.

---

## 1. Versioning plan

`omnist-j` will remain on `v0.0.x-alpha` until:
- The core Document data model is implemented.
- Safety limits (§2.4 depth, node count, integer digits) are enforced.
- All four codecs (JSON, YAML, TOML, XML) are implemented.
- OML (Omnist Model Language) and OSD (Omnist Schema Definition) are implemented.
- The CLI tool is implemented.
- The conformance harness (`tools/conformance/`) passes with zero real failures (skips permitted only with explicit spec citations).

Advancing past `v0.0.x-alpha` will be a maintainer sign-off event, not a routine release mechanic.

The spec version this repository targets is **omnist-spec v0.2.2-alpha** (pinned via the `vendor/omnist-spec` git submodule). Pass/fail/skip counts will be shipped alongside every release per spec §10.3.

---

## 2. Foundational design decisions

Each decision below defines a load-bearing architectural choice for `omnist-j`.

### 2.1 Document node representation

**Decision**: Pointer/reference-based tree (`record Node(List<Edge> edges)` and `record Edge(String label, Target target)`), rather than an arena+index scheme.

**Why**: Java features garbage collection and object references. While Rust's `omnist-rs` required an arena to satisfy ownership rules without reference counting, Java handles direct object references idiomatically. A pointer tree directly represents the spec §2.2 edge-list model without extra indirection layers.

### 2.2 Scalar type shape

**Decision**: A tagged record or sealed class hierarchy discriminating the seven spec scalar kinds, using Java native representations:

<!-- doc-illustrative -->
```java
public enum ScalarKind {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    DATE,
    TIME,
    DATE_TIME
}

public sealed interface Scalar {
    ScalarKind kind();

    record StringScalar(String value) implements Scalar {
        public ScalarKind kind() { return ScalarKind.STRING; }
    }
    record IntegerScalar(BigInteger value) implements Scalar {
        public ScalarKind kind() { return ScalarKind.INTEGER; }
    }
    record NumberScalar(double value) implements Scalar {
        public ScalarKind kind() { return ScalarKind.NUMBER; }
    }
    record BooleanScalar(boolean value) implements Scalar {
        public ScalarKind kind() { return ScalarKind.BOOLEAN; }
    }
    record DateScalar(LocalDate value) implements Scalar {
        public ScalarKind kind() { return ScalarKind.DATE; }
    }
    record TimeScalar(TimeValue value) implements Scalar {
        public ScalarKind kind() { return ScalarKind.TIME; }
    }
    record DateTimeScalar(DateTimeValue value) implements Scalar {
        public ScalarKind kind() { return ScalarKind.DATE_TIME; }
    }
}
```

**Why**: Spec §2.2.1 defines exactly seven scalar kinds and explicitly forbids adding or collapsing kinds. Primitive integer types (`long`, `int`) cannot be used for `INTEGER` because spec §2.4 requires supporting integers up to 4,300 decimal digits — `java.math.BigInteger` satisfies this requirement.

### 2.3 Ordered-map / repeated-label representation

**Decision**: The node stays edge-list-native everywhere (`record Node(List<Edge> edges)`), with no separate map-collapsed canonical value type alongside it.

**Why**: Spec §2.1/§2.2 defines the Document model itself as an ordered edge list where repeated labels are permitted (`[(item, "pen"), (note, "rush"), (item, "pad")]`). Introducing a secondary map-shaped representation risks subtle ordering and mutation bugs if any code path consumes the collapsed map instead of the true edge list.

### 2.4 Error-type hierarchy

**Decision**: Structured exceptions and records from day one:

<!-- doc-illustrative -->
```java
public record ParseError(
    int line,
    int column,
    String path,
    String code,
    String message
) {}

public record ValidationError(
    String path,
    String code,
    String message
) {}

public record ValidationResult(
    List<ValidationError> errors
) {
    public boolean isValid() { return errors.isEmpty(); }
}
```

**Why**: Structured error reporting permits exact failure location and classification for spec conformance vectors.

### 2.5 Package boundary reachability check & pre-planned shared modules

**Decision**: Before finalizing any package boundary in `dev.omnist.*`, grep for private and package-private symbols called across proposed package boundaries.

**Why**: Predecessor experience from `omnist-go` showed that a 5-stage package restructuring was required late in development because package symbol reachability was not checked upfront. A simple reachability audit catches boundary leaks early.

Furthermore, **temporal parsing/formatting** and **`validate`/`compatible_with` type-resolution** are known from sibling port experience to require shared internal modules from day zero, rather than attempting organic later extraction mid-implementation.

---

## 3. Planned build order

Per spec §9.5's recommended build sequence:

1. Document model core + §2.4 safety limits (depth, node count, integer digits — finite, documented, enforced)
2. OML reader, then canonical OML writer (Core, then Extended)
3. OSD reader, then canonical OSD writer
4. `validate`
5. `satisfiable_set`, `is_empty`, `prune`
6. `compatible_with`, then `equivalent`
7. `normalize` (requires `prune`)
8. `extract` (requires `prune` and `normalize`)
9. `lint` (requires `satisfiable_set` and `equivalence_classes`)
10. `infer`

---

## 4. Conformance harness (planned implementation)

The conformance runner will be integrated early (right after OML lands) rather than deferred to the end of project development.

- **Referee test first**: Port the 10-case `_referee-self-test` fixtures from `vendor/omnist-spec/conformance/fixtures/_referee-self-test/` and pass them before running test suite vectors.
- **Track 1**: Walk `vendor/omnist-spec/conformance/fixtures/` and invoke operations via direct library calls.
- **Track 2**: Walk `vendor/omnist-spec/test-suite/`, dispatch on each vector's `operation` field, and compare `expect` results per spec §8.5.2.
- **Skips**: Skips are permitted only when explicitly cited with a spec section or an `omnist-spec` divergence-ledger entry.
- **CI Gate**: A dedicated CI build step will fail on any nonzero real failure count.

---

## 5. Engineering workflow loop

1. File an issue with the specific specification section cited in the body (design + rationale).
2. Post the implementation plan and obtain sign-off.
3. Create a feature branch referencing the issue.
4. Test-Driven Development (Red before Green): Write failing tests first, observe them fail, then write implementation to pass.
5. Run full test suite locally, open PR, and run CI.
6. Conduct independent review.
7. Merge and close issue with actual results.

---

## 6. Sharp edges (carried forward from predecessor sibling ports)

- Pinned expected pass/fail/skip counts in conformance tests go stale whenever genuine bugs are resolved — update them explicitly with clean runner output.
- Avoid masking exit codes in shell pipelines (e.g. check exit codes without relying on piped commands that lose the primary exit status).
- Use `git worktree` per parallel branch if working across concurrent workspaces.
- Check package visibility reachability before locking down package structures.

---

## 7. Doc-example verification gate (planned)

Every fenced code block in `docs/*.md` will require an HTML-comment marker directly above the block:

- `<!-- verified-by: path/to/TestOrExample.java::testMethod -->` — a backing Java test asserts the literal content of the block.
- `<!-- doc-illustrative -->` — the block is non-runnable or conceptual code.

---

## 8. Documentation synchronization rule

If a change touches a public API surface, a documented number, or an external-state claim, the documentation describing it MUST be updated in the **same PR** — never left as a follow-up task.
