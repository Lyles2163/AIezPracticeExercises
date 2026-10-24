package org.leon.aimodule.chat.stream;

import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.ModelParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Locale;

@Component
public class SpringAiChatModelGateway implements ChatModelGateway {

    private final ChatClient chatClient;
    private final ChatProperties props;

    public SpringAiChatModelGateway(ChatClient.Builder builder, ChatProperties props) {
        this.chatClient = builder.build();
        this.props = props;
    }

    @Override
    public Flux<ChatChunk> stream(List<CtxMessage> messages, ModelParams params) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(params.modelKey());
        if (params.temperature() != null) {
            options.temperature(params.temperature());
        }
        if (params.maxTokens() != null) {
            options.maxTokens(params.maxTokens());
        }
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .messages(messages.stream().map(SpringAiChatModelGateway::toMessage).toList())
                .options(options.build());
        if (!props.getSystemPrompt().isBlank()) {
            spec = spec.system(props.getSystemPrompt());
        }
        return spec.stream().chatResponse().mapNotNull(SpringAiChatModelGateway::toChunk);
    }

    static Message toMessage(CtxMessage m) {
        return AiMessage.ROLE_ASSISTANT.equals(m.role())
                ? new AssistantMessage(m.content()) : new UserMessage(m.content());
    }

    /**
     * 上游一帧 → 归一化 chunk。全空时返回 null 让 mapNotNull 丢掉，
     * 但 usage-only 的最后一帧必须留住——DeepSeek 的 usage 就挂在那一帧上。
     */
    static ChatChunk toChunk(ChatResponse resp) {
        if (resp == null) {
            return null;
        }
        String text = null;
        String finish = null;
        Generation gen = resp.getResult();
        if (gen != null && gen.getOutput() != null) {
            String raw = gen.getOutput().getText();
            text = (raw == null || raw.isEmpty()) ? null : raw;
            if (gen.getMetadata() != null) {
                String fr = gen.getMetadata().getFinishReason();
                if (fr != null) {
                    // 实测 Spring AI 1.0.0-M7 会把 DeepSeek 的 "length" 交付成 "LENGTH"，
                    // 编排层按小写常量比较；不归一的话被 max_tokens 截断的回答会记成 done。
                    fr = fr.trim().toLowerCase(Locale.ROOT);
                    finish = fr.isEmpty() ? null : fr;
                }
            }
        }
        ChatResponseMetadata md = resp.getMetadata();
        Usage usage = md == null ? null : md.getUsage();
        // 实测：ChatResponse 的兜底 metadata 带 EmptyUsage，每一帧的 pt/ct 都是 0 而不是 null。
        // 真实补全的 prompt_tokens 至少为 1，所以 0 一律按"这帧没有 usage"处理，
        // 否则中间帧会带着 0/0 活下来，编排层会把它们当成 usage 帧发出去。
        Integer pt = positive(usage == null ? null : usage.getPromptTokens());
        Integer ct = positive(usage == null ? null : usage.getCompletionTokens());
        if (text == null && finish == null && pt == null && ct == null) {
            return null;
        }
        return new ChatChunk(text, finish, pt, ct);
    }

    private static Integer positive(Integer value) {
        return value == null || value <= 0 ? null : value;
    }
}
