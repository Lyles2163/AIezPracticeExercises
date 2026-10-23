package org.leon.practicemodule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本段第一个任务的验收锚点：PracticeModule 能否使用 CommonJwt 的统一返回体。
 * 不启动 Spring 容器——只证明依赖已进 classpath 且 Result 的码值约定成立。
 */
class ResultSmokeTest {

    @Test
    @DisplayName("success 的 code 必须是 1，前端 request.ts 以 code!==1 判错")
    void successCodeIsOne() {
        Result<String> r = Result.success("ok");
        assertEquals(1, r.getCode());
        assertEquals("ok", r.getData());
    }

    @Test
    @DisplayName("error(String) 把码写成 0，因此业务子码必须用 error(int,String)")
    void errorWithCodeKeepsSubCode() {
        Result<Void> plain = Result.error("普通失败");
        assertEquals(0, plain.getCode());

        Result<Void> sub = Result.error(40100, "用户身份不存在");
        assertEquals(40100, sub.getCode());
        assertEquals("用户身份不存在", sub.getMsg());
    }
}
