# omnist-j

A from-scratch, spec-first Java implementation of [Omnist](https://spec.omnist.dev) — a data-interchange format built around one idea: a document is an ordered list of labeled edges, not a map.

Most formats model an object as a map from key to value. That works fine until a format needs to express "many" without a wrapper, or interleave repeated elements with other data — the shape XML uses and JSON, YAML, and TOML can't natively carry. Omnist's Document model handles all of it the same way: a node is a list of `(label, value)` edges, in order, with no special case for arrays. Two `item` edges *are* the array. There is no separate list type to define.

`omnist-j` reads and writes all five formats — JSON, YAML, TOML, XML, and OML (the native format) — into that one model, validates and materializes documents against a schema (OSD), and implements the schema algebra: `compatible_with`, `equivalent`, `normalize`, `extract`, `prune`, `lint`, `infer`.

## Why "spec-first"

This port follows [omnist-spec](https://spec.omnist.dev) as its primary normative contract — `vendor/omnist-spec` is pinned as a git submodule. See the [workflow playbook](workflow-playbook.md) for the full engineering policy.

## Install

<!-- doc-illustrative -->
```xml
<dependency>
    <groupId>dev.omnist</groupId>
    <artifactId>omnist-j</artifactId>
</dependency>
```

## Where to go next

- [Guide & quickstart](00-guide.md) — the Omnist mental model and a working example
- [API reference](01-api-reference.md) — the full Java API
- [Javadoc](https://j.omnist.dev/javadoc/) — generated API docs, straight from source
- [CLI reference](02-cli-reference.md) — every `omnist` subcommand
- [Status & limitations](limitations.md) — conformance results, coverage, what's implemented
