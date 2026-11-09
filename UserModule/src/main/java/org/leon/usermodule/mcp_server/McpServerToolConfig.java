package org.leon.usermodule.mcp_server;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把本模块里带 {@code @Tool} 注解的普通 Bean 暴露成 MCP 工具。
 * <p>
 * 光有 {@code @Component} 不会被 MCP Server 收进去：Spring AI 的 MCP Server 自动配置只扫描
 * 容器里的 {@link ToolCallbackProvider} 与 ToolCallback，所以必须在这里显式包一层。
 */
@Configuration
public class McpServerToolConfig {

    @Bean
    public ToolCallbackProvider orderToolCallbackProvider(OrderTools orderTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(orderTools)
                .build();
    }
}
