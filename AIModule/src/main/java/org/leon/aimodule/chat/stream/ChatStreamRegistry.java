package org.leon.aimodule.chat.stream;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内活跃流表。多实例部署时 stop 可能打到别的实例，
 * 那时走 ChatStreamService 的孤儿分支（状态能收敛，内容会缺）。
 */
@Component
public class ChatStreamRegistry {

    private final Map<Long, ActiveStream> active = new ConcurrentHashMap<>();

    public void register(Long messageId, ActiveStream stream) {
        active.put(messageId, stream);
    }

    public ActiveStream find(Long messageId) {
        return messageId == null ? null : active.get(messageId);
    }

    /**
     * 摘除并告知调用方"是不是你摘掉的"。收尾写入必须先抢这个claim：
     * 断连、stop 与 onComplete 可能并发，谁 remove 成功谁负责那一次 UPDATE，
     * 输的什么都不写——否则一条回答会落两次终态（后写覆盖前写）。
     */
    public boolean remove(Long messageId) {
        return messageId != null && active.remove(messageId) != null;
    }

    public int size() {
        return active.size();
    }

    public Set<Long> activeIds() {
        return Set.copyOf(active.keySet());
    }
}
