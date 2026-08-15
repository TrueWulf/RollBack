# Proxy Adapters

RollBack uses separate proxy artifacts because Velocity and Waterfall do not provide Bukkit world APIs.

## Artifacts

| Proxy | Artifact |
| --- | --- |
| Velocity 3.x | `rollback-velocity-0.6.0.jar` |
| Waterfall / BungeeCord-compatible | `rollback-waterfall-0.6.0.jar` |

Install the proxy artifact in the proxy's plugin directory. Proxy artifacts are independent of the backend Minecraft version. Install the matching versioned RollBack backend artifact separately on every backend server that should record or apply rollbacks.

For example, use `RollBack-1.20.x.jar` on a Paper 1.20.x backend and `RollBack-Spigot-1.21.x.jar` on a Spigot 1.21.x backend. The same proxy adapter can route to both.

## What the adapters do

- Register `/rb` and `/rollback` on the proxy.
- Forward the command to the player's current backend through the `rollback:command` plugin-message channel.
- Keep world mutation on the backend where the world is loaded.

The adapters do not perform network-wide block rollback. A proxy cannot safely change blocks on a backend without a backend plugin and a selected target server.

## Network database

SQLite and DuckDB are local embedded databases. Do not point multiple backend servers at the same database file. A shared network history backend should use PostgreSQL or MariaDB when that backend is added.
