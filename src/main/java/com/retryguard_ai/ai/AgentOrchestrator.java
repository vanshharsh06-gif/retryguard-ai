package com.retryguard_ai.ai;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentOrchestrator {

    private final GroqAgentProvider groqProvider;
    private final AgentToolRegistry toolRegistry;

    public AgentOrchestrator(GroqAgentProvider groqProvider, AgentToolRegistry toolRegistry) {
        this.groqProvider = groqProvider;
        this.toolRegistry = toolRegistry;
    }

    public GroqAgentProvider.AgentRunResult runWorkflow(String orderId) {
        List<GroqAgentProvider.ToolDefinition> tools = toolRegistry.getTools(orderId);
        return groqProvider.runWorkflow(orderId, tools);
    }
}