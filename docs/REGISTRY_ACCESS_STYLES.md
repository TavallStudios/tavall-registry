# Tavall Registry Access Styles

> **Document Type:** Reference
> **Status:** Current API reference; consumer-pattern ranking under review
> **Authority:** `TavallStudios/tavall-registry@main` and `TavallStudios/tavall-docs@main`
> **Owns:** Verified current Registry API and candidate access patterns. Final preferred application shape remains under review.
> **Does Not Own:** Whether a runtime responsibility should use a Registry, or the owning runtime's lifecycle/thread contract.
> **Notion:** No twin designated; reference documents are synchronized only by request or explicit policy.

## Purpose

This guide documents how the current Registry tool actually behaves and surveys supported consumer access options. The former ranking is not an approved universal implementation. Choose the approach justified by real domain ownership and existing consumers rather than treating a sample subclass as a required template.

The architecture rule for when to use a Registry at all lives in [tavall-docs `REGISTRIES_CACHES_AND_REPOSITORIES.md`](https://github.com/TavallStudios/tavall-docs/blob/main/docs/quality/code-architecture/REGISTRIES_CACHES_AND_REPOSITORIES.md): a Registry owns runtime keyed identity (definitions, providers, strategies, active objects, sessions) for one runtime generation. This guide covers how to use `tavall-registry` once that decision is made.

The ownership rule:

> One Registry owns a given runtime keyed identity. Consumers do not introduce competing maps, Service facades, or alternate Registry objects. Tavall DI manages Registry instances where these are managed collaborators.

The public Java Tool API below is **verified**. The choice between a domain subclass and direct semantic Registry access is still **subject to design review**. Neither is mandatory merely because it appears in this guide.

## Scope and ownership

This guide owns the ranked consumer patterns for `AbstractRegistry` and its specialized variants. The current repository API owns their method behavior. Tavall Docs owns the rule for when Registry is the correct runtime-state boundary; the runtime or module using a Registry remains responsible for its lifecycle and thread ownership.

---

## The Contract

`AbstractRegistry<REG_KEY, REG_DATA>` extends `ConcurrentHashMap<REG_KEY, REG_DATA>` and implements `IAbstractRegistry<REG_KEY, REG_DATA>`. The Tavall-named API is:

| Method | Behavior |
| --- | --- |
| `createRegistry(key, data)` | Publishes `data` under `key` if the key is absent (`putIfAbsent`). The first registration wins; `null` key or data throws `NullPointerException`. Returns the registry. |
| `getRegistryData(key)` | The data for `key`, or `null`. |
| `getRegistryKeyByData(data)` | Reverse lookup: one key whose data equals `data`, or `null`. If multiple keys have equal data, iteration order does not define a stable winner. |
| `getRegistryKeysAsSet/List/Collection()` | Unmodifiable snapshot of the keys. |
| `getRegistryDataAsSet/List/Collection()` | Unmodifiable snapshot of the data. |
| `hasRegistryKey(key)` / `hasRegistryData(data)` | Presence checks; `null` is never present. |

The inherited `ConcurrentMap` operations (`put`, `remove`, `compute`, `clear`, ...) are part of the contract. They are the implementation mechanism for a registry subclass, not the consumer vocabulary.

`AbstractIndexedRegistry<REG_KEY, REG_DATA>` adds serialized `registerIndexed`, `unregisterIndexed`, and `clearIndexed` for registries that maintain secondary lookups through idempotent `index`/`unindex` hooks, with rollback when index publication fails.

It is optional. The existing reverse lookup and snapshot APIs cover common cases; add an indexed subclass only when a distinct maintained lookup is required by the owning domain or a measured access need.

## Runtime and thread ownership

The base Registry provides concurrent-map behavior, but it does not make stored values thread-safe or replace their runtime owner. For Bukkit/Paper-owned state, keep Bukkit objects and mutations on the Paper primary thread; do not use concurrent maps to move gameplay state to worker threads. A secondary index must follow its owning Registry's actual mutation and read synchronization boundary.

---

## Current API-Backed Options (Ranking Under Review)

| Option | Current verified behavior | Selection concern |
| --- | --- | --- |
| Domain `AbstractRegistry` subclass | Supported typed keys/values and domain methods | Justify the added domain methods; not mandatory for every Registry |
| Direct Tavall-named `IAbstractRegistry` API | Supported semantic lookup/registration/snapshots | Appropriate when its existing vocabulary is sufficient |
| `AbstractIndexedRegistry` | Supported secondary indexes and coordinated mutation hooks | Only when a required secondary lookup exists |
| Inherited `ConcurrentMap` operations | Available in the base class and used for subclass mutation | Do not expose raw map access as an accidental consumer API |
| Competing maps or forwarding services | No ownership justification | Reject |

This is **not** a ranked, final class-construction prescription. Preserve existing supported access pending explicit migration decisions.

---

# Style 1: Domain Registry Subclass Through Tavall DI

## Selection Status

Supported API-backed option, **not a mandated default**. Use when domain-specific operations add meaningful ownership beyond renaming the same map lookup.

## Shape

```java
@DelegatesTo
public final class AdminDashboardModuleRegistry
        extends AbstractRegistry<String, AdminDashboardModule> {

    public void register(AdminDashboardModule module) {
        createRegistry(module.moduleKey(), module);
    }

    public Optional<AdminDashboardModule> module(String moduleKey) {
        return Optional.ofNullable(moduleKey == null ? null : getRegistryData(moduleKey));
    }

    public List<AdminDashboardModule> modules() {
        return getRegistryDataAsList();
    }
}
```

The owning bootstrap/runtime creates the registry, fills it, and registers it in the `IDependencyMap`. Consumers declare it like any other managed collaborator and read it through a named getter:

```java
@DelegatesTo
public final class AdminDashboardHandler
        implements IAdminDashboardHandler,
        DependencyAccess<ITavallOrganizationDataHandler, AdminDashboardModuleRegistry> {

    private AdminDashboardModuleRegistry getAdminDashboardModuleRegistry() {
        return getInstance().adminDashboardModuleRegistry();
    }
}
```

## When It Helps

- The subclass name states which identity it owns, and its methods state the lookups consumers need (`module`, `ownerOf`), so callers never learn the key encoding.
- `Optional` return types replace the `null` that `getRegistryData` returns for a missing key.
- Tavall DI owns the instance, so replacement on reload follows the same metadata as every other managed object ([tavall-di access styles](https://github.com/TavallStudios/tavall-di/blob/main/docs/DI_ACCESS_STYLES.md), style 1).

## Rules

- Use `createRegistry` for first-wins registration only (`putIfAbsent`). If replacement is required, use an explicit domain operation backed by supported Registry mutation (`put`, `replace`, or the indexed API as appropriate) and test it. Do not silently replace existing `put` with first-wins `createRegistry`.
- Return snapshots (`getRegistryDataAsList()`, `getRegistryKeysAsSet()`) rather than the live map or its views.
- The runtime that created the registry clears it on unload (`clear()`, or `clearIndexed()` for an indexed registry) before removing it from the dependency map.

---

# Style 2: Tavall-Named API Directly

## Selection Status

Supported semantic access; not inherently less preferred because no domain-specific wrapper was added.

## Shape

```java
IAbstractRegistry<Class<?>, CacheRegistryMetaData> registry = getCacheRegistry();
registry.createRegistry(cacheClass, metaData);
CacheRegistryMetaData current = registry.getRegistryData(cacheClass);
```

## Use When

The registry's vocabulary already is the domain vocabulary, for example a registry keyed by `Class<?>` whose consumers only register and look up.

## Avoid When

Consumers would repeat key construction, `null` checks, or ordering logic. Move those into Style 1 methods.

---

# Style 3: Indexed Registry

## Selection Status

Supported specialized Registry variant; use only for real secondary lookup requirements.

## Shape

```java
public final class SessionRegistry extends AbstractIndexedRegistry<UUID, Session> {
    private final Map<String, UUID> byToken = new HashMap<>();

    public synchronized Optional<Session> sessionByToken(String token) {
        UUID sessionId = byToken.get(token);
        return Optional.ofNullable(sessionId == null ? null : getRegistryData(sessionId));
    }

    @Override
    protected void index(UUID sessionId, Session session) {
        byToken.put(session.token(), sessionId);
    }

    @Override
    protected void unindex(UUID sessionId, Session session) {
        byToken.remove(session.token(), sessionId);
    }
}
```

## Rules

- Secondary structures are private implementation details of the one registry that owns the identity.
- Registry mutation hooks serialize index publication and rollback a failed index update. Expose secondary lookups through owner methods that follow the same synchronization boundary.
- `index` and `unindex` must be idempotent.
- Check first whether `getRegistryKeyByData` or a snapshot already answers the lookup.
- Use a concurrent secondary index only when its actual readers/writers are concurrently accessible outside that Registry boundary; do not add one merely to accommodate thread-unsafe Bukkit state.

---

# Style 4: Inherited `ConcurrentMap` Operations

## Selection Status

Supported subclass mechanics; keep consumer access domain-oriented.

Registry subclasses may use `put`, `remove`, `compute`, and `clear` to implement replacement, removal, and unload. Consumer code outside the registry does not call them: that bypasses the subclass's validation and, for indexed registries, desynchronizes the secondary indexes.

---

# Anti-Patterns

| Anti-pattern | Why it is rejected | Use instead |
| --- | --- | --- |
| A consumer-owned `Map` of the same identity | Two owners drift; unload clears only one | The registry, Style 1 |
| A static singleton registry | Bypasses Tavall DI replacement and generation cleanup | Register the instance in the `IDependencyMap` |
| A `*Manager` or `*Service` that only forwards to the registry | Forwarding wrapper with no ownership (tavall-docs `CLASSES.md`) | Use the current Registry's semantic API or genuinely owned domain methods |
| Returning the live map or `keySet()`/`values()` views | Callers mutate or iterate registry state without the registry | Registry-named snapshots |
| Relying on `createRegistry` to replace | It is first-wins; the new value is silently ignored | An explicit replacement method |
| No clear on unload | The next generation resolves stale identities | Clear in the owning bootstrap/runtime |

---

# Test Coverage Needed

- Registration and lookup through the domain methods, including a missing key.
- Duplicate registration behavior (first wins, or the documented replacement).
- Snapshot immutability where consumers receive collections.
- Unload/clear from the owning bootstrap.
- For indexed registries: index/unindex idempotency and rollback on a failing `index`.

---

<details>
<summary>Documentation Update State</summary>

### Current Locations

| Surface | Sync State | Location | Last Updated | Evidence |
| --- | --- | --- | --- | --- |
| GitHub | `PRIMARY` | [TavallStudios/tavall-registry/docs/REGISTRY_ACCESS_STYLES.md](https://github.com/TavallStudios/tavall-registry/blob/main/docs/REGISTRY_ACCESS_STYLES.md) | 2026-10-19 11:26 AM PDT | PR #12 merged while Draft at [`8d35df86`](https://github.com/TavallStudios/tavall-registry/commit/8d35df86d16f4395c014f7a14624f8939c27182b). |
| Notion | `NOT_APPLICABLE` | No Notion twin designated | 2026-10-19 11:26 AM PDT | Reference docs are shared only by request or explicit policy. |

### Update History

| Timestamp | Surface | Event | Location | Previous Location | Evidence | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-09-29 1:23 PM PDT | GitHub | `CREATED` | `docs/REGISTRY_ACCESS_STYLES.md` | — | [Registry PR #12](https://github.com/TavallStudios/tavall-registry/pull/12) | Added the consumer-access guide in the existing PR. |
| 2026-10-19 11:26 AM PDT | GitHub | `MERGED` | `docs/REGISTRY_ACCESS_STYLES.md` | `docs/REGISTRY_ACCESS_STYLES.md` | [PR #12 merge commit](https://github.com/TavallStudios/tavall-registry/commit/8d35df86d16f4395c014f7a14624f8939c27182b) | The verified guide, README index, local CI definition, and test-discovery isolation are on main. |

</details>
