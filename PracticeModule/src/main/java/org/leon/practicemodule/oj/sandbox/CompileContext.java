package org.leon.practicemodule.oj.sandbox;

import java.nio.file.Path;

/** 一次判题的编译产物位置：临时目录 + 该次编译用的语言。 */
public record CompileContext(Path workDir, JudgeLanguage language) {
}
