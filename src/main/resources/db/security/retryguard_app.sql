-- RetryGuard AI — Restricted Production Database Account
-- ---------------------------------------------------------------------------
-- This script creates a dedicated, least-privilege database account
-- `retryguard_app` for running the RetryGuard AI application at runtime.
-- Schema migrations and administrative operations must be performed using a
-- separate, higher-privilege account (e.g. postgres), NOT this runtime account.
--
-- Execution method (run as a PostgreSQL superuser, e.g. postgres):
--   psql -d retryguard_db -f retryguard_app.sql
--   Then set the password interactively when prompted:
--     ALTER ROLE retryguard_app WITH LOGIN PASSWORD '<secure-password>';
--
-- The password is never hardcoded in this file, never passed on the command
-- line, and never echoed in script output. It must be set interactively or
-- through a secret-management system by a database administrator.
--
-- This script is NOT executed automatically by the application. It must be
-- applied manually by a database administrator.
-- ---------------------------------------------------------------------------

-- Create the runtime application role without an embedded password.
-- The password must be set separately through a secure prompt or secret manager.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname = 'retryguard_app'
    ) THEN
        CREATE ROLE retryguard_app LOGIN;
    END IF;
END $$;

-- Connect to the target application database.
\connect retryguard_db

-- Grant CONNECT on the application database.
GRANT CONNECT ON DATABASE retryguard_db TO retryguard_app;

-- Grant USAGE on the schema containing application tables.
GRANT USAGE ON SCHEMA public TO retryguard_app;

-- Grant only the privileges required for normal application runtime operations.
-- Review of repository operations shows the application performs SELECT, INSERT,
-- and UPDATE on tables. DELETE is NOT required for normal runtime operation and
-- is intentionally omitted to follow least-privilege principles.
GRANT SELECT, INSERT, UPDATE
    ON ALL TABLES IN SCHEMA public
    TO retryguard_app;

-- Sequences are intentionally NOT granted. The application's reviewed entities
-- use UUID-generated identifiers and do not rely on database sequences at runtime.
-- SELECT, USAGE on sequences are omitted to follow least-privilege principles.

-- Explicitly do NOT grant:
--   - DELETE, TRUNCATE, REFERENCES on tables
--   - SELECT, USAGE on sequences
--   - CREATE, DROP on schema or tables
--   - SUPERUSER, CREATEDB, CREATEROLE, BYPASSRLS, REPLICATION role attributes

-- Verification queries (run as postgres after applying this script):
--
--   1. Role existence and attributes:
--      SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole,
--             rolreplication, rolbypassrls
--      FROM pg_roles WHERE rolname = 'retryguard_app';
--
--   2. Database CONNECT privilege:
--      SELECT datname, datacl
--      FROM pg_database WHERE datname = 'retryguard_db';
--
--   3. Schema USAGE privilege:
--      SELECT nspname, nspacl
--      FROM pg_namespace WHERE nspname = 'public';
--
--   4. Required table privileges:
--      SELECT grantee, table_name, privilege_type
--      FROM information_schema.table_privileges
--      WHERE grantee = 'retryguard_app'
--      ORDER BY table_name, privilege_type;
