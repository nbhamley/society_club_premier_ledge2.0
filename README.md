# Society Club Premier League (SCPL) v2.0

Multi-tenant cricket league management SaaS. Java 17, Spring Boot 3.2, Thymeleaf, Spring Security,
JPA/Hibernate 6, SQLite by default, Flyway-managed schema.

## Quick start

```bash
mvn spring-boot:run          # dev profile, SQLite file at ./data/scpl.db
```

Open http://localhost:8080. On first run Flyway applies `V1__init.sql` and the seeder loads
demo data (Phase 8). Dev logins (printed at startup when `feature.dev-log-credentials=true`):

| Login | Password | Role |
|---|---|---|
| platformadmin | Passw0rd! | PLATFORM_ADMIN |
| gv.admin1 | Passw0rd! | SOCIETY_ADMIN (Green Valley) |
| sh.admin1 | Passw0rd! | SOCIETY_ADMIN (Sunrise Heights) |
| gv.player1 | Passw0rd! | SOCIETY_PLAYER (Green Valley) |

## Schema management (Flyway only)

- `spring.jpa.hibernate.ddl-auto=validate` is the only Hibernate setting. Hibernate never creates
  or alters tables. Deleting `db/migration/*` makes startup fail, which proves this.
- Every schema change is a new file: `V2__add_x.sql`, `V3__...`. Never edit an applied migration.
- SQLite cannot drop or alter most column constraints; in V2+ use the create-new-table, copy,
  drop, rename pattern.

### Type conventions (V1)

| Java | SQLite column | Notes |
|---|---|---|
| Long id | INTEGER PRIMARY KEY AUTOINCREMENT | `GenerationType.IDENTITY` |
| enum | TEXT | `@Enumerated(EnumType.STRING)`, CHECK constraints on key enums |
| Boolean | INTEGER 0/1 | |
| LocalDate / LocalDateTime | TEXT ISO-8601 | driver props `date_class=TEXT`, `date_string_format` |
| Double | REAL | |

### Switching to MySQL / PostgreSQL

V1 uses SQLite-only syntax (`AUTOINCREMENT`, partial-index flavour), so production does not replay it.
The strategy is a dedicated production baseline with identical table and column names:

1. Create `src/main/resources/db/migration-postgresql/V1__init.sql` (or `db/migration-mysql/`)
   by translating V1 with this mapping:

   | SQLite | PostgreSQL | MySQL |
      |---|---|---|
   | `INTEGER PRIMARY KEY AUTOINCREMENT` | `BIGSERIAL PRIMARY KEY` | `BIGINT AUTO_INCREMENT PRIMARY KEY` |
   | `INTEGER` boolean 0/1 | `BOOLEAN` (or keep SMALLINT) | `TINYINT(1)` |
   | `TEXT` ISO dates | `TIMESTAMP` / `DATE` | `DATETIME` / `DATE` |
   | `TEXT` enums | `VARCHAR(40)` | `VARCHAR(40)` |
   | `TEXT` long content | `TEXT` | `LONGTEXT` |
   | `COLLATE NOCASE` unique index | `CREATE UNIQUE INDEX ... (lower(col))` | default case-insensitive collation |
   | partial indexes | supported as-is | not supported: drop the `WHERE` clause |

2. Later migrations (`V2__...`) are written once per vendor folder so histories stay aligned.
3. Run with the one-line switch: `SPRING_PROFILES_ACTIVE=prod` plus `SCPL_DB_URL`, `SCPL_DB_USER`,
   `SCPL_DB_PASSWORD`. For MySQL also uncomment the MySQL lines in `application-prod.properties`.

## Configuration reference

| Property | Default | Purpose |
|---|---|---|
| `scpl.uploads.dir` | `/var/scpl/uploads` (dev: `./data/uploads`) | Import batches: `{societyId}/{batchId}/` |
| `scpl.uploads.max-bytes` | 10485760 | Server-side upload cap, also enforced by multipart limits |
| `scpl.backup.master-key` | dev placeholder | Root secret for per-tenant backup keys. Override with `SCPL_BACKUP_MASTER_KEY` |
| `scpl.backup.retention-days` | 30 | Society backup purge window |
| `scpl.subscription.trial-days` | 14 | Free-trial length |
| `scpl.subscription.read-only-after-past-due-days` | 30 | Days PAST_DUE before the society turns read-only |
| `feature.ai.enabled` | false | Use `LocalAiSegregationProvider` when true and `ai.endpoint` is set |
| `ai.endpoint` | empty | Local AI base URL (Ollama / LM Studio / llama.cpp); app calls `POST {endpoint}/api/parse` |
| `feature.cross-society.enabled` | false | Gates tournament UI and endpoints |
| `feature.dev-log-credentials` | true in dev | Print seeded logins at startup |

