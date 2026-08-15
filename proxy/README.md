# RollBack Proxy Adapters

The Bukkit plugin owns block, inventory, and database operations. Proxy adapters are intentionally separate artifacts:

- `velocity`: command routing and network session events for Velocity.
- `waterfall`: command routing and network session events for Waterfall/BungeeCord.

They should use the same shared database only for network-wide audit events. A proxy cannot safely mutate backend world state.

Recommended production setup:

1. Keep RollBack on every backend server.
2. Use PostgreSQL or MariaDB for shared network audit data when multiple servers write concurrently.
3. Let the proxy adapter route `/rb` to a backend server; keep rollback execution on that backend.
