# Contributing to tavall-registry

This repository is the canonical standalone source of truth for `tavall-registry`.

## Development workflow

1. Clone or fetch this repository:
   ```bash
   git clone https://github.com/TavallStudios/tavall-registry.git
   ```
2. Create a focused topic branch (`working/<topic>`).
3. Make changes and validate locally:
   ```bash
   ./gradlew check
   ```
4. Push your branch and open a pull request targeting `main`.
5. Comply with canonical Tavall code review, testing, and architecture policy from [Tavall Docs](https://github.com/TavallStudios/tavall-docs).

## Monorepo integration

`TavallMonoRepo` represents this module as a read-only Git submodule gitlink for integration verification. Development does not occur in `TavallMonoRepo`, and there is no source export back into this repository.
