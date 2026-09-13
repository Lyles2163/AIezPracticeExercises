package org.leon.usermodule.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
//@Aspect//表示当前是AOP类
@Component
public class RecordTimeAspect {

    @Around("execution(* org.leon.usermodule.service.impl.*.*(..))")
    public Object recordTime(ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
        //1.记录开始时间
        long begin = System.currentTimeMillis();
        //2.执行原始方法
        Object result = proceedingJoinPoint.proceed();
        //3.记录结束时间
        long end = System.currentTimeMillis();
        log.info("方法执行用时{}ms", end - begin);
        return result;
    }


}
