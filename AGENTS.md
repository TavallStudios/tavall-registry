# Repository instructions

This repository is an authoritative public Tavall Java module.

## Source of truth

- `TavallStudios/<repository>:main` is canonical for code, releases, tags, issues, and public review.
- `TavallMonoRepo` is a private integration workspace, not an alternate source of truth.
- A change is canonical only after it is merged into this TavallStudios repository.

## Working rules

- For a change limited to this module, work directly in this TavallStudios repository on a focused topic branch and open or update a pull request against `main`.
- Personal forks are optional contribution surfaces, not a required first hop for Tavall development.
- Cross-module work may be coordinated across the affected TavallStudios repositories, with one focused pull request per repository.
- Validate this module independently before merge.
- Releases, tags, package publication, and issue tracking belong to this repository.
- Do not force-push `main` or automation-owned `sync/**` branches.
- Do not overwrite concurrent work. When independent branches overlap, reconcile them through normal Git merges or stacked pull requests rather than choosing a winner by force.
- Preserve public commit ancestry and automation sync trailers when present; they are used to identify shared history.

Read [CONTRIBUTING.md](CONTRIBUTING.md) before changing the repository workflow.
