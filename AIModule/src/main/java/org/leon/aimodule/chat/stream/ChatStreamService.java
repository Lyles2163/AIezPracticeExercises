package org.leon.aimodule.chat.stream;

import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiConversation;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.repo.ChatTurnRepository;
import org.leon.aimodule.chat.service.ContextAssembler;
import org.leon.aimodule.chat.service.ConversationService;
import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.EffortResolver;
import org.leon.aimodule.chat.service.ModelParams;
import org.leon.aimodule.chat.service.ModelResolver;
import org.leon.aimodule.rag.qa.RagContextService;
import org.leon.aimodule.tool.ChatTool;
import org.leon.aimodule.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一轮对话的编排。三段式：
 * T1 = prepare（建流之前的全部 DB 动作，任何拒绝都在这里抛出）；
 * 流式期间零写入（chunk 回调只碰内存）；
 * T3 = 收尾（一次 UPDATE 写完内容、状态、stop_reason 与 usage）。
 */
@Slf4j
@Service
public class ChatStreamService {

    static final String BUSY = "上一条回答仍在生成";
    static final String BUSY_START = "服务繁忙，请稍后重试";
    static final String NOT_FOUND = "会话不存在";
    static final String NO_QUESTION = "没有可重新生成的问题";
    static final String EMPTY_CONTENT = "content 不能为空";

    private final ChatTurnRepository repo;
    private final ConversationService conversations;
    private final ContextAssembler assembler;
    private final ModelResolver modelResolver;
    private final ChatModelGateway gateway;
    private final ChatStreamRegistry registry;
    private final ChatProperties props;
    private final Executor streamExecutor;
    private final RagContextService ragContextService;
    private final ToolRegistry toolRegistry;

    public ChatStreamService(ChatTurnRepository repo, ConversationService conversations,
                             ContextAssembler assembler, ModelResolver modelResolver, ChatModelGateway gateway,
                             ChatStreamRegistry registry, ChatProperties props,
                             @Qualifier("chatStreamExecutor") Executor streamExecutor,
                             RagContextService ragContextService, ToolRegistry toolRegistry) {
        this.repo = repo;
        this.conversations = conversations;
        this.assembler = assembler;
        this.modelResolver = modelResolver;
        this.gateway = gateway;
        this.registry = registry;
        this.props = props;
        this.streamExecutor = streamExecutor;
        this.ragContextService = ragContextService;
        this.toolRegistry = toolRegistry;
    }

