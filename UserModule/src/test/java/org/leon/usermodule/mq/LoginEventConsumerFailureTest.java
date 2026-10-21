package org.leon.usermodule.mq;

import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.usermodule.feign.AuthModuleClient;
import org.leon.usermodule.pojo.mq.LoginEvent;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginEventConsumerFailureTest {

    private static LoginEvent event() {
        return new LoginEvent(999999L, "ghost", "trace-404",
                "127.0.0.1", 1_700_000_000_000L, "abcdef123456");
    }

    private static FeignException feignException(int status) {
        Request request = Request.create(Request.HttpMethod.GET,
                "http://localhost/auth/internal/users/999999",
                Map.of(), null, StandardCharsets.UTF_8, null);
        Response response = Response.builder()
                .status(status)
                .reason("test")
                .request(request)
                .headers(Map.of())
                .build();
        return FeignException.errorStatus("getUserProfile", response);
    }

    @DisplayName("反查 404 时立即拒绝进死信，不浪费重试")
    @Test
    void shouldRejectImmediatelyOnNotFound() {
        AuthModuleClient client = mock(AuthModuleClient.class);
        when(client.getUserProfile(999999L)).thenThrow(feignException(404));

        LoginEventConsumer consumer = new LoginEventConsumer(new RecentLoginStore(), client);

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.onLoginEvent(event()));
    }

    @DisplayName("反查 500 时原样抛出，交给重试")
    @Test
    void shouldRethrowForRetryableFailure() {
        AuthModuleClient client = mock(AuthModuleClient.class);
        when(client.getUserProfile(999999L)).thenThrow(feignException(500));

        LoginEventConsumer consumer = new LoginEventConsumer(new RecentLoginStore(), client);

        FeignException thrown = assertThrows(FeignException.class,
                () -> consumer.onLoginEvent(event()));
        assertEquals(500, thrown.status());
    }
}
