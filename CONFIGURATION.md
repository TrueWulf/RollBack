# Configuration

## Query scopes

| Scope | Meaning |
| --- | --- |
| `--near=N` | Search a block cube around the player. |
| `--scope=radius` | Use the configured or explicit radius. |
| `--scope=chunk` | Search the player's current chunk. |
| `--scope=looking` | Search the exact block under the crosshair. |
| `--scope=selection` | Search the current WorldEdit or FAWE selection. |

`--near=10` means `X ± 10`, `Y ± 10`, and `Z ± 10` around the player. It does not mean ten chunks.

## Filters

```text
--time=1h
--near=10
--world=world_nether
--player=Steve,Alex
--type=BLOCK
--include=diamond
--action=+BLOCK
--count
--page=2
```

Supported actions include `BLOCK`, `+BLOCK`, `-BLOCK`, `CONTAINER`, `+CONTAINER`, `-CONTAINER`, `INVENTORY`, `ITEM`, `DEATH`, `KILL`, and `CRAFT`.

## Rollback safety

Block rollback checks the current block state before applying the recorded previous state. If another player changed the block after the recorded event, RollBack skips it and reports a conflict.

Inventory rollback stores a transaction ID and serialized before/after snapshots. The current inventory must still match the recorded after snapshot before RollBack applies the before snapshot. Inventory rollback requires the actor to be online and is intended for player inventory transactions; container transfer events remain history until container snapshots are implemented.

Use preview before a production rollback:

```text
/rb rollback 10m --near=10 --preview
/rb rollback 10m --near=10
```

## Database

```yaml
database:
  type: sqlite
  file: rollback.db
  duckdb-file: rollback.duckdb
```

SQLite is the default for a single server. DuckDB is a local alternative. For multiple backend servers, use a future PostgreSQL or MariaDB backend rather than sharing SQLite or DuckDB files.

## Permissions

- `rollback.admin`
- `rollback.lookup`
- `rollback.rollback`
- `rollback.restore`
- `rollback.undo`
- `rollback.purge`
- `rollback.reload`
- `rollback.migrate`

All command permissions default to operators.
