# Contributing

Thank you for contributing to this Tavall Java module. This repository is the public source of truth for the module.

## Repository roles

| Repository | Role |
| --- | --- |
| `TavallStudios/<repository>` | Canonical code, review, releases, tags, and issues |
| Private `TavallMonoRepo` | Cross-project integration workspace |
| Personal forks | Optional external or experimental contribution surfaces |

The private monorepo may mirror or integrate this module, but it does not replace this repository's history or review process.

## A change to one module

1. Create a focused topic branch directly in the TavallStudios repository.
2. Make and validate the change in the standalone module.
3. Push the topic branch and open or update a pull request against `main`.
4. Review and merge that pull request here.
5. Any private integration workspace may consume the accepted TavallStudios history afterward.

This is the preferred path for ordinary Tavall development. A personal fork is optional, not required.

## A change spanning modules

Coordinate the work across the affected TavallStudios repositories and keep one focused pull request per repository. Stacked pull requests are valid when one repository change depends on another change that has not merged yet. The private monorepo can be used for integration testing, but it is not the required first authoring location and must not replace the standalone repository histories.

## Concurrent changes

Do not force one branch to win over another. Non-overlapping edits should merge normally; overlapping edits should be resolved through normal Git conflicts or stacked/rebased pull requests. The public repository remains authoritative throughout the process.

## Branches and history

- `main` is the canonical branch.
- `sync/**` branches are reserved for synchronization automation.
- Use focused topic branches for ordinary work.
- Keep commits focused and preserve meaningful ancestry.
- Include validation appropriate to the changed module.

## Releases and support

Create releases, tags, package publications, and issues in the corresponding TavallStudios module repository. Personal forks and the private monorepo are not release authorities.

AI coding tools must also follow [AGENTS.md](AGENTS.md).
