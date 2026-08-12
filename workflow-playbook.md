# omnist-j Workflow Playbook

Engineering rules and conventions for `omnist-j`.

---

## 1. Documentation-Code Synchronization Rule (Mechanical Safeguard)

> [!IMPORTANT]
> **Documentation-Code Lockstep Requirement**:
> 1. Any PR or commit that adds, modifies, renames, or deprecates a public API class/method in `dev.omnist.*` or a CLI subcommand/flag in `dev.omnist.cli.Cli` MUST update `docs/01-api-reference.md` and/or `docs/02-cli-reference.md` in the exact same commit.
> 2. Every displayed code example in `docs/01-api-reference.md` and `docs/02-cli-reference.md` MUST be backed by a corresponding executable JUnit test in `src/test/java/dev/omnist/DocTest.java` or `src/test/java/dev/omnist/cli/CliDocTest.java`.
> 3. `DocTest.java` automatically runs a reflection safeguard check verifying that every class and method referenced in `docs/01-api-reference.md` exists and matches exact signatures. `mvn clean test` will fail if documentation references non-existent or stale methods.

---

## 2. Test-Driven Development (TDD) & Quality Standards

- **Red-Before-Green**: Write failing tests before implementation.
- **100% Conformance**: Always verify baseline conformance (`./run-conformance`) achieves 181 Pass, 0 Fail, 0 Skip.
- **Property-Based Fuzzing**: Maintain `FuzzTest.java` running 70,000 fuzzing iterations across all format readers.