    /**
     * T1：建流之前的全部数据库动作。任何拒绝都在这里抛出。
     * 整个 T1 必须是一个事务——supersede 与插新占位是一体的，
     * 中途失败只留下"旧答案已作废、新答案没建起来"的空会话（实测踩过）。
     */
    @Transactional
    public PreparedTurn prepare(StreamCommand cmd, String userName) {
        AiConversation conversation = conversations.requireOwned(userName, cmd.conversationId());
        Long cid = conversation.getId();
        int stale = props.getStaleStreamReapMinutes();
        repo.reapStale(cid, stale);
        if (repo.countLive(cid, stale) > 0) {
            throw new ChatRejectException(BUSY);
        }
        // 档位只有一个真相源：先算出要落库的 effortName，再把它喂给 resolver，
        // 否则会出现"按 Medium 的参数生成、按 High 落库"这种两条回落链分叉。
        String modelKey = firstNonBlank(cmd.modelKey(), conversation.getModelKey());
        String effortName = firstNonBlank(cmd.effort(), conversation.getEffort(), props.getDefaultEffort());
        ModelResolver.Resolved resolved = modelResolver.resolve(userName, modelKey, effortName, cmd.thinking());
        ModelParams params = resolved.params();
        Long userMessageId;
        String question;
        boolean regenerate = cmd.regenerate();
        AiMessage existing = repo.findByClientMsgId(cid, cmd.clientMsgId());
        if (existing != null) {
            userMessageId = existing.getId();
            question = existing.getContent();
            regenerate = true;
        } else if (regenerate) {
            AiMessage last = null;
            for (AiMessage m : repo.activeHistory(cid, null)) {
                if (AiMessage.ROLE_USER.equals(m.getRole())) {
                    last = m;
                }
            }
            if (last == null) {
                throw new ChatRejectException(NO_QUESTION);
            }
            userMessageId = last.getId();
            question = last.getContent();
        } else {
            if (cmd.content() == null || cmd.content().isBlank()) {
                throw new ChatRejectException(EMPTY_CONTENT);
            }
            question = cmd.content();
            userMessageId = repo.insertUser(cid, question, cmd.clientMsgId());
            repo.touch(cid, userMessageId, question, 1);
        }
        if (regenerate) {
            repo.supersede(userMessageId);
        }
        // 幂等键只保护用户消息。重新生成必须让助手行不带客户端键：
        // 派生的 "xxx:a" 在同一会话里是固定值，第二次重新生成会撞 uk_conv_client
        // （实测 Duplicate entry '6-rename-probe-2:a'）。助手行本来就是用 id 寻址的（#6/stop）。
        String assistantClientMsgId = regenerate ? null : derivedAssistantKey(cmd.clientMsgId());
        Long assistantId = repo.insertAssistant(cid, userMessageId, params.modelKey(),
                effortName, assistantClientMsgId);
        repo.touch(cid, assistantId, question, regenerate ? 0 : 1);
        if (!regenerate) {
            repo.renameIfDefault(cid, question);
        }
        List<CtxMessage> messages = assembler.assemble(repo.activeHistory(cid, userMessageId), question);
        return new PreparedTurn(cid, userMessageId, assistantId, messages, params, userName, effortName,
                cmd.useRag(), cmd.toRagOptions(), cmd.systemPromptOverride(), resolved.route());
    }

    /** 提交到流式线程池并返回登记好的流；channel 由 controller 创建。 */
    public ActiveStream subscribeAsync(PreparedTurn prepared, SseChannel channel) {
        ActiveStream stream = new ActiveStream(prepared.assistantMessageId(), prepared.conversationId(),
                prepared.userName(), channel, new AtomicReference<>(), new StringBuilder(),
                new AtomicReference<>(AiMessage.STOP_DONE), new AtomicReference<>(new int[]{-1, -1, -1}),
                // 开关只问 Task 13 收敛出来的 params.supportsThinking()（= 全局开关 ∧ 模型能力），
                // 这里不许再判第二次，否则"报 true 却发不出内容"的空头旗子会重演。
                new ThinkingTracker(prepared.params().supportsThinking(),
                        props.getThinkingMaxStepChars(), props.getThinkingMaxSteps()));
        // 必须先登记再提交任务：秒回的流会在任务跑完时自己摘掉自己，
        // 登记若放在订阅之后，那条已完成的流会被重新塞回注册表成为停不下来的僵尸。
        registry.register(stream.messageId(), stream);
        try {
            streamExecutor.execute(() -> run(stream, prepared));
        } catch (RejectedExecutionException e) {
            if (registry.remove(stream.messageId())) {
                repo.markFailed(prepared.assistantMessageId(), "服务繁忙，未开始生成");
            }
            channel.close();
            throw new ChatRejectException(BUSY_START);
        }
        return stream;
    }

    public void stop(Long messageId, String userName) {
        ActiveStream stream = registry.find(messageId);
        if (stream != null) {
            if (!stream.userName().equals(userName)) {
                throw new ChatRejectException(NOT_FOUND);
            }
            stream.disposeUpstream();
            truncate(stream, AiMessage.STOP_STOPPED);
            return;
        }
        // 孤儿分支：多实例部署时 stop 会打到没有这条流的实例，按 DB 状态收敛
        AiMessage row = repo.findById(messageId);
        if (row == null) {
            throw new ChatRejectException(NOT_FOUND);
        }
        conversations.requireOwned(userName, row.getConversationId());
        if (!AiMessage.ST_STREAMING.equals(row.getStatus())) {
            return;
        }
        repo.finalizeAssistant(row.getId(), row.getContent() == null ? "" : row.getContent(),
                AiMessage.ST_TRUNCATED, AiMessage.STOP_STOPPED, null, null);
    }

