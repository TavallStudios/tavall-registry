# tavall-registry

A Java 25 library for thread-safe keyed registries and registry projections.

The single-module project provides an abstract map-backed registry and indexed/cache registry variants.

## Why tavall-registry

- Stores keyed values with concurrent-map-backed behavior.
- Supports lookup by key or data and collection views of keys and values.
- Provides registry variants for indexed and cached access.

## Features

- AbstractRegistry key/data storage
- Key and value membership checks
- Set, list, and collection projections
- Indexed registry and cache registry variants

## Quick Start

Add the published artifact to a Gradle project:

```kotlin
dependencies {
    implementation("org.tavall:tavall-registry:<version>")
}
```

Use the exact published version and repository access configured for your project. See the links below for API and contribution details.

## Project Structure

This repository is a single Java library module (Module Type: LIBRARY; Runtime: None).

## Documentation

| Document | Purpose |
| --- | --- |
| [Contributing](CONTRIBUTING.md) | Contribution and development notes. |
| [Repository Git Workflow](docs/quality/GIT_WORKFLOW.md) | Applicable repository guidance. |

## Requirements / Compatibility

Java 25.

## Building From Source

```bash
./gradlew check
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

No tracked license file is present in the current repository tree.

## Documentation Update State

<details>
<summary>Documentation Update State</summary>

### Current Locations

| Surface | Sync State | Location | Last Updated | Evidence |
| --- | --- | --- | --- | --- |
| GitHub | PRIMARY | TavallStudios/tavall-registry/README.md | 2026-09-27 12:29 PM PDT | Migration PR. |
| Notion | NOT_APPLICABLE | — | 2026-09-27 12:29 PM PDT | README files are not synchronized as Notion twins. |

### Update History

| Timestamp | Surface | Event | Location | Previous Location | Evidence | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-09-27 12:29 PM PDT | GitHub | UPDATED | TavallStudios/tavall-registry/README.md | Same path | Migration PR. | Reworked the public README to describe the current project, module boundary, usage, and documentation. |

</details>
