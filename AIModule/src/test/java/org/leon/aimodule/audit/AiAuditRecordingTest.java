package org.leon.aimodule.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.provider.ProviderRoute;
import org.leon.aimodule.chat.provider.UserModelCatalogService;
import org.leon.aimodule.imagegen.config.ImageGenProperties;
import org.leon.aimodule.imagegen.gateway.ImageGateway;
import org.leon.aimodule.imagegen.minio.ImageObjectStore;
import org.leon.aimodule.imagegen.service.ImageGenService;
import org.leon.aimodule.imagegen.web.dto.ImageGenRequest;
import org.leon.loggingmodule.collector.AuditEventCollector;
import org.leon.loggingmodule.collector.SensitiveDataMasker;
import org.leon.loggingmodule.config.AuditProperties;
import org.leon.loggingmodule.model.AiCallEvent;
import org.leon.loggingmodule.model.ApiCallEvent;
import org.leon.loggingmodule.recorder.AuditRecorder;
import org.leon.loggingmodule.storage.AsyncAuditStorage;
import org.leon.loggingmodule.storage.AuditEventSink;
import org.leon.loggingmodule.storage.AuditMetrics;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiAuditRecordingTest {

    private AsyncAuditStorage storage;
    private AuditRecorder recorder;
    private List<AiCallEvent> aiEvents;

    @BeforeEach
    void setUp() {
        AuditProperties props = new AuditProperties();
        props.setFlushIntervalSeconds(60);
        aiEvents = new ArrayList<>();
        AuditEventSink sink = new AuditEventSink() {
            @Override
            public void writeApiCalls(List<ApiCallEvent> events) {
            }

            @Override
            public void writeAiCalls(List<AiCallEvent> events) {
                aiEvents.addAll(events);
            }
        };
        SensitiveDataMasker masker = new SensitiveDataMasker(props);
        AuditEventCollector collector = new AuditEventCollector(masker, "AIModuleServer");
        storage = new AsyncAuditStorage(props, sink, new AuditMetrics());
        recorder = new AuditRecorder(props, storage, masker, collector);
    }

    @AfterEach
    void tearDown() {
        storage.shutdown();
    }

    @Test
    void imageGenService_recordsDoneAndFailedAiCalls() {
        UserModelCatalogService userModels = mock(UserModelCatalogService.class);
        ImageGateway gateway = mock(ImageGateway.class);
        ImageObjectStore store = mock(ImageObjectStore.class);
        ProviderRoute route = new ProviderRoute(
                1L, 2L, "dashscope", "Wanx", "https://api.example.com", "sk-secret", "wanx-v1", false);
        when(userModels.resolveImage("leon", "u:2")).thenReturn(route);
        when(gateway.generate(any())).thenReturn(new ImageGateway.Response(
                List.of(new ImageGateway.GeneratedImage(new byte[]{1, 2, 3}, "image/png")), 420L));
        when(store.put(eq("leon"), eq("image/png"), any())).thenReturn("leon/img-1.png");

        ImageGenService service = new ImageGenService(userModels, gateway, store, new ImageGenProperties(), recorder);
        service.generate("leon", new ImageGenRequest("u:2", "a cute cat", "512x512", 1));
        storage.flushNow();

        assertThat(aiEvents).hasSize(1);
        AiCallEvent done = aiEvents.get(0);
        assertThat(done.getUserName()).isEqualTo("leon");
        assertThat(done.getServiceType()).isEqualTo(AiCallEvent.SERVICE_IMAGE);
        assertThat(done.getProvider()).isEqualTo("dashscope");
        assertThat(done.getModelKey()).isEqualTo("u:2");
        assertThat(done.getUpstreamModel()).isEqualTo("wanx-v1");
        assertThat(done.getCostMs()).isEqualTo(420L);
        assertThat(done.getStatus()).isEqualTo(AiCallEvent.STATUS_DONE);

        when(gateway.generate(any())).thenThrow(new RuntimeException("upstream failed token=secret123"));
        assertThatThrownBy(() -> service.generate("leon", new ImageGenRequest("u:2", "a dog", "512x512", 1)))
                .isInstanceOf(RuntimeException.class);
        storage.flushNow();

        assertThat(aiEvents).hasSize(2);
        AiCallEvent failed = aiEvents.get(1);
        assertThat(failed.getStatus()).isEqualTo(AiCallEvent.STATUS_FAILED);
        assertThat(failed.getErrorMsg()).contains("token=***").doesNotContain("secret123");
    }
}