    /** emitter 超时：Spring 回调进来，按 timeout 收尾。 */
    public void timeout(Long messageId) {
        truncateIfActive(messageId, AiMessage.STOP_TIMEOUT);
    }

    /**
     * 客户端断连 / onCompletion 回调。正常 done 之后 onCompletion 也会被调用，
     * 那时流早就不在注册表里了，这里必须是空操作——否则会把 done 改写成 truncated。
     */
    public void clientGone(Long messageId) {
        truncateIfActive(messageId, AiMessage.STOP_CLIENT_DISCONNECT);
    }

    private void truncateIfActive(Long messageId, String stopReason) {
        ActiveStream stream = registry.find(messageId);
        if (stream == null) {
            return;
        }
        stream.disposeUpstream();
        truncate(stream, stopReason);
    }

    private void run(ActiveStream stream, PreparedTurn prepared) {
        ModelParams params = prepared.params();
        try {
            stream.channel().emit(new ChatEvent.Meta(prepared.conversationId(), stream.messageId(),
                    params.modelKey(), prepared.effortName(), params.supportsThinking()));
        } catch (SseChannel.ClientGoneException gone) {
            truncate(stream, AiMessage.STOP_CLIENT_DISCONNECT);
            return;
        }

        // ===== RAG 检索 =====
        String ragPrompt = null;
        if (prepared.useRag()) {
            try {
                String question = prepared.messages().isEmpty() ? ""
                        : prepared.messages().get(prepared.messages().size() - 1).content();
                RagContextService.RagContext ctx = ragContextService.retrieve(
                        question, prepared.userName(), prepared.ragOptions());
                if (!ctx.empty()) {
                    emitQuietly(stream.channel(), new ChatEvent.Sources(ctx.citations()));
                    ragPrompt = buildRagSystemPrompt(props.getSystemPrompt(), ctx.context());
                }
            } catch (Exception e) {
                // 检索失败不阻断对话：降级为普通对话，仅打日志。
                log.warn("[chat] RAG 检索失败，降级为普通对话: {}", e.getMessage());
            }
        }
        // 资料块在前、请求携带的人设在后，顺序是定死的：反过来会让角色口吻盖过
        // 「必须基于资料回答」，模型开始用女仆语气编造出处。两段都在就拼成一段，不做二选一。
        String systemPromptOverride = joinSystemPrompt(ragPrompt, prepared.systemPromptOverride());

        // 个人供应商的端点是否支持工具协议无法预判，route 一旦存在就整轮不带工具，
        // 否则不兼容的端点会直接 400，用户只看到"模型服务不可用"。
        boolean toolsAllowed = prepared.route() == null || prepared.route().supportsTools();
        List<ChatTool> tools = toolRegistry.isEmpty() || !toolsAllowed ? null : toolRegistry.all();
        streamLoop(stream, prepared, params, systemPromptOverride, tools, new ArrayList<>(prepared.messages()), 0);
    }

