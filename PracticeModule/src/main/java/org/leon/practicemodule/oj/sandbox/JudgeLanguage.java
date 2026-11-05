package org.leon.practicemodule.oj.sandbox;

import java.util.Locale;

/**
 * 每种语言的判题参数。加一门语言 = 在这里加一个枚举项，
 * 判题器、沙箱、Controller 都不用改。
 *
 * 三种语言都"先编译、再运行"：Python 用 py_compile 做语法检查，
 * 这样语法错误能判成 CE，而不是运行时才崩成 RE。
 */
public enum JudgeLanguage {

    JAVA("java", "eclipse-temurin:17-jdk", "Main.java",
            "javac -encoding UTF-8 Main.java",
            "java -Xmx{mem}m Main"),

    PYTHON("python3", "python:3.11-slim", "main.py",
            "python3 -m py_compile main.py",
            "python3 main.py"),

    CPP("cpp", "gcc:13", "main.cpp",
            "g++ -O2 -std=c++17 -o main main.cpp",
            "./main");

    private final String code;
    private final String image;
    private final String sourceFile;
    private final String compileCommand;
    private final String runCommand;

    JudgeLanguage(String code, String image, String sourceFile,
                  String compileCommand, String runCommand) {
        this.code = code;
        this.image = image;
        this.sourceFile = sourceFile;
        this.compileCommand = compileCommand;
        this.runCommand = runCommand;
    }

    public String code() {
        return code;
    }

    public String image() {
        return image;
    }

    public String sourceFile() {
        return sourceFile;
    }

    public String compileCommand() {
        return compileCommand;
    }

    /** {mem} 占位符：Java 需要 -Xmx，Python/C++ 靠 docker --memory 兜底 */
    public String runCommand(int memoryLimitMb) {
        return runCommand.replace("{mem}", String.valueOf(memoryLimitMb));
    }

    /**
     * 找不到就抛 IllegalArgumentException，调用方负责转成 40000。
     * 不用 Optional：语言白名单是硬约束，静默兜底成 java 会让用户"提交 python 却按 java 判"。
     */
    public static JudgeLanguage of(String code) {
        if (code == null || code.isBlank()) {
            return JAVA;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (JudgeLanguage language : values()) {
            if (language.code.equals(normalized)) {
                return language;
            }
        }
        throw new IllegalArgumentException("暂不支持的语言：" + code);
    }
}
