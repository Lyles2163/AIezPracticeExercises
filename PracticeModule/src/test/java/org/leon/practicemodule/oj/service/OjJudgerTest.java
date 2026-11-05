package org.leon.practicemodule.oj.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.oj.pojo.OjProblem;
import org.leon.practicemodule.oj.pojo.OjTestcase;
import org.leon.practicemodule.oj.sandbox.CodeSandbox;
import org.leon.practicemodule.oj.sandbox.CompileContext;
import org.leon.practicemodule.oj.sandbox.JudgeLanguage;
import org.leon.practicemodule.oj.sandbox.OjCompileException;
import org.leon.practicemodule.oj.sandbox.RunResult;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判题结论的唯一定义处。用假沙箱喂固定结果，
 * 不需要 Docker 也能验证 AC/WA/TLE/RE/CE 的分支。
 */
class OjJudgerTest {

    private final OjProblem problem = problem();
    private final List<OjTestcase> cases = List.of(
            testcase(1, "1 2\n", "3"),
            testcase(2, "5 7\n", "12"));

    @Test
    @DisplayName("全部用例输出一致 -> AC")
    void allPassedIsAccepted() {
        OjJudger judger = new OjJudger(new FakeSandbox(
                RunResult.ok("3\n", 10), RunResult.ok("12\n", 20)));

        OjJudger.Verdict verdict = judger.judge(problem, cases, "code", JudgeLanguage.JAVA);

        assertEquals("AC", verdict.status());
        assertEquals(2, verdict.passed());
        assertEquals(2, verdict.total());
        assertEquals(20, verdict.timeUsedMs());
    }

    @Test
    @DisplayName("首个用例输出不一致 -> WA，且不继续跑后面的用例")
    void wrongAnswerStopsEarly() {
        FakeSandbox sandbox = new FakeSandbox(RunResult.ok("4", 5), RunResult.ok("12", 5));
        OjJudger judger = new OjJudger(sandbox);

        OjJudger.Verdict verdict = judger.judge(problem, cases, "code", JudgeLanguage.JAVA);

        assertEquals("WA", verdict.status());
        assertEquals(0, verdict.passed());
        assertEquals(1, sandbox.remainingResults(), "WA 之后不应该再调用 run");
    }

    @Test
    @DisplayName("用例超时 -> TLE")
    void timeoutIsTle() {
        OjJudger judger = new OjJudger(new FakeSandbox(RunResult.timeout(2000)));

        OjJudger.Verdict verdict = judger.judge(problem, cases, "code", JudgeLanguage.JAVA);

        assertEquals("TLE", verdict.status());
        assertEquals(2000, verdict.timeUsedMs());
    }

    @Test
    @DisplayName("用例非 0 退出 -> RE")
    void runtimeErrorIsRe() {
        OjJudger judger = new OjJudger(new FakeSandbox(
                RunResult.runtimeError("ArrayIndexOutOfBounds", 5)));

        OjJudger.Verdict verdict = judger.judge(problem, cases, "code", JudgeLanguage.JAVA);

        assertEquals("RE", verdict.status());
        assertTrue(verdict.errorMsg().contains("ArrayIndexOutOfBounds"));
    }

    @Test
    @DisplayName("编译失败 -> CE，且不运行任何用例")
    void compileFailureIsCe() {
        FakeSandbox sandbox = new FakeSandbox();
        sandbox.failCompile = true;
        OjJudger judger = new OjJudger(sandbox);

        OjJudger.Verdict verdict = judger.judge(problem, cases, "code", JudgeLanguage.JAVA);

        assertEquals("CE", verdict.status());
        assertEquals(2, verdict.total(), "CE 也要回用例总数，前端才能显示 0/2");
    }

    @Test
    @DisplayName("题目没有任何用例 -> SE，不调沙箱")
    void emptyTestcasesIsSystemError() {
        OjJudger judger = new OjJudger(new FakeSandbox());

        OjJudger.Verdict verdict = judger.judge(problem, List.of(), "code", JudgeLanguage.JAVA);

        assertEquals("SE", verdict.status());
    }

    @Test
    @DisplayName("换行风格与末尾空白不算错")
    void normalizesLineEndings() {
        OjJudger judger = new OjJudger(new FakeSandbox(
                RunResult.ok("3\r\n", 1), RunResult.ok("12\n\n", 1)));

        OjJudger.Verdict verdict = judger.judge(problem, cases, "code", JudgeLanguage.JAVA);

        assertEquals("AC", verdict.status());
        assertNull(verdict.errorMsg());
    }

    // ---------------- fixtures ----------------

    private static OjProblem problem() {
        OjProblem p = new OjProblem();
        p.setId(1L);
        p.setTimeLimitMs(2000);
        p.setMemoryLimitMb(256);
        return p;
    }

    private static OjTestcase testcase(int seq, String input, String expected) {
        OjTestcase tc = new OjTestcase();
        tc.setSeq(seq);
        tc.setInputData(input);
        tc.setExpectedOutput(expected);
        return tc;
    }

    /** 按顺序吐出预设结果；同时记录是否被调用过 */
    private static final class FakeSandbox implements CodeSandbox {

        private final Deque<RunResult> results = new ArrayDeque<>();
        private boolean failCompile;
        private boolean cleaned;

        FakeSandbox(RunResult... results) {
            this.results.addAll(List.of(results));
        }

        @Override
        public CompileContext compile(String sourceCode, JudgeLanguage language) {
            if (failCompile) {
                throw new OjCompileException("Main.java:1: error: ';' expected");
            }
            return new CompileContext(Path.of("build"), language);
        }

        @Override
        public RunResult run(CompileContext context, String input, int timeLimitMs, int memoryLimitMb) {
            return results.pollFirst();
        }

        @Override
        public void cleanup(CompileContext context) {
            cleaned = true;
        }

        int remainingResults() {
            return results.size();
        }
    }
}
