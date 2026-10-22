package org.leon.aimodule.rag.index;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.index", name = "auto-create", havingValue = "true")
public class IndexBootstrap implements ApplicationRunner {

    private final IndexRegistry registry;

    public IndexBootstrap(IndexRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            registry.ensureKbIndex();
            registry.ensureQuestionIndex();
            log.info("[RAG] 索引已就绪: {}", registry.stats());
        } catch (Exception e) {
            // 检索是增强不是依赖：ES 不可用时应用仍要能启动
            log.warn("[RAG] 启动时创建索引失败，检索功能暂不可用: {}", e.getMessage());
        }
    }
}
