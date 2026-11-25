package org.leon.loggingmodule.collector;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.leon.loggingmodule.model.ApiCallEvent;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 审计事件收集器（设计文档 §6.4）：从 HTTP 请求上下文提取 traceId/user/ip/size 等字段，
 * 并通过 {@link SensitiveDataMasker} 脱敏后构造 {@link ApiCallEvent}。
 */
public class AuditEventCollector {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_SPAN_ID = "X-Span-Id";
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_NAME = "X-User-Name";
    public static final String HEADER_SOURCE_SERVICE = "X-Source-Service";
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    private final SensitiveDataMasker masker;
    private final String applicationName;

    public AuditEventCollector(SensitiveDataMasker masker, String applicationName) {
        this.masker = masker;
        this.applicationName = (applicationName == null || applicationName.isBlank()) ? "unknown" : applicationName;
    }

    public ApiCallEvent collectApiCall(HttpServletRequest request,
                                       HttpServletResponse response,
                                       String handlerMethod,
                                       Object returnValue,
                                       Throwable error,
                                       long costMs) {
        String traceId = resolveTraceId(request);
        String spanId = resolveSpanId(request);
        Long userId = parseLongHeader(request, HEADER_USER_ID);
        String userName = trimToNull(request != null ? request.getHeader(HEADER_USER_NAME) : null);
        String sourceService = resolveSourceService(request);
        String httpMethod = request != null ? request.getMethod() : "UNKNOWN";
        String requestUri = request != null
                ? masker.maskUri(request.getRequestURI(), request.getQueryString(), 512)
                : "";
        String remoteAddr = resolveClientIp(request);
        String userAgent = request != null
                ? SensitiveDataMasker.truncate(request.getHeader("User-Agent"), 256)
                : null;
        Integer requestSize = resolveRequestSize(request);
        Integer responseSize = resolveResponseSize(response);

        int statusCode = resolveStatusCode(response, error);
        String errorCode = resolveErrorCode(returnValue, error);
        String errorMsg = resolveErrorMsg(returnValue, error);

        return ApiCallEvent.builder()
                .traceId(traceId)
                .spanId(spanId)
                .userId(userId)
                .userName(SensitiveDataMasker.truncate(userName, 64))
                .sourceService(SensitiveDataMasker.truncate(sourceService, 64))
                .targetService(SensitiveDataMasker.truncate(applicationName, 64))
                .httpMethod(SensitiveDataMasker.truncate(httpMethod, 10))
                .requestUri(requestUri)
                .handlerMethod(SensitiveDataMasker.truncate(handlerMethod, 256))
                .remoteAddr(SensitiveDataMasker.truncate(remoteAddr, 64))
                .userAgent(userAgent)
                .requestSize(requestSize)
                .responseSize(responseSize)
                .statusCode(statusCode)
                .errorCode(SensitiveDataMasker.truncate(errorCode, 32))
                .errorMsg(errorMsg)
                .costMs(Math.max(0L, costMs))
                .createTime(LocalDateTime.now())
                .build();
    }

    public String currentTraceId() {
        HttpServletRequest req = currentRequest();
        return resolveTraceId(req);
    }

    public Long currentUserId() {
        return parseLongHeader(currentRequest(), HEADER_USER_ID);
    }

    public String currentUserName() {
        HttpServletRequest req = currentRequest();
        return trimToNull(req != null ? req.getHeader(HEADER_USER_NAME) : null);
    }

    public String getApplicationName() {
        return applicationName;
    }

    public static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    public static HttpServletResponse currentResponse() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getResponse();
        }
        return null;
    }

    private String resolveTraceId(HttpServletRequest request) {
        String header = request != null ? trimToNull(request.getHeader(HEADER_TRACE_ID)) : null;
        if (header != null) {
            return SensitiveDataMasker.truncate(header, 64);
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String resolveSpanId(HttpServletRequest request) {
        String header = request != null ? trimToNull(request.getHeader(HEADER_SPAN_ID)) : null;
        if (header != null) {
            return SensitiveDataMasker.truncate(header, 64);
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String resolveSourceService(HttpServletRequest request) {
        if (request == null) {
            return "internal";
        }
        String src = trimToNull(request.getHeader(HEADER_SOURCE_SERVICE));
        if (src != null) {
            return src;
        }
        if (trimToNull(request.getHeader(HEADER_INTERNAL_TOKEN)) != null) {
            return "gateway";
        }
        return "direct";
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String xff = trimToNull(request.getHeader("X-Forwarded-For"));
        if (xff != null) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String realIp = trimToNull(request.getHeader("X-Real-IP"));
        if (realIp != null) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    private Integer resolveRequestSize(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        long len = request.getContentLengthLong();
        if (len < 0) {
            return null;
        }
        return len > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) len;
    }

    private Integer resolveResponseSize(HttpServletResponse response) {
        if (response == null) {
            return null;
        }
        if (response instanceof ContentCachingResponseWrapper wrapper) {
            return wrapper.getContentSize();
        }
        String header = response.getHeader("Content-Length");
        if (header != null) {
            try {
                return Integer.parseInt(header.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int resolveStatusCode(HttpServletResponse response, Throwable error) {
        if (error != null) {
            if (response != null && response.getStatus() >= 400) {
                return response.getStatus();
            }
            return 500;
        }
        if (response != null && response.getStatus() > 0) {
            return response.getStatus();
        }
        return 200;
    }

    private String resolveErrorCode(Object returnValue, Throwable error) {
        if (error != null) {
            String fromError = invokeGetterAsString(error, "getErrorCode");
            if (fromError != null) {
                return fromError;
            }
            fromError = invokeGetterAsString(error, "getCode");
            if (fromError != null) {
                return fromError;
            }
            return error.getClass().getSimpleName();
        }
        if (returnValue != null) {
            String code = invokeGetterAsString(returnValue, "getCode");
            if (code != null && !"200".equals(code) && !"0".equals(code) && !"1".equals(code)) {
                return code;
            }
        }
        return null;
    }

    private String resolveErrorMsg(Object returnValue, Throwable error) {
        if (error != null) {
            String raw = error.getMessage();
            if (raw == null || raw.isBlank()) {
                raw = error.getClass().getSimpleName();
            }
            return masker.maskMessage(raw, 512);
        }
        if (returnValue != null) {
            String code = invokeGetterAsString(returnValue, "getCode");
            if (code != null && !"200".equals(code) && !"0".equals(code) && !"1".equals(code)) {
                String msg = invokeGetterAsString(returnValue, "getMsg");
                if (msg == null) {
                    msg = invokeGetterAsString(returnValue, "getMessage");
                }
                return masker.maskMessage(msg, 512);
            }
        }
        return null;
    }

    private static String invokeGetterAsString(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return v == null ? null : String.valueOf(v);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long parseLongHeader(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        String raw = trimToNull(request.getHeader(name));
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
