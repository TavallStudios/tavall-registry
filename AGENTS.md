# Repository instructions

This repository is an authoritative public Tavall Java module.

## Source of truth

- `TavallStudios/<repository>:main` is canonical for code, releases, tags, issues, and public review.
- A matching `tjXJNOOBIE/<repository>` repository is a contribution fork.
- `TavallMonoRepo` is a private integration workspace, not an alternate source of truth.
- A change is canonical only after it is merged into this TavallStudios repository.

## Working rules

- For a change limited to this module, work on a branch in a personal fork. Its automation should open or update a draft pull request against this repository.
- Cross-module work may begin in TavallMonoRepo, but each affected public module still requires its own pull request here.
- Validate this module independently before merge.
- Releases, tags, package publication, and issue tracking belong to this repository.
- Do not force-push `main` or automation-owned `sync/**` branches.
- Do not overwrite concurrent work. When both the monorepo and public repository changed, use the generated reconciliation pull request and resolve any normal Git conflicts there.
- Preserve public commit ancestry and automation sync trailers; they are used to find the last shared state.

Read [CONTRIBUTING.md](CONTRIBUTING.md) before changing the repository workflow.