    /**
     * 模型请求的主循环（响应式，保留 Disposable 供 stop 取消）。
     *
     * 普通情况：模型直接回文本，流式吐给前端，收尾。
     * 工具调用：模型 finish_reason=tool_calls，把 assistant 的 tool_calls 消息和工具执行结果
     * 追加到上下文，再请求一次模型；递归直到模型回文本或达到最大轮次。
     *
     * 每次请求都把 Disposable 存进 stream.upstream()，stop 时 dispose 即可中断。
     */
    private void streamLoop(ActiveStream stream, PreparedTurn prepared, ModelParams params,
                            String systemPromptOverride, List<ChatTool> tools,
                            List<CtxMessage> context, int round) {
        final int maxToolRounds = 5;
        boolean lastRound = round >= maxToolRounds;
        List<ChatModelGateway.ChatChunk> chunks = new ArrayList<>();
        // 只有个人模型才走 route 重载；内置模型仍走 4 参重载，避免测试替身被多一个默认方法打穿。
        Disposable disposable = (prepared.route() == null
                ? gateway.stream(context, params, systemPromptOverride, lastRound ? null : tools)
                : gateway.stream(context, params, systemPromptOverride, lastRound ? null : tools,
                        prepared.route()))
                .subscribe(
                        chunk -> {
                            chunks.add(chunk);
                            // 只在"确定是文本响应"时才流式吐前端；工具调用轮先攒着
                            if (!isToolCallRound(chunks)) {
                                onChunk(stream, chunk);
                            }
                        },
                        error -> onError(stream, error),
                        () -> {
                            String finish = finishOf(chunks);
                            if ("tool_calls".equals(finish) && !lastRound) {
                                List<CollectedToolCall> calls = collectToolCalls(chunks);
                                if (calls.isEmpty()) {
                                    // 模型说要调工具但没给详情：把已攒的 chunk 吐出去收尾
                                    for (ChatModelGateway.ChatChunk c : chunks) {
                                        onChunk(stream, c);
                                    }
                                    onComplete(stream);
                                    return;
                                }
                                context.add(new CtxMessage(AiMessage.ROLE_ASSISTANT, textOf(chunks),
                                        calls.stream().map(c -> new CtxMessage.ToolCallRef(c.id, c.name, c.arguments)).toList(),
                                        null));
                                for (CollectedToolCall call : calls) {
                                    String result = executeTool(call.name, call.arguments, prepared.userName());
                                    context.add(new CtxMessage("tool", result, null, call.id));
                                }
                                streamLoop(stream, prepared, params, systemPromptOverride, tools, context, round + 1);
                            } else {
                                // 文本响应：chunk 在 onNext 已逐帧吐出，直接收尾
                                onComplete(stream);
                            }
                        });
        stream.upstream().set(disposable);
    }

