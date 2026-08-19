-- Runs once, on first initialisation of the Postgres data volume.
--
-- Two databases, one instance. Each service migrates its own and can see
-- nothing of the other's: Postgres has no cross-database query and no
-- cross-database transaction short of PREPARE TRANSACTION, so the impossibility
-- of a distributed transaction across this seam is enforced by the engine
-- rather than by anyone remembering not to try.
--
-- Sharing one container means they also share a failure domain. That is a
-- laptop concession and it is deliberate: the chaos test in Session 12 kills
-- *application* containers, so a shared database process does not weaken what
-- that test proves.
--
-- NOTE: docker-entrypoint-initdb.d scripts are skipped entirely if the data
-- volume already exists. If these databases are missing, the volume predates
-- this file -- `docker compose down -v` and bring it up again.

CREATE DATABASE ledger   OWNER spademoney;
CREATE DATABASE payments OWNER spademoney;
