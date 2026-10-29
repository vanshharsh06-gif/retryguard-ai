package com.retryguard_ai.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "retryguard.ai.agent.enabled=true",
                    "retryguard.ai.agent.scheduler-enabled=false",
                    "retryguard.ai.agent.stuck-order-minutes=30",
                    "retryguard.ai.agent.max-tool-calls=4",
                    "retryguard.ai.agent.max-runs-per-scan=10",
                    "retryguard.ai.agent.timeout-seconds=15",
                    "retryguard.ai.agent.groq.api-key=test-key",
                    "retryguard.ai.agent.groq.model=llama-3.1-70b-versatile",
                    "retryguard.ai.agent.groq.base-url=https://api.groq.com/openai/v1"
            );

    @EnableConfigurationProperties(AgentProperties.class)
    static class TestConfig {}

    @Test
    void bindsAllPropertiesCorrectly() {
        contextRunner.run(context -> {
            AgentProperties properties = context.getBean(AgentProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.schedulerEnabled()).isFalse();
            assertThat(properties.stuckOrderMinutes()).isEqualTo(30);
            assertThat(properties.maxToolCalls()).isEqualTo(4);
            assertThat(properties.maxRunsPerScan()).isEqualTo(10);
            assertThat(properties.timeoutSeconds()).isEqualTo(15);
            assertThat(properties.groq().apiKey()).isEqualTo("test-key");
            assertThat(properties.groq().model()).isEqualTo("llama-3.1-70b-versatile");
            assertThat(properties.groq().baseUrl()).isEqualTo("https://api.groq.com/openai/v1");
            assertThat(properties.groq().isConfigured()).isTrue();
        });
    }

    @Test
    void defaultsToDisabledAndSafeValues() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(context -> {
                    AgentProperties properties = context.getBean(AgentProperties.class);
                    assertThat(properties.enabled()).isFalse();
                    assertThat(properties.schedulerEnabled()).isFalse();
                    assertThat(properties.stuckOrderMinutes()).isEqualTo(30);
                    assertThat(properties.maxToolCalls()).isEqualTo(4);
                    assertThat(properties.maxRunsPerScan()).isEqualTo(10);
                    assertThat(properties.timeoutSeconds()).isEqualTo(15);
                    assertThat(properties.groq().isConfigured()).isFalse();
                });
    }

    @Test
    void groqIsConfiguredReturnsFalseWhenApiKeyBlank() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues("retryguard.ai.agent.groq.api-key=  ")
                .run(context -> {
                    AgentProperties properties = context.getBean(AgentProperties.class);
                    assertThat(properties.groq().isConfigured()).isFalse();
                });
    }
}