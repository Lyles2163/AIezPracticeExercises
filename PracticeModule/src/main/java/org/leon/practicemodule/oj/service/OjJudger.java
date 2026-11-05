package org.leon.practicemodule.oj.service;

import org.leon.practicemodule.oj.pojo.OjProblem;
import org.leon.practicemodule.oj.pojo.OjTestcase;
import org.leon.practicemodule.oj.sandbox.CodeSandbox;
import org.leon.practicemodule.oj.sandbox.CompileContext;
import org.leon.practicemodule.oj.sandbox.JudgeLanguage;
import org.leon.practicemodule.oj.sandbox.OjCompileException;
import org.leon.practicemodule.oj.sandbox.RunResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 判题大脑：编译一次，逐用例跑，比对输出，得出终态。
 *
 * 判题顺序是有讲究的：一旦某个用例超时就立刻 TLE 返回，不再跑后面的用例，
 * 否则一个死循环程序会把每个用例都拖到超时才结束，白等 N 倍时间。
 */
@Service
public class OjJudger {

    private final CodeSandbox sandbox;

    public OjJudger(CodeSandbox sandbox) {
        this.sandbox = sandbox;
    }

    public Verdict judge(OjProblem problem, List<OjTestcase> testcases,
                         String sourceCode, JudgeLanguage language) {
        if (testcases == null || testcases.isEmpty()) {
            return new Verdict("SE", 0, 0, 0, "题目没有配置测试用例");
        }

        CompileContext context;
        try {
            context = sandbox.compile(sourceCode, language);
        } catch (OjCompileException e) {
            return new Verdict("CE", 0, testcases.size(), 0, e.getMessage());
        }

        try {
            int passed = 0;
            long maxTime = 0;
            for (OjTestcase testcase : testcases) {
                RunResult result = sandbox.run(context, testcase.getInputData(),
                        problem.getTimeLimitMs(), problem.getMemoryLimitMb());
                maxTime = Math.max(maxTime, result.elapsedMs());

                if (result.status() == RunResult.RunStatus.TIMEOUT) {
                    return new Verdict("TLE", passed, testcases.size(), maxTime,
                            "用例 " + testcase.getSeq() + " 超时");
                }
                if (result.status() == RunResult.RunStatus.RUNTIME_ERROR) {
                    return new Verdict("RE", passed, testcases.size(), maxTime,
                            "用例 " + testcase.getSeq() + " 运行错误：" + result.stderr());
                }
                if (!sameOutput(result.stdout(), testcase.getExpectedOutput())) {
                    return new Verdict("WA", passed, testcases.size(), maxTime,
                            "用例 " + testcase.getSeq() + " 输出不正确");
                }
                passed++;
            }
            String status = passed == testcases.size() ? "AC" : "WA";
            return new Verdict(status, passed, testcases.size(), maxTime, null);
        } finally {
            sandbox.cleanup(context);
        }
    }

    /**
     * 判等口径：统一换行、去掉行尾空白与末尾空行。
     * 不做 token 级比较（那是 special judge 的事）：这版只认"看起来完全一致"，
     * 但把 CRLF 和末尾换行这几种"肉眼一样却判错"的假阴性挡掉。
     */
    static boolean sameOutput(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace("\r", "\n").stripTrailing();
    }

    /** 判题结论。status 直接就是 oj_submission.status 要写的值 */
    public record Verdict(String status, int passed, int total, long timeUsedMs, String errorMsg) {
    }
}
