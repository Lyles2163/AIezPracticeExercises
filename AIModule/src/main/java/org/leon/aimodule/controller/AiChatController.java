package org.leon.aimodule.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiChatController {

    private final ChatClient chatClient;

    // Spring AI 自动配置注入 ChatClient.Builder
    public AiChatController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 简单对话接口
     * 示例：GET /ai/chat?message=你好，介绍一下你自己
     */
    @GetMapping("/ai/chat")
    public String chat(@RequestParam(value = "message", defaultValue = "你好") String message) {
        return chatClient.prompt()
                .user(message)      // 设置用户消息
                .call()             // 发起同步调用
                .content();         // 获取纯文本响应
    }
}