## Backup encryption and the master key

Society exports are JSON, gzipped, then AES-256-GCM encrypted (BouncyCastle). The key is derived per
tenant as `SHA-256(master_key || societyId || exportedAt.toEpochDay())`; IV and GCM tag live in the file
header. Only this application can decrypt `.scplenc` files, via Platform Admin → Restore.

**Losing or changing `scpl.backup.master-key` makes every existing backup undecryptable.**
Store it in a secrets manager and set it through the environment. The prod profile has no default,
so startup fails if it is missing.

Unpaid societies (PAST_DUE or CANCELLED) can still download one final backup before the read-only
cutoff. This is documented on the billing page.

## Local AI import (optional)

Set `feature.ai.enabled=true` and `ai.endpoint=http://localhost:11434` (Ollama example). If the AI
call fails or the flag is off, the rule-based CSV header-heuristic segregator is used automatically.

## Troubleshooting

- **"Schema-validation: wrong column type"**: SQLite reports every column as INTEGER, REAL or TEXT, while
  Hibernate expects BIGINT, BOOLEAN, DATE, TIMESTAMP and so on. `com.scpl.config.SqliteDialect` treats types
  in the same storage family as equivalent. If you map a Java type that falls outside the families listed there,
  extend `SqliteDialect.family()`. Also check that the dev profile is active: the dialect is set in
  `application-dev.properties`.
- **"unable to open database file"**: the `./data` directory is created at startup by `FlywayConfig`. Check that the
  process can write to the working directory.

## Build phases

1 Foundation · 2 Domain · 3 Repositories · 4 Services · 5 Security & config · 6 Web layer ·
7 Jobs & integrations · 8 Seeder · 9 Templates · 10 Static assets · 11 Tests


## Restoring

- **One society** (Platform Admin → Restore): upload a `.scplenc` file plus at least 3 founding admins. It becomes a NEW
  draft society with a fresh trial; nothing is overwritten. The master key that made the file must still be configured.
- **Full database dump**: the upload only STAGES the file under `{scpl.backup.dir}/staged/` with its SHA-256. Applying it
  is an ops task: stop the app, verify the checksum, replace `data/scpl.db` (SQLite only), start the app. The app never
  swaps its own live database. PostgreSQL deployments restore with their own tooling.
- Behind a reverse proxy, allow ~256 MB bodies only on `/platform-admin/restore*` and 10 MB on the import routes.


## Scheduled jobs

| Job | When | What |
|---|---|---|
| ImportPollingJob | every minute after the previous run ends | segregates UPLOADED import batches |
| SubscriptionDunningJob | 03:00 (`scpl.subscription.dunning-cron`) | trial conversion, renewals, retries, cancellation |
| NightlyBackupJob | 02:30 (`scpl.backup.nightly-cron`) | whole-database dump to `{scpl.backup.dir}/{yyyy-MM-dd}-full.sqlite.gz` (`.sql.gz` for MySQL/PostgreSQL), keeps 7 daily + 4 weekly + 3 monthly |
| BackupPurgeJob | 03:30 (`scpl.maintenance.purge-cron`) | deletes society backups older than 30 days; deletes import files per retention |
| NotificationHousekeepingJob | 03:45 | deletes read notifications older than `scpl.notifications.retention-days` |

Jobs run inside the web process and assume ONE node. Behind several nodes, set `scpl.jobs.enabled=false` on all but one.
The nightly dump is gzip only, NOT encrypted, and holds every society's player details and password hashes: restrict the
directory (the app writes files as owner-only where the OS allows) and use disk encryption. MySQL/PostgreSQL dumps need
`mysqldump` / `pg_dump` on the PATH of the app's user.