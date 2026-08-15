<div align="center">

# RollBack

**Conflict-aware history and rollback for Minecraft servers.**

Inspect block changes, search gameplay history, and safely roll back world and inventory changes without NMS.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.x%20baseline-2ea043?style=flat-square)](COMPATIBILITY.md)
[![Java](https://img.shields.io/badge/Java-17%2B-e76f00?style=flat-square)](https://adoptium.net/temurin/releases/?version=17)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/TrueWulf/RollBack/build.yml?branch=main&style=flat-square&label=build)](https://github.com/TrueWulf/RollBack/actions/workflows/build.yml)

[Support RollBack on Ko-fi](https://ko-fi.com/truewulf/goal?g=0)

</div>

## Overview

RollBack is a lightweight CoreProtect-style history plugin for Bukkit-compatible servers. It records server changes and gives administrators a clear way to inspect and reverse them without restarting the server.

The plugin uses Bukkit APIs and platform-aware scheduling. SQLite works out of the box. WorldEdit and DuckDB are optional.

## Quick Start

1. Install `RollBack-0.5.0.jar` on the backend server.
2. Start the server and check `/rb status`.
3. Preview a rollback before applying it:

```text
/rb rollback 10m --near=10 --preview
```

4. Apply the same operation when the preview is correct:

```text
/rb rollback 10m --near=10
```

`--near=10` means ten blocks in each direction from your current position. It searches a block cube, not ten chunks.

## Features

- Block history with placement and break summaries.
- Conflict-aware block rollback, restore, and undo.
- Inventory snapshots with transaction IDs and current-state checks.
- Container, item, death, kill, crafting, chat, command, session, and sign history.
- Time, radius, world, actor, event type, include, action, and count filters.
- Chunk, looking, radius, and optional WorldEdit selection scopes.
- SQLite by default with optional DuckDB support and migration.
- Paginated chat results with clickable navigation.
- Folia-aware region and global scheduling.
- Optional Velocity and Waterfall command-routing adapters.
- Java/Kotlin API with lookup and rollback callbacks.
- English, Russian, Polish, German, Italian, Spanish, Portuguese, Brazilian Portuguese, Japanese, and Finnish locales.

## Compatibility

| Platform | Artifact | Status |
| --- | --- | --- |
| Bukkit, Spigot, Paper, Pufferfish, Purpur, Leaf, Patina | `RollBack-0.5.0.jar` | Bukkit API build |
| Folia | `RollBack-0.5.0.jar` | Region scheduler path included |
| Velocity | `rollback-velocity-0.5.0.jar` | Separate adapter |
| Waterfall / BungeeCord-compatible proxy | `rollback-waterfall-0.5.0.jar` | Separate adapter |

The main artifact is compiled against Spigot API `1.20.6-R0.1-SNAPSHOT` and is intended for compatible Paper-family forks. Runtime verification must use the matching server version. Proxy adapters must not be installed in a Bukkit or Paper server.

See [`COMPATIBILITY.md`](COMPATIBILITY.md) for the support matrix and verification notes. See [`PROXY.md`](PROXY.md) for network deployment.

## Installation

1. Download `RollBack-0.5.0.jar` from the release artifacts.
2. Put it in the backend server `plugins` directory.
3. Start the server once.
4. Review `plugins/RollBack/config.yml`.
5. Grant the required `rollback.*` permissions.

WorldEdit is optional. Install WorldEdit or FAWE when using `--scope=selection`.

## Commands

| Command | Description |
| --- | --- |
| `/rb status` | Show plugin and database status. |
| `/rb inspect` | Inspect the block under the crosshair. |
| `/rb lookup <time> [radius] [player] [type]` | Search history without changing the world. |
| `/rb rollback <time> [radius] [player] [type]` | Apply conflict-aware rollback. |
| `/rb restore <time> [radius] [player] [type]` | Reapply recorded block or inventory state. |
| `/rb undo` | Undo the latest completed rollback operation. |
| `/rb purge <duration>` | Remove old events. |
| `/rb reload` | Reload configuration and locale settings. |
| `/rb migrate-db` | Copy SQLite data to a new DuckDB file. |

The full command is `/rollback`; `/rb` is its alias.

### Common examples

```text
/rb lookup 1h --near=10
/rb lookup 1h --scope=chunk --type=BLOCK
/rb lookup 1h --world=world_nether --include=diamond
/rb lookup 1h --player=Steve,Alex --action=+BLOCK
/rb lookup 1h --scope=selection
/rb rollback 10m --near=10 --preview
/rb rollback 10m --near=10 --action=+BLOCK
/rb undo
```

`--near=10` searches a 10-block cube around the player's current position. It is not a chunk radius. A rollback changes only events in the selected scope and time range; blocks with a newer conflicting state are skipped.

### What gets rolled back

- Blocks are rolled back automatically when their current state still matches the recorded change.
- Player inventory transactions use before/after snapshots and are skipped when the inventory changed afterwards.
- Container transfer events are recorded and searchable; automatic container restoration is not enabled yet.
- Preview never changes the world or inventory.

See [`CONFIGURATION.md`](CONFIGURATION.md) for filters, database settings, permissions, and inventory rollback behavior.

## Database

SQLite is the default and is recommended for a single backend server. DuckDB is available for local analytics and lookup-heavy workloads:

```yaml
database:
  type: sqlite
```

To migrate to DuckDB:

```text
/rb migrate-db
```

The command creates a timestamped SQLite backup. Set `database.type: duckdb` and restart the server after a successful migration. PostgreSQL or MariaDB is the recommended future option for shared multi-server network history; SQLite and DuckDB should not be used as a concurrently-written proxy-wide database.

## Building

```bash
mvn clean test package
mvn -f proxy/pom.xml clean test package
```

Artifacts are written to:

```text
target/RollBack-0.5.0.jar
proxy/velocity/target/rollback-velocity-0.5.0.jar
proxy/waterfall/target/rollback-waterfall-0.5.0.jar
```

## License

RollBack is licensed under [GNU General Public License v3.0](LICENSE).

## Support

If RollBack is useful for your server, you can support development on [Ko-fi](https://ko-fi.com/truewulf/goal?g=0).

TrueWulf is the original author and maintainer of RollBack.
