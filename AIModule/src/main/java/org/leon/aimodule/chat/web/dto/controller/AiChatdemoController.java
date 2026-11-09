package org.leon.aimodule.chat.web.dto.controller;

import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.demo.OrderTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
@Slf4j
@RestController
public class AiChatdemoController {

    private final ChatClient chatClient;

    // 工具来源：有 MCP 客户端就用 MCP 的，没有就退化成进程内的本地 OrderTools。
    // ToolCallbackProvider 强注入会让 MCP 一失效整个应用都起不来，所以走 ObjectProvider 可选注入。
    public AiChatdemoController(ChatClient.Builder builder, OrderTools orderTools,
                                ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider) {
        ToolCallbackProvider mcpTools = mcpToolCallbackProvider.getIfAvailable();
        if (mcpTools != null) {
            builder.defaultTools(mcpTools);
        } else {
            log.warn("没有可用的 MCP 客户端，/agent 退化为只使用本地 OrderTools");
            builder.defaultTools(orderTools);
        }
        this.chatClient = builder.build();
    }



    // 先定义记录类
    public record BookInfo(String title, String author, double price, List<String> tags) {}

    @GetMapping("/book")
    public BookInfo book(@RequestParam String bookName) {
        return chatClient.prompt()
                .user("推荐一本关于" + bookName + "的经典书，返回书名、作者、大致价格和3个标签")
                .call()
                .entity(BookInfo.class);
    }


    public record StudentInfo(String name, String context) {}

    @GetMapping("/student")
    public StudentInfo student(@RequestParam String name){
        return chatClient.prompt()
                .user("请返回这个人的姓名：" + name+"和它的介绍")
                .call()
                .entity(StudentInfo.class);
    }


    @GetMapping("/agent")
    public String agent(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

    /**
     * 简单对话接口（不接入 RAG 知识库）。
     * <p>
     * 本接口直接调用大模型，不带知识库检索，适合快速连通性测试。
     * 需要基于上传文档的问答，请使用带 RAG 的流式接口：
     * {@code POST /ai/chat/completions/stream}
     *
     * @param message 用户问题
     * @return 模型纯文本回答
     */
    @GetMapping("/ai/chat")
    public String chat(@RequestParam(value = "message", defaultValue = "你好,你是什么模型") String message) {
        return chatClient.prompt()
                .user(message)      // 设置用户消息
                .call()             // 发起同步调用
                .content();         // 获取纯文本响应
    }
}