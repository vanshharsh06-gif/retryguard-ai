-- V5__concurrent_workflow_prevention.sql
-- Enforce at most one active (RUNNING) workflow per merchant order
-- at the database level using a PostgreSQL partial unique index.
-- This prevents race conditions where two simultaneous requests both
-- pass the application-level check and create duplicate RUNNING workflows
-- for the same order.

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_workflows_one_running_per_order
    ON agent_workflows (order_id)
    WHERE status = 'RUNNING';
