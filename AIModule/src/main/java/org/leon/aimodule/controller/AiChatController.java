package org.leon.aimodule.controller;

import org.leon.aimodule.demo.OrderTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiChatController {

    private final ChatClient chatClient;
    // ChatController.java 新增
    private final OrderTools orderTools;

    public AiChatController(ChatClient.Builder builder, OrderTools orderTools) {
        this.orderTools = orderTools;
        this.chatClient = builder
                .defaultTools(orderTools)  // 👈 注册工具
                .build();
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