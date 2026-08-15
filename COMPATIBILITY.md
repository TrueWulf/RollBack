# Compatibility

## Backend servers

RollBack uses the Bukkit API and a scheduler adapter instead of NMS. The main artifact is compiled against Spigot API `1.20.6-R0.1-SNAPSHOT`.

| Server | Support target | Verification status |
| --- | --- | --- |
| Bukkit | Bukkit-compatible runtime | Build verified; runtime pending |
| Spigot | Spigot API 1.20.6 | Build verified; runtime pending |
| Paper | Paper-family 1.20.x | Build verified; runtime pending |
| Pufferfish | Paper-compatible fork | Runtime pending |
| Purpur | Paper-compatible fork | Runtime pending |
| Leaf | Paper-compatible fork | Runtime pending |
| Patina | Paper-compatible fork | Runtime pending |
| Folia | Regionized scheduler | Build verified; runtime pending |

The workspace does not contain server JARs, so these runtime statuses must not be read as a claim that every fork has been run locally.

## Optional integrations

- WorldEdit and FAWE are optional and accessed through reflection.
- SQLite is bundled in the backend artifact.
- DuckDB is bundled in the backend artifact but is disabled unless selected in `config.yml`.
- Velocity and Waterfall use separate proxy artifacts.

## Recommended verification

Run each test on a staging server with the matching Minecraft version:

1. Start the server and run `/rb status`.
2. Place and break blocks, then run `/rb lookup 10m --scope=looking`.
3. Run `/rb rollback 10m --near=10 --preview`, followed by the same command without `--preview`.
4. Change a block after the event and confirm the rollback reports a conflict.
5. Open a container, move items, and test `/rb lookup 10m --type=INVENTORY`.
6. Install WorldEdit, create a selection, and test `/rb lookup 1h --scope=selection`.
7. Remove WorldEdit and confirm the selection command reports a readable error.
8. Run `/rb migrate-db` on a backup copy of an SQLite database.
