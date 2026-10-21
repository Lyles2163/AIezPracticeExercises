package org.leon.usermodule.mq;

import org.leon.usermodule.pojo.vo.ProcessedLogin;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 只保留最近 20 条，纯粹为了演示可观察，不落库。 */
@Component
public class RecentLoginStore {

    static final int CAPACITY = 20;

    private final Deque<ProcessedLogin> recent = new ArrayDeque<>();

    public synchronized void add(ProcessedLogin processed) {
        if (recent.size() == CAPACITY) {
            recent.removeLast();
        }
        recent.addFirst(processed);
    }

    public synchronized List<ProcessedLogin> recent() {
        return new ArrayList<>(recent);
    }
}
