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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.Disposable;

import java.util.List;
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
    private final EffortResolver resolver;
    private final ChatModelGateway gateway;
    private final ChatStreamRegistry registry;
    private final ChatProperties props;
    private final Executor streamExecutor;

    public ChatStreamService(ChatTurnRepository repo, ConversationService conversations,
                             ContextAssembler assembler, EffortResolver resolver, ChatModelGateway gateway,
                             ChatStreamRegistry registry, ChatProperties props,
                             @Qualifier("chatStreamExecutor") Executor streamExecutor) {
        this.repo = repo;
        this.conversations = conversations;
        this.assembler = assembler;
        this.resolver = resolver;
        this.gateway = gateway;
        this.registry = registry;
        this.props = props;
        this.streamExecutor = streamExecutor;
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
        ModelParams params = resolver.resolve(modelKey, effortName);

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
        return new PreparedTurn(cid, userMessageId, assistantId, messages, params, userName, effortName);
    }

    /** 提交到流式线程池并返回登记好的流；channel 由 controller 创建。 */
    public ActiveStream subscribeAsync(PreparedTurn prepared, SseChannel channel) {
        ActiveStream stream = new ActiveStream(prepared.assistantMessageId(), prepared.conversationId(),
                prepared.userName(), channel, new AtomicReference<>(), new StringBuilder(),
                new AtomicReference<>(AiMessage.STOP_DONE), new AtomicReference<>(new int[]{-1, -1}));
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
        Disposable disposable = gateway.stream(prepared.messages(), params).subscribe(
                chunk -> onChunk(stream, chunk),
                error -> onError(stream, error),
                () -> onComplete(stream));
        stream.upstream().set(disposable);
    }

    /** 流式期间这里一次 DB 都不碰：只 append 内存 buffer。 */
    private void onChunk(ActiveStream stream, ChatModelGateway.ChatChunk chunk) {
        if (stream.channel().isClosed()) {
            return;
        }
        if (chunk.text() != null) {
            stream.append(chunk.text());
            try {
                stream.channel().emit(new ChatEvent.Delta(chunk.text()));
            } catch (SseChannel.ClientGoneException gone) {
                stream.disposeUpstream();
                truncate(stream, AiMessage.STOP_CLIENT_DISCONNECT);
            }
            return;
        }
        if (chunk.finishReason() != null) {
            stream.finishReason().set(chunk.finishReason());
        }
        if (chunk.promptTokens() != null || chunk.completionTokens() != null) {
            stream.usage().set(new int[]{
                    chunk.promptTokens() == null ? -1 : chunk.promptTokens(),
                    chunk.completionTokens() == null ? -1 : chunk.completionTokens()});
        }
    }

    private void onComplete(ActiveStream stream) {
        if (!claim(stream)) {
            stream.channel().close();
            return;
        }
        int[] usage = stream.usage().get();
        Integer promptTokens = usage[0] < 0 ? null : usage[0];
        Integer completionTokens = usage[1] < 0 ? null : usage[1];
        if (promptTokens != null || completionTokens != null) {
            emitQuietly(stream.channel(), new ChatEvent.TokenUsage(promptTokens, completionTokens));
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
        repo.finalizeFailed(stream.messageId(), stream.accumulated(), msg);
        emitQuietly(stream.channel(), new ChatEvent.ErrorEvt("UPSTREAM_MODEL_ERROR", "模型服务暂时不可用", true));
        emitQuietly(stream.channel(), new ChatEvent.Done(AiMessage.STOP_UPSTREAM));
        stream.channel().close();
    }

    private void truncate(ActiveStream stream, String stopReason) {
        if (!claim(stream)) {
            return;
        }
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
