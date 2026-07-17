# Contributing

Thank you for contributing to this Tavall Java module. This repository is the public source of truth for the module.

## Repository roles

| Repository | Role |
| --- | --- |
| `TavallStudios/<repository>` | Canonical code, review, releases, tags, and issues |
| `tjXJNOOBIE/<repository>` | Personal contribution fork |
| Private `TavallMonoRepo` | Cross-project development and integration |

The monorepo mirrors this module, but it does not replace this repository's history or review process.

## A change to one module

1. Update your personal fork from `TavallStudios/main`.
2. Create a topic branch in the fork; do not commit directly to `main`.
3. Make and validate the change in the standalone module.
4. Push the topic branch. The installed workflow opens or updates a draft pull request against `TavallStudios/main`.
5. Review and merge that pull request here.
6. The private monorepo's inbound workflow imports the accepted public history.

This is the preferred path for ordinary module work.

## A change spanning modules

Cross-module work can be developed and tested together in TavallMonoRepo. Each affected public module must still receive a focused pull request. Merge those module pull requests before treating the change as canonical. The inbound workflow then records the accepted module histories back in the monorepo.

## Concurrent changes

If this public repository and the monorepo changed from their last shared public commit, automation must not choose a winner or force-push either side. It creates a deterministic reconciliation branch and pull request:

- Non-overlapping edits should merge normally.
- Overlapping edits appear as ordinary Git conflicts and require review.
- The public repository remains unchanged until the reconciliation pull request is merged.
- A later inbound sync records the accepted result in the monorepo.

Never bypass this process by rewriting `main` or an automation proposal branch.

## Branches and history

- `main` is the canonical branch.
- `sync/**` branches are reserved for synchronization automation.
- `upstream/**` branches in personal forks are reserved for automatic upstream pull requests.
- Keep commits focused and preserve meaningful ancestry.
- Include validation appropriate to the changed module.

## Releases and support

Create releases, tags, package publications, and issues in the corresponding TavallStudios module repository. Personal forks and the private monorepo are not release authorities.

AI coding tools must also follow [AGENTS.md](AGENTS.md).
