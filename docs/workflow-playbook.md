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

- Pinned expected pass/fail/skip counts in conformance tests go stale whenever genuine bugs are resolved — update them explicitly with clean runner output. `./run-conformance` must report `Pass: 181, Fail: 0, Skip: 0` before any change is considered done.
- Avoid masking exit codes in shell pipelines (e.g. check exit codes without relying on piped commands that lose the primary exit status).
- Use `git worktree` per parallel branch if working across concurrent workspaces.
- Check package visibility reachability before locking down package structures.
- `FuzzTest.java` runs property-based/fuzz coverage across every format reader at 10,000 iterations per property (7 properties, 70,000 total) — keep this running and passing, don't reduce iteration counts to speed up CI.

---

## 7. Doc-example verification gate (implemented)

Every fenced code block in `docs/*.md` requires an HTML-comment marker directly above the block:

- `<!-- test-backed: dev.omnist.SomeTest#someMethod -->` — a backing JUnit test asserts the literal content of the block. `DocTest.java` and `CliDocTest.java` carry these for `docs/00-guide.md`, `docs/01-api-reference.md`, and `docs/02-cli-reference.md`.
- `<!-- doc-illustrative -->` — the block is non-runnable or conceptual code, exempt from the check.

`DocTest.java` also runs a reflection-based safeguard: it verifies every class and method referenced in `docs/01-api-reference.md` actually exists with a matching signature. `mvn clean test` fails if documentation references a stale or non-existent method — this is a real, enforced CI gate, not aspirational.

---

## 8. Documentation synchronization rule

If a change touches a public API surface, a documented number, or an external-state claim, the documentation describing it MUST be updated in the **same PR** — never left as a follow-up task. Concretely: any PR or commit that adds, modifies, renames, or deprecates a public API class/method in `dev.omnist.*` or a CLI subcommand/flag in `dev.omnist.cli.Cli` MUST update `docs/01-api-reference.md` and/or `docs/02-cli-reference.md` in that same commit — the reflection safeguard in §7 above is the mechanical backstop for this, not a substitute for actually doing it.

---

## 9. Publishing to Maven Central

`mvn clean test`/`mvn package` never require a GPG key or Central credentials — signing and publishing plugins are opt-in via the `release` Maven profile, not bound to the default lifecycle.

**Artifact shape**: the main `dev.omnist:omnist-j` jar is a plain library jar with its real `<dependencies>` intact (Jackson/SnakeYAML/tomlj resolve normally for anyone adding it as a dependency). The shaded fat jar for running `omnist` as a standalone CLI is a separate `-cli` classifier (`omnist-j-<version>-cli.jar`), attached by the same `maven-shade-plugin` execution but never replacing the main artifact. The `omnist` wrapper script and `Track1Runner.java`'s conformance harness both reference the `-cli.jar` explicitly — if either the shade plugin's classifier name or the version changes, update both call sites in the same commit (see §8 above).

**Releasing is automated via `.github/workflows/release.yml`**, triggered by pushing a `v*` tag (e.g. `v0.2.1-alpha`). It runs as two jobs:
1. `verify` — the same `mvn clean test` + `./run-conformance` gates as regular CI, plus a check that the pushed tag's version actually matches `pom.xml`'s `<version>` (fails loudly if they've drifted, rather than publishing the wrong content under the wrong tag).
2. `publish` — gated behind the `central-publish` GitHub Environment, runs `mvn clean deploy -Prelease` using secrets for the GPG key and Central Portal credentials.

`autoPublish` is deliberately `false` in `pom.xml`: the CI job uploads and signs the bundle, but it sits in the Portal for **manual review** — publishing still requires an explicit click there. Central releases are immutable once published: there is no undo, no republish under the same version, and no way to delete a bad release, only supersede it with a new one. Never flip `autoPublish` to `true` without a considered reason to skip that manual checkpoint.

**One-time setup, done once by whoever administers the repo** (not per release, not per machine):
1. A Sonatype Central Publisher Portal account with the `dev.omnist` namespace verified (a DNS TXT record on `omnist.dev`, not GitHub-based verification, since the groupId is a custom domain, not `io.github.*`).
2. A GPG signing key. Export the private key in ASCII-armored form and publish the public key to a keyserver:
   ```bash
   gpg --full-generate-key
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID> > private-key.asc
   ```
3. A Central Portal user token (generated in the Portal's account settings — a username/password pair for publishing, distinct from your login).
4. Four GitHub Actions secrets on this repo (Settings → Secrets and variables → Actions), and a `central-publish` Environment (Settings → Environments) that the `publish` job's secrets are scoped to — optionally with required reviewers configured on that environment for an extra manual checkpoint before every publish run:
   - `CENTRAL_USERNAME` / `CENTRAL_PASSWORD` — the Portal token from step 3.
   - `GPG_PRIVATE_KEY` — the full contents of `private-key.asc` from step 2.
   - `GPG_PASSPHRASE` — the passphrase protecting that key.

   Delete `private-key.asc` locally once it's in GitHub Secrets — it shouldn't be left sitting on disk, committed, or shared anywhere else.

**Releasing, once the above is set up**: bump the version first (see the version-string-scatter gotcha above — grep the exact old string repo-wide, don't trust a single-file diff), verify all three gates locally, and merge to `main`. Tagging is automatic from there: `ci.yml`'s `auto-tag` job runs on every push to `main` (never on PRs), reads `pom.xml`'s version with `mvn help:evaluate`, and pushes `v<version>` as a tag if that tag doesn't already exist — which is what actually triggers `release.yml`. There is nothing else to run manually to kick off a release; merging the version bump is the trigger.

Two safety properties this depends on: `auto-tag` only fires after `test-and-coverage` and `conformance` both pass (`needs: [...]`), and pushing the tag doesn't publish anything by itself — `release.yml`'s `publish` job still waits on manual approval in the `central-publish` GitHub Environment (required reviewers configured there) before `mvn deploy -Prelease` actually runs. Watch the `Release` workflow in the Actions tab, approve the `publish` job when it's ready, then do the final manual review/publish click in the Central Portal once that job succeeds.
