package org.leon.aimodule.chat.web;

import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.ModelParams;
import org.leon.aimodule.chat.stream.ActiveStream;
import org.leon.aimodule.chat.stream.PreparedTurn;
import org.leon.aimodule.chat.stream.ThinkingTracker;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

final class TestTurns {

    private TestTurns() {
    }

    static PreparedTurn prepared() {
        return new PreparedTurn(7L, 41L, 42L, List.of(new CtxMessage("user", "hi")),
                new ModelParams("deepseek-chat", 1.0, 4096, false), "alice", "High");
    }

    static ActiveStream activeStream() {
        return new ActiveStream(42L, 7L, "alice", null, new AtomicReference<>(), new StringBuilder(),
                new AtomicReference<>("done"), new AtomicReference<>(new int[]{-1, -1, -1}),
                new ThinkingTracker(true, 120, 12));
    }
}
