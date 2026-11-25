package org.leon.loggingmodule.aspect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leon.loggingmodule.annotation.LogBiz;
import org.leon.loggingmodule.annotation.LogIgnore;
import org.leon.loggingmodule.collector.AuditEventCollector;
import org.leon.loggingmodule.collector.SensitiveDataMasker;
import org.leon.loggingmodule.config.AuditProperties;
import org.leon.loggingmodule.model.AiCallEvent;
import org.leon.loggingmodule.model.ApiCallEvent;
import org.leon.loggingmodule.model.BizAuditEvent;
import org.leon.loggingmodule.recorder.AuditRecorder;
import org.leon.loggingmodule.storage.AsyncAuditStorage;
import org.leon.loggingmodule.storage.AuditEventSink;
import org.leon.loggingmodule.storage.AuditMetrics;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiCallAuditAspectTest {

    private AuditProperties properties;
    private AsyncAuditStorage storage;
    private List<ApiCallEvent> apiEvents;
    private List<BizAuditEvent> bizEvents;
    private SampleController controllerProxy;

    @BeforeEach
    void setUp() {
        properties = new AuditProperties();
        properties.setFlushIntervalSeconds(60);
        apiEvents = new ArrayList<>();
        bizEvents = new ArrayList<>();
        AuditEventSink sink = new AuditEventSink() {
            @Override
            public void writeApiCalls(List<ApiCallEvent> events) {
                apiEvents.addAll(events);
            }

            @Override
            public void writeAiCalls(List<AiCallEvent> events) {
            }

            @Override
            public void writeBizAudits(List<BizAuditEvent> events) {
                bizEvents.addAll(events);
            }
        };
        SensitiveDataMasker masker = new SensitiveDataMasker(properties);
        AuditEventCollector collector = new AuditEventCollector(masker, "UserModuleServer");
        storage = new AsyncAuditStorage(properties, sink, new AuditMetrics());
        AuditRecorder recorder = new AuditRecorder(properties, storage, masker, collector);

        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleController());
        factory.addAspect(new ApiCallAuditAspect(properties, collector, recorder));
        factory.addAspect(new BizLogAspect(properties, collector, recorder));
        controllerProxy = factory.getProxy();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        storage.shutdown();
    }

    @Test
    void normalControllerCall_recordsApiEventWithHeadersAndMasking() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/user/profile");
        req.setQueryString("phone=13812345678&tab=base");
        req.addHeader("X-Trace-Id", "trace-xyz");
        req.addHeader("X-User-Id", "42");
        req.addHeader("X-User-Name", "leon");
        req.addHeader("X-Internal-Token", "dev-internal-token");
        req.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req, resp));

        String result = controllerProxy.profile();
        storage.flushNow();

        assertThat(result).isEqualTo("ok");
        assertThat(apiEvents).hasSize(1);
        ApiCallEvent e = apiEvents.get(0);
        assertThat(e.getTraceId()).isEqualTo("trace-xyz");
        assertThat(e.getUserId()).isEqualTo(42L);
        assertThat(e.getUserName()).isEqualTo("leon");
        assertThat(e.getSourceService()).isEqualTo("gateway");
        assertThat(e.getTargetService()).isEqualTo("UserModuleServer");
        assertThat(e.getRequestUri()).isEqualTo("/user/profile?phone=***&tab=base");
        assertThat(e.getRemoteAddr()).isEqualTo("203.0.113.5");
        assertThat(e.getStatusCode()).isEqualTo(200);
        assertThat(e.getHandlerMethod()).contains("SampleController#profile");
    }

    @Test
    void logIgnoreAndExcludedPaths_areSkipped() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/user/ping");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req, new MockHttpServletResponse()));
        controllerProxy.ping();

        MockHttpServletRequest actuatorReq = new MockHttpServletRequest("GET", "/actuator/health");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(actuatorReq, new MockHttpServletResponse()));
        controllerProxy.profile();

        storage.flushNow();
        assertThat(apiEvents).isEmpty();
    }

    @Test
    void controllerException_recordsErrorAndRethrowsOriginal() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/user/fail");
        req.addHeader("X-User-Name", "leon");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req, new MockHttpServletResponse()));

        assertThatThrownBy(() -> controllerProxy.fail())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad password=123456");

        storage.flushNow();
        assertThat(apiEvents).hasSize(1);
        ApiCallEvent e = apiEvents.get(0);
        assertThat(e.getStatusCode()).isEqualTo(500);
        assertThat(e.getErrorMsg()).contains("password=***");
        assertThat(e.getErrorMsg()).doesNotContain("123456");
    }

    @Test
    void logBizAnnotation_recordsBizAuditEvent() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/user/submit");
        req.addHeader("X-User-Id", "9");
        req.addHeader("X-User-Name", "alice");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req, new MockHttpServletResponse()));

        controllerProxy.submitBiz();
        storage.flushNow();

        assertThat(bizEvents).hasSize(1);
        assertThat(bizEvents.get(0).getBizType()).isEqualTo("OJ_SUBMIT");
        assertThat(bizEvents.get(0).getBizDesc()).isEqualTo("提交判题");
        assertThat(bizEvents.get(0).isSuccess()).isTrue();
    }

    @RestController
    static class SampleController {
        @GetMapping("/user/profile")
        public String profile() {
            return "ok";
        }

        @LogIgnore
        @GetMapping("/user/ping")
        public String ping() {
            return "pong";
        }

        @PostMapping("/user/fail")
        public String fail() {
            throw new IllegalStateException("bad password=123456");
        }

        @LogBiz(value = "提交判题", type = "OJ_SUBMIT")
        @PostMapping("/user/submit")
        public String submitBiz() {
            return "submitted";
        }
    }
}
