# Repository instructions

This repository is an authoritative standalone Tavall Java module.

## Source of truth

- `TavallStudios/tavall-registry:main` is canonical for code, releases, tags, issues, and pull requests.
- Ordinary development occurs directly in this repository on topic branches (`working/<topic>`).
- Cross-project engineering and Git workflow policy is governed by `TavallStudios/tavall-docs@main`.
- `TavallMonoRepo` is a pointer-only submodule aggregate for cross-project integration validation. It does not own, export, or mirror-edit code.

## Working rules

- Create a focused topic branch (`working/<topic>`) for changes to this module.
- Validate independently with `./gradlew check` (Java 25).
- Follow canonical Tavall Git and code architecture standards from `tavall-docs`.
- Pull requests target `main` and require standard review or owner self-review before merging.
- Releases, tags, package publication, and issue tracking belong to this repository.

Read [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidance.
