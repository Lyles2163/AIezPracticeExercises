package org.leon.practicemodule.oj.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 语言白名单的表驱动测试：加一门语言必须先在这里加一行。
 */
class JudgeLanguageTest {

    @Test
    @DisplayName("空语言按 java 处理，大小写不敏感")
    void ofNormalizes() {
        assertEquals(JudgeLanguage.JAVA, JudgeLanguage.of(null));
        assertEquals(JudgeLanguage.JAVA, JudgeLanguage.of("  "));
        assertEquals(JudgeLanguage.JAVA, JudgeLanguage.of("JAVA"));
        assertEquals(JudgeLanguage.PYTHON, JudgeLanguage.of("Python3"));
        assertEquals(JudgeLanguage.CPP, JudgeLanguage.of("cpp"));
    }

    @Test
    @DisplayName("不支持的语言必须报错，不能静默兜底成 java")
    void ofRejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> JudgeLanguage.of("rust"));
    }

    @Test
    @DisplayName("运行命令里的 {mem} 占位符在 Java 上被替换成 -Xmx")
    void runCommandFillsMemory() {
        assertEquals("java -Xmx256m Main", JudgeLanguage.JAVA.runCommand(256));
        assertEquals("./main", JudgeLanguage.CPP.runCommand(256));
        assertEquals("python3 main.py", JudgeLanguage.PYTHON.runCommand(256));
    }
}
