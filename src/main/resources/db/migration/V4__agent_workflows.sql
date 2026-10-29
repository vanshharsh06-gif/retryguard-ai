-- V4__agent_workflows.sql
-- Additive migration for AI agent workflow persistence

CREATE TABLE IF NOT EXISTS agent_workflows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES merchant_orders(id),
    status VARCHAR(50) NOT NULL,
    summary TEXT,
    tool_calls_used INT NOT NULL DEFAULT 0,
    model_turns_used INT NOT NULL DEFAULT 0,
    provider_name VARCHAR(50),
    model_name VARCHAR(100),
    prompt_tokens INT,
    completion_tokens INT,
    total_tokens INT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_workflows_order_id ON agent_workflows(order_id);
CREATE INDEX IF NOT EXISTS idx_agent_workflows_started_at ON agent_workflows(started_at DESC);

CREATE TABLE IF NOT EXISTS agent_workflow_steps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id UUID NOT NULL REFERENCES agent_workflows(id) ON DELETE CASCADE,
    step_type VARCHAR(50) NOT NULL,
    step_name VARCHAR(100) NOT NULL,
    input_summary JSONB,
    output_summary JSONB,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_workflow_steps_workflow_id ON agent_workflow_steps(workflow_id);
CREATE INDEX IF NOT EXISTS idx_agent_workflow_steps_created_at ON agent_workflow_steps(created_at ASC);