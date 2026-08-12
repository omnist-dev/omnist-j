# omnist-j

From-scratch Java port of the [Omnist data-interchange specification](https://github.com/omnist-dev/omnist-spec).

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

- [`docs/01-api-reference.md`](docs/01-api-reference.md): Complete Java API Reference.
- [`docs/02-cli-reference.md`](docs/02-cli-reference.md): CLI Command Reference.
- [`workflow-playbook.md`](workflow-playbook.md): Development & Documentation Workflow.

## Status

- **Conformance Harness**: **181 / 181 (100%) PASS** across Track 1 CLI fixtures & Track 2 JSON test vectors.
- **Unit & Fuzz Testing**: **100 Tests Passing** (93 unit tests + 7 property-based fuzz tests running 70,000 iterations with 0 crashes).
- **Code Coverage (JaCoCo)**: **60.0% Line / 53.0% Branch** overall (`dev.omnist.codec` 76.1%, `oml` 81.6%, `schema` 93.6%, `algebra` 91.5%, `validation` 81.7%, `document` 96.8%). `dev.omnist.cli` (37.5% line / 28.8% branch) tracked separately.
