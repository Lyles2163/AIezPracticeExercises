package org.leon.aimodule.chat.stream;

import java.util.List;

/**
 * 思考相的状态：切句器 + 起点 + 是否还开着。
 * 时间在这里而不在 splitter 里 —— 切句是纯文本变换，可测性优先；
 * 计时是编排语义，只有编排层知道"进入正文相"这个时刻。
 *
 * 只被 onNext 串行路径访问，不加锁（Reactor 保证 onNext 串行）。
 *
 * public 是给 chat 包外的测试夹具（TestTurns）构造用的，不是对外 API。
 */
public final class ThinkingTracker {

    private final ReasoningSentenceSplitter splitter;
    private final boolean enabled;
    private long startedAtNanos = -1L;
    private boolean open;

    public ThinkingTracker(boolean enabled, int maxStepChars, int maxSteps) {
        this.enabled = enabled;
        this.splitter = new ReasoningSentenceSplitter(maxStepChars, maxSteps);
    }

    List<String> onReasoning(String piece) {
        if (!enabled) {
            // 全局关掉或模型没这能力时，上游的 reasoning 直接丢，一帧 thought 都不该出现。
            return List.of();
        }
        if (!open) {
            open = true;
            startedAtNanos = System.nanoTime();
        }
        return splitter.feed(piece);
    }

    boolean open() {
        return open;
    }

    List<String> closeAndDrain() {
        if (!open) {
            return List.of();
        }
        open = false;
        return splitter.flush();
    }

    double elapsedSeconds() {
        if (startedAtNanos < 0) {
            return 0.0;
        }
        return Math.round((System.nanoTime() - startedAtNanos) / 100_000_000.0) / 10.0;
    }
}
