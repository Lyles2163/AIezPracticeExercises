package org.leon.practicemodule.oj.sandbox;

/**
 * 编译失败。用异常而不是返回对象：编译成功的产物（临时目录）必须被调用方
 * 接着用并负责清理，返回值形态容易让人漏掉 cleanup。
 */
public class OjCompileException extends RuntimeException {

    public OjCompileException(String message) {
        super(message);
    }
}
