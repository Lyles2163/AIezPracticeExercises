package org.leon.practicemodule.oj.sandbox;

/**
 * 执行用户代码的隔离层。当前唯一实现是 DockerSandbox；
 * 抽成接口是为了以后换 nsjail / 远程判题机时，判题器不用动。
 */
public interface CodeSandbox {

    /**
     * 编译源码。成功返回带临时目录的上下文，失败抛 {@link OjCompileException}。
     *
     * @throws OjCompileException 编译失败或编译阶段基础设施异常
     */
    CompileContext compile(String sourceCode, JudgeLanguage language);

    /** 在隔离环境里跑一个用例。返回超时/运行错误/正常三种结果之一 */
    RunResult run(CompileContext context, String input, int timeLimitMs, int memoryLimitMb);

    /** 释放编译上下文占用的临时文件 */
    void cleanup(CompileContext context);
}
