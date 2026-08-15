# omnist-j

From-scratch Java port of the [Omnist data-interchange specification](https://github.com/omnist-dev/omnist-spec).

Docs: [j.omnist.dev](https://j.omnist.dev)

## Methodology

This repository follows a strict spec-first methodology. `vendor/omnist-spec` is pinned as a git submodule and serves as the primary normative contract.

For workflow details and engineering constraints, see [`docs/workflow-playbook.md`](docs/workflow-playbook.md).

## Sibling Ports

- **Specification**: [omnist-spec](https://github.com/omnist-dev/omnist-spec)
- **Python**: [omnist](https://github.com/omnist-dev/omnist)
- **TypeScript**: [omnist-ts](https://github.com/omnist-dev/omnist-ts)
- **Rust**: [omnist-rs](https://github.com/omnist-dev/omnist-rs)
- **Go**: [omnist-go](https://github.com/omnist-dev/omnist-go)

## Documentation

- [`docs/00-guide.md`](docs/00-guide.md): Mental Model & Quickstart Guide.
- [`docs/01-api-reference.md`](docs/01-api-reference.md): Complete Java API Reference.
- [`docs/02-cli-reference.md`](docs/02-cli-reference.md): CLI Command Reference.
- [`workflow-playbook.md`](workflow-playbook.md): Development & Documentation Workflow.

## Status

**`v0.0.1-alpha`.**

- **Conformance Harness**: **181 / 181 (100%) PASS** across Track 1 CLI fixtures & Track 2 JSON test vectors.
- **Unit & Fuzz Testing**: **513 tests passing**, 0 failures — JUnit unit/integration tests plus jqwik property-based and fuzz tests, zero crashes.
- **Code Coverage (JaCoCo)**: **99.65% Line / 97.87% Branch** overall (gate-scoped, excludes the conformance harness and `CliMain`). `document` and `schema` are at 100%/100%; every remaining gap is a documented, empirically-verified trip-wire. See [Status & limitations](https://j.omnist.dev/limitations/) for the full breakdown.
