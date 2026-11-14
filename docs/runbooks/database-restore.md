# Database restore and crash recovery

Applies to PostgreSQL after a restore from backup (`pg_restore`, a volume snapshot or point-in-time recovery) and after an
unclean stop (crash recovery). Owner: operations. Status: procedure for the deployed environment; the restore itself is
covered by the backup plan, which is still an open production gate (encryption-key custody, backup and recovery).

## Why

Planner statistics are not part of a logical backup, and an unclean stop resets the activity counters that trigger
autovacuum's `ANALYZE`. Until tables are analysed, PostgreSQL may estimate one row per table and choose very slow plans:
KI-86 measured the former enrolment-history query at **217 s** instead of 5 ms (`docs/performance/sql/ki86-enrolment-history-without-statistics.sql`).
That query has been rewritten so it no longer depends on statistics, but other queries may still do.

## Steps

1. Keep the application out of service (delivery and administrative processes stopped or not receiving traffic).
2. Restore, or let crash recovery finish (`pg_isready`; the server log shows "database system is ready").
3. Analyse every table in the application database, as the owner or a superuser:

   ```bash
   psql -d ivorovi -c "ANALYZE VERBOSE"
   ```

4. Check that no application table is left without statistics; the query must return no rows:

   ```sql
   SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = 'ivorovi' AND c.relkind = 'r' AND c.reltuples < 0;
   ```

5. Start the processes and check readiness (`/actuator/health/readiness` on both).
6. Record the restore (time, backup used, who) in the operations log.

## Development and test

Local Docker restarts that kill PostgreSQL have the same effect. If a page is suddenly slow after one, run step 3 against the
local database (`docker exec ivorovi-postgres-1 psql -U postgres -d ivorovi -c ANALYZE`).
