package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseChannelTest {

    private final List<String> frames = new CopyOnWriteArrayList<>();
    private final SseChannel.Sink sink = new SseChannel.Sink() {
        @Override
        public void send(String event, String json) {
            frames.add("event:" + event + "\ndata:" + json + "\n\n");
        }

        @Override
        public void complete() {
            frames.add("complete\n");
        }
    };
    private final SseChannel channel = new SseChannel(sink, new ObjectMapper());

    @Test
    @DisplayName("五类事件各自的名字与 JSON 形状")
    void emitsAllEventKinds() {
        channel.emit(new ChatEvent.Meta(7L, 42L, "deepseek-chat", "High", false));
        channel.emit(new ChatEvent.Delta("你好"));
        channel.emit(new ChatEvent.TokenUsage(812, 1640));
        channel.emit(new ChatEvent.ErrorEvt("UPSTREAM_MODEL_ERROR", "模型服务暂时不可用", true));
        channel.emit(new ChatEvent.Done("done"));
        assertEquals(5, frames.size());
        assertTrue(frames.get(0).startsWith("event:meta\ndata:{"));
        assertTrue(frames.get(0).contains("\"conversationId\":7"));
        assertTrue(frames.get(0).contains("\"supportsThinking\":false"));
        assertTrue(frames.get(1).contains("event:delta"));
        assertTrue(frames.get(2).contains("\"completionTokens\":1640"));
        assertTrue(frames.get(3).contains("\"retryable\":true"));
        assertTrue(frames.get(4).contains("event:done"));
    }

    @Test
    @DisplayName("正文里的换行必须被转义，绝不能提前造出空行断帧")
    void escapesNewlines() {
        channel.emit(new ChatEvent.Delta("第一行\n\n第二行"));
        String frame = frames.get(0);
        // 帧里只允许出现一次真实空行，而且必须是结尾那个帧分隔符
        assertEquals(frame.length() - 2, frame.indexOf("\n\n"), "提前出现空行会断帧: " + frame);
        assertTrue(frame.endsWith("\n\n"));
        assertTrue(frame.contains("\\n\\n"), "换行必须转义成 \\n 字面量: " + frame);
    }

    @Test
    @DisplayName("心跳帧独立于业务事件")
    void pingIsOwnFrame() {
        channel.ping();
        assertEquals("event:ping\ndata:{\"t\":100}\n\n", frames.get(0).replaceAll("\\d{2,}", "100"));
    }

    @Test
    @DisplayName("close 之后再发不产生新帧，且只 complete 一次")
    void closeIsIdempotent() {
        channel.close();
        channel.close();
        channel.emit(new ChatEvent.Delta("晚了"));
        assertEquals(1, frames.stream().filter(f -> f.equals("complete\n")).count());
        assertEquals(1, frames.size());
        assertTrue(channel.isClosed());
    }

    @Test
    @DisplayName("sink 抛异常时标记关闭并把异常透出，由调用方落库")
    void surfacesSinkFailure() {
        SseChannel broken = new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) {
                throw new IllegalStateException("断开的管道");
            }

            @Override
            public void complete() {
            }
        }, new ObjectMapper());
        assertThrows(SseChannel.ClientGoneException.class, () -> broken.emit(new ChatEvent.Delta("x")));
        assertTrue(broken.isClosed());
        // 关闭后再发既不该调用 sink，也不该再抛
        broken.emit(new ChatEvent.Delta("再晚一次"));
        assertTrue(broken.isClosed());
    }

    @Test
    @DisplayName("两个线程并发发帧不会交错出半帧")
    void concurrentEmitsStayAtomic() throws Exception {
        // 真实 SseEmitter 一帧要分两次写（event 行、data 行 + 空行），所以测试 sink 也必须分两次
        // 落笔：一次写完的假 sink 无论有没有那把锁都是原子的，用例就什么也测不到。
        List<String> chunks = new CopyOnWriteArrayList<>();
        SseChannel concurrent = new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) {
                chunks.add("event:" + event + "\n");
                Thread.yield();
                chunks.add("data:" + json + "\n\n");
            }

            @Override
            public void complete() {
            }
        }, new ObjectMapper());
        int threads = 4;
        int each = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            final int id = t;
            pool.execute(() -> {
                start.countDown();
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < each; i++) {
                    concurrent.emit(new ChatEvent.Delta("t" + id + "-i" + i));
                    if (i % 20 == 0) {
                        concurrent.ping();
                    }
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        // 每线程 each 个 delta + 每 20 个一次 ping（i=0,20..180 共 each/20 次）
        int expectedFrames = threads * (each + each / 20);
        assertEquals(expectedFrames * 2, chunks.size(), "每帧两次落笔，一次都不能丢");
        String wire = String.join("", chunks);
        List<String> frames = List.of(wire.split("\n\n", -1));
        assertEquals(expectedFrames + 1, frames.size(), "每帧一个结束空行，末尾留一个空段");
        assertTrue(frames.get(frames.size() - 1).isEmpty(), "整段输出必须以帧分隔符收尾");
        for (String frame : frames) {
            if (frame.isEmpty()) {
                continue;
            }
            // 交错会把两条 event: 行挤进同一帧，帧内就会出现第二个换行
            assertEquals(1, frame.chars().filter(c -> c == '\n').count(),
                    "帧内只允许 event 与 data 之间那个换行: " + frame);
            assertTrue(frame.startsWith("event:"), "帧必须以 event: 起头: " + frame);
        }
    }
}