    /**
     * 判断当前攒到的 chunk 是否是工具调用轮。
     * 只要出现过非空的 toolCalls delta，就认为这轮是工具调用，不再往前端吐文本
     * （工具调用轮的 content 通常为空，吐了也是噪音）。
     */
    private static boolean isToolCallRound(List<ChatModelGateway.ChatChunk> chunks) {
        for (ChatModelGateway.ChatChunk c : chunks) {
            if (c.toolCalls() != null && !c.toolCalls().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 从收集到的 chunks 里取出 finish_reason（最后一个非空的）。 */
    private static String finishOf(List<ChatModelGateway.ChatChunk> chunks) {
        for (int i = chunks.size() - 1; i >= 0; i--) {
            if (chunks.get(i).finishReason() != null) {
                return chunks.get(i).finishReason();
            }
        }
        return null;
    }

    /** 拼接所有 text chunk 的内容。 */
    private static String textOf(List<ChatModelGateway.ChatChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (ChatModelGateway.ChatChunk c : chunks) {
            if (c.text() != null) {
                sb.append(c.text());
            }
        }
        return sb.toString();
    }

    /** 把流式的 tool_calls 增量按 index 拼成完整调用。 */
    private static List<CollectedToolCall> collectToolCalls(List<ChatModelGateway.ChatChunk> chunks) {
        Map<Integer, CollectedToolCall> byIndex = new LinkedHashMap<>();
        for (ChatModelGateway.ChatChunk c : chunks) {
            if (c.toolCalls() == null) {
                continue;
            }
            for (ChatModelGateway.ToolCallDelta d : c.toolCalls()) {
                int idx = d.index() == null ? 0 : d.index();
                CollectedToolCall call = byIndex.computeIfAbsent(idx, k -> new CollectedToolCall());
                if (d.id() != null) {
                    call.id = d.id();
                }
                if (d.name() != null) {
                    call.name = d.name();
                }
                if (d.arguments() != null) {
                    call.arguments = (call.arguments == null ? "" : call.arguments) + d.arguments();
                }
            }
        }
        return new ArrayList<>(byIndex.values());
    }

    /** 执行一个工具；找不到工具时返回错误说明字符串，让模型自己决定怎么圆场。 */
    private String executeTool(String name, String arguments, String userName) {
        ChatTool tool = toolRegistry.find(name);
        if (tool == null) {
            log.warn("[chat] 模型调用了未注册的工具: {}", name);
            return "{\"ok\":false,\"message\":\"未知工具: " + name + "\"}";
        }
        try {
            return tool.execute(arguments, userName);
        } catch (Exception e) {
            log.warn("[chat] 工具执行异常 tool={} err={}", name, e.getMessage());
            return "{\"ok\":false,\"message\":\"工具执行失败: " + e.getMessage() + "\"}";
        }
    }

    /** 一次工具调用的拼装结果。 */
    private static final class CollectedToolCall {
        String id;
        String name;
        String arguments;
    }

    /**
     * 把 RAG 检索上下文拼到 system prompt 前面。
     * 若配置里没有 system prompt，就只给 RAG 指令；若有，则在原有指令前追加资料块。
     */
    private String buildRagSystemPrompt(String baseSystemPrompt, String ragContext) {
        String instruction = "你是一个答疑助手。下面给出了参考资料，你必须优先基于这些资料回答用户的问题，"
                + "不要声称自己没有收到资料。引用资料时在句末标注对应的 [n] 编号。"
                + "如果参考资料中确实没有相关信息，再说明无法回答，不要编造。\n\n"
                + ragContext;
        if (baseSystemPrompt == null || baseSystemPrompt.isBlank()) {
            return instruction;
        }
        return instruction + "\n\n" + baseSystemPrompt;
    }

    /** 资料块与人设的拼接：任一为空就返回另一个，都为空返回 null（= 不覆盖，走默认 system）。 */
    private static String joinSystemPrompt(String ragBlock, String persona) {
        boolean hasRag = ragBlock != null && !ragBlock.isBlank();
        boolean hasPersona = persona != null && !persona.isBlank();
        if (hasRag && hasPersona) {
            return ragBlock + "\n\n" + persona;
        }
        return hasRag ? ragBlock : (hasPersona ? persona : null);
    }

    /** 流式期间这里一次 DB 都不碰：只 append 内存 buffer，reasoning 连 buffer 都不进。 */
    private void onChunk(ActiveStream stream, ChatModelGateway.ChatChunk chunk) {
        if (stream.channel().isClosed()) {
            return;
        }
        if (chunk.reasoning() != null) {
            for (String step : stream.thinking().onReasoning(chunk.reasoning())) {
                emitOrDisconnect(stream, new ChatEvent.ThoughtStep(step));
            }
            return;
        }
        if (chunk.text() != null) {
            closeThinking(stream);
            stream.append(chunk.text());
            emitOrDisconnect(stream, new ChatEvent.Delta(chunk.text()));
            return;
        }
        if (chunk.finishReason() != null) {
            stream.finishReason().set(chunk.finishReason());
        }
        if (chunk.promptTokens() != null || chunk.completionTokens() != null
                || chunk.reasoningTokens() != null) {
            stream.usage().set(new int[]{
                    chunk.promptTokens() == null ? -1 : chunk.promptTokens(),
                    chunk.completionTokens() == null ? -1 : chunk.completionTokens(),
                    chunk.reasoningTokens() == null ? -1 : chunk.reasoningTokens()});
        }
    }

    /** 进入正文相：把残留短句发完，再发 thought_end。没开过思考相就什么都不发。 */
    private void closeThinking(ActiveStream stream) {
        if (!stream.thinking().open()) {
            return;
        }
        double seconds = stream.thinking().elapsedSeconds();
        for (String step : stream.thinking().closeAndDrain()) {
            emitOrDisconnect(stream, new ChatEvent.ThoughtStep(step));
        }
        emitQuietly(stream.channel(), new ChatEvent.ThoughtEnd(seconds));
    }

    /** 上游 error 相：短句要发完，但 thought_end 不发（前端据此把思考线标"思考中断"）。 */
    private void drainThinking(ActiveStream stream) {
        for (String step : stream.thinking().closeAndDrain()) {
            emitQuietly(stream.channel(), new ChatEvent.ThoughtStep(step));
        }
    }

    private void emitOrDisconnect(ActiveStream stream, ChatEvent event) {
        try {
            stream.channel().emit(event);
        } catch (SseChannel.ClientGoneException gone) {
            stream.disposeUpstream();
            truncate(stream, AiMessage.STOP_CLIENT_DISCONNECT);
        }
    }

    private void onComplete(ActiveStream stream) {
        if (!claim(stream)) {
            stream.channel().close();
            return;
        }
        // 覆盖"全程只有推理、一帧正文都没有"的流
        closeThinking(stream);
        int[] usage = stream.usage().get();
        Integer promptTokens = usage[0] < 0 ? null : usage[0];
        Integer completionTokens = usage[1] < 0 ? null : usage[1];
        Integer reasoningTokens = usage[2] < 0 ? null : usage[2];
        if (promptTokens != null || completionTokens != null) {
            emitQuietly(stream.channel(),
                    new ChatEvent.TokenUsage(promptTokens, completionTokens, reasoningTokens));
        }
        String reason = mapFinishReason(stream.finishReason().get());
        repo.finalizeAssistant(stream.messageId(), stream.accumulated(), AiMessage.ST_DONE, reason,
                promptTokens, completionTokens);
        emitQuietly(stream.channel(), new ChatEvent.Done(reason));
        stream.channel().close();
    }

    private void onError(ActiveStream stream, Throwable error) {
        String msg = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        log.warn("[chat] 上游失败 messageId={} err={}", stream.messageId(), msg);
        if (!claim(stream)) {
            stream.channel().close();
            return;
        }
        drainThinking(stream);
        repo.finalizeFailed(stream.messageId(), stream.accumulated(), msg);
        emitQuietly(stream.channel(), new ChatEvent.ErrorEvt("UPSTREAM_MODEL_ERROR",
                "模型服务暂时不可用", retryable(error)));
        emitQuietly(stream.channel(), new ChatEvent.Done(AiMessage.STOP_UPSTREAM));
        stream.channel().close();
    }

    private void truncate(ActiveStream stream, String stopReason) {
        if (!claim(stream)) {
            return;
        }
        // stop / 断连 / 超时都不补发 thought_end：流已断，补发只是多一次无意义往返（spec §4.6 第四行）。
        repo.finalizeAssistant(stream.messageId(), stream.accumulated(), AiMessage.ST_TRUNCATED,
                stopReason, null, null);
        stream.channel().close();
    }

    /**
     * 收尾的唯一门票：注册表里摘掉自己的那个人才准写终态。
     * 断连、stop 与 onComplete 可能并发，没有这道 claim 一条回答会落两次终态
     * （后写的 done 覆盖先写的 truncated），mapper 层的 status='streaming' 只是第二道保险。
     */
    private boolean claim(ActiveStream stream) {
        return registry.remove(stream.messageId());
    }

    /** 401/403 是"重试也没用"，其余（408/429/5xx/IO/超时/解析失败）才值得重试。 */
    private static boolean retryable(Throwable error) {
        if (error instanceof UpstreamStatusException status) {
            int code = status.status();
            return code != 401 && code != 403 && (code == 408 || code == 429 || code >= 500);
        }
        return true;
    }

    private void emitQuietly(SseChannel channel, ChatEvent event) {
        try {
            channel.emit(event);
        } catch (SseChannel.ClientGoneException ignored) {
            // 客户端已经不在了，落库已完成，无需再处理
        }
    }

    private static String mapFinishReason(String upstream) {
        if (upstream == null) {
            return AiMessage.STOP_DONE;
        }
        return switch (upstream) {
            case "length" -> AiMessage.STOP_LENGTH;
            default -> AiMessage.STOP_DONE;
        };
    }

    private static String derivedAssistantKey(String clientMsgId) {
        return clientMsgId == null || clientMsgId.isBlank() ? null : clientMsgId.trim() + ":a";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
