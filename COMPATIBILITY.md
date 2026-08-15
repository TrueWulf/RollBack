# Compatibility

## Backend servers

RollBack uses the Bukkit API and a scheduler adapter instead of NMS. Backend artifacts are compiled against the matching Paper or Spigot API family.

| Server | Version | Artifact | Verification status |
| --- | --- | --- | --- |
| Paper, Pufferfish, Purpur, Leaf, Patina | 1.20.x | `RollBack-1.20.x.jar` | API build verified; runtime pending |
| Spigot and Bukkit-compatible forks | 1.20.x | `RollBack-Spigot-1.20.x.jar` | API build verified; runtime pending |
| Paper, Pufferfish, Purpur, Leaf, Patina | 1.21.x | `RollBack-1.21.x.jar` | API build verified; runtime pending |
| Spigot and Bukkit-compatible forks | 1.21.x | `RollBack-Spigot-1.21.x.jar` | API build verified; runtime pending |
| Paper, Pufferfish, Purpur, Leaf, Patina | 26.1.x | `RollBack-26.1.x.jar` | API build verified; runtime pending |
| Paper, Pufferfish, Purpur, Leaf, Patina | 26.2 | `RollBack-26.2.jar` | API build verified; runtime pending |
| Folia | Matching Paper-family artifact | Matching versioned JAR | Regionized scheduler included; runtime pending |

The workspace does not contain server JARs, so these runtime statuses must not be read as a claim that every fork has been run locally.

## Optional integrations

- WorldEdit and FAWE are optional and accessed through reflection.
- SQLite is bundled in the backend artifact.
- DuckDB is bundled in the backend artifact but is disabled unless selected in `config.yml`.
- Velocity and Waterfall use separate proxy artifacts.

## Proxy versions

Velocity and Waterfall adapters are not Minecraft-versioned backend plugins. They compile against the proxy API and forward `/rb` through plugin messaging to a backend RollBack installation. Use `rollback-velocity-0.6.0.jar` for Velocity and `rollback-waterfall-0.6.0.jar` for Waterfall with backend servers from the matrix above.

## Recommended verification

Run each backend artifact on a staging server with the matching Minecraft version:

1. Start the server and run `/rb status`.
2. Place and break blocks, then run `/rb lookup 10m --scope=looking`.
3. Run `/rb rollback 10m --near=10 --preview`, followed by the same command without `--preview`.
4. Change a block after the event and confirm the rollback reports a conflict.
5. Open a container, move items, and test `/rb lookup 10m --type=INVENTORY`.
6. Install WorldEdit, create a selection, and test `/rb lookup 1h --scope=selection`.
7. Remove WorldEdit and confirm the selection command reports a readable error.
8. Run `/rb migrate-db` on a backup copy of an SQLite database.
