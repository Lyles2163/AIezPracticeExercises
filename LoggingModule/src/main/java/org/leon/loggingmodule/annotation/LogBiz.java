package org.leon.loggingmodule.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 轨道 C：业务敏感操作审计注解（如注册、提交判题、修改密码）。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogBiz {

    /** 业务操作描述，例如 "提交判题" */
    String value();

    /** 业务操作类型标识，例如 "OJ_SUBMIT" */
    String type() default "BIZ";
}
