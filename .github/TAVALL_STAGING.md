# Tavall Registry Staging Root

```text
<!-- tavall-staging:v1 -->
Type: REPOSITORY_INTEGRATION
State: ACTIVE
Branch: staging/platform
Parent: main
Promotion: MANUAL
ChildMergeTarget: staging/platform
```

This branch is the combined integration tree for Tavall Registry APIs, indexed-registry behavior, package/dependency changes, and downstream compatibility. Child merges are integration for combined validation, not production promotion.

## Validated promotions (2026-10-15)

- **Tavall Registry 1.1.0** — #8 (pinned exact-source CI, versioned artifact) and #14 (universal module lifecycle registry) through staging #7 to main `5f5a501f1591b0577f3fe9e0a28b101205778d86`. CI: #8 `9eabf225`, #14 `26a054bd`, staging `c2738665` (artifact `8ad444be…`).
- **Tavall Registry 1.2.0** — #15 (typed module operator API and runtime transport SPI) through staging #16 to main `92eff633a26822cb6e9ac0d95661a200d544296e`. CI: #15 `2b82bbe6`, staging `2d2b8c8d`.

Unreleased branches use `0.<iteration>.<sub>-SNAPSHOT` through `tavallRegistryVersion`; release preparation on this branch sets the release version and RELEASE channel. This branch continues as the persistent integration root.
- **Tavall Registry 1.3.0** — #18 (module catalog and deployment module-profile compatibility validation) through staging #17 to main. CI: #18 `42bd87de`, staging `3697327d`.
- **Tavall Registry 1.3.1** — #20 (P0 lifecycle review fixes: participant onLoadAsync before LOADED, recorded SYSTEM cascade failures, reverse-dependency shutdown) through staging #19 to main. CI: #20 `e8fb5ef2`.
- Promoted to main `93e25139925b42a2953ae7e44dcea592cc1152bc` (tree equals staging CI `57abd43c`).
- **Tavall Registry 1.3.2** — #22 (restore residual obligations of disabled modules after restart) through staging #21 to main. CI: #22 `31e7db3d`.
- Promoted to main `77834806394bca6bfb6eb1535b7a52ed466f8c78` (tree equals staging CI `8ff95c26`).
