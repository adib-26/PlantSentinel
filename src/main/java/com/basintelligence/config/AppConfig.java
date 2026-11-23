package com.basintelligence.config;

import java.time.Clock;

import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import com.basintelligence.agent.AgentAuditService;
import com.basintelligence.agent.AgentToolPolicy;
import com.basintelligence.agent.AgentToolset;
import com.basintelligence.agent.McpToolExposure;
import com.basintelligence.agent.InvestigationPrompt;
import com.basintelligence.agent.AuditingToolCallback;
import com.basintelligence.agent.BasMcpTools;
import com.basintelligence.common.ActivityTracker;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Publishes the @Tool methods as MCP tools. Kiro connects to /mcp and uses them as its toolbox. */
    /**
     * Step 1, ordinary tool calling: the agent's @Tool methods as Spring AI tool callbacks, each wrapped so the policy,
     * timeout and audit apply. Startup fails if a tool is not on the allowlist.
     */
    @Bean
    AgentToolset agentToolset(BasMcpTools tools, AgentAuditService audit, ActivityTracker activity, AgentToolPolicy policy,
            org.springframework.transaction.PlatformTransactionManager transactions,
            io.micrometer.core.instrument.MeterRegistry meters) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();
        policy.verifyPublished(Arrays.stream(callbacks).map(c -> c.getToolDefinition().name())
                .collect(java.util.stream.Collectors.toSet()));
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        return new AgentToolset(Arrays.stream(callbacks)
                .map(c -> (ToolCallback) new AuditingToolCallback(c, audit, activity, policy, executor, transactions, meters))
                .toList());
    }

    /** Step 2, MCP: the same callbacks published to MCP clients under namespaced names with behaviour hints. */
    @Bean
    List<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification> mcpToolSpecifications(AgentToolset toolset) {
        return McpToolExposure.specifications(toolset);
    }

    /** MCP prompt that encodes the investigation workflow, so any MCP client follows the same steps. */
    @Bean
    List<io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification> basPrompts() {
        return List.of(InvestigationPrompt.specification());
    }

    @Bean
    OpenAPI basOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PlantSentinel API")
                        .version("0.1.0")
                        .description("Industrial monitoring, predictive maintenance and diagnostics. "
                                + "Edge endpoints use the X-API-Key header; other endpoints use HTTP Basic."))
                .components(new Components()
                        .addSecuritySchemes("basic", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic"))
                        .addSecuritySchemes("edgeKey", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-API-Key")))
                .addSecurityItem(new SecurityRequirement().addList("basic"));
    }
}
