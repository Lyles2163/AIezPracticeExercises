package org.leon.practicemodule.oj.sandbox;

/** 单个用例的执行结果。区分"超时"和"运行错误"，因为两者判题结论不同（TLE vs RE）。 */
public record RunResult(RunStatus status, String stdout, String stderr, long elapsedMs) {

    public enum RunStatus {
        /** 正常退出（退出码 0） */
        OK,
        /** 超时被强杀 */
        TIMEOUT,
        /** 非 0 退出（异常、段错误等） */
        RUNTIME_ERROR
    }

    public static RunResult ok(String stdout, long elapsedMs) {
        return new RunResult(RunStatus.OK, stdout, "", elapsedMs);
    }

    public static RunResult timeout(long elapsedMs) {
        return new RunResult(RunStatus.TIMEOUT, "", "", elapsedMs);
    }

    public static RunResult runtimeError(String stderr, long elapsedMs) {
        return new RunResult(RunStatus.RUNTIME_ERROR, "", stderr, elapsedMs);
    }
}
