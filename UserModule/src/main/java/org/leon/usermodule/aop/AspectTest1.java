package org.leon.usermodule.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect//表示当前是AOP类
@Component
public class AspectTest1 {

//    @Before("execution(public void org.leon.usermodule.service.impl.UsersServiceImpl.deleteById(java.lang.Integer))")
//    @Before("execution( void deleteById(java.lang.Integer))") //省略写法，不建议报名和类名省略

//   @Before("execution(* org.leon.usermodule.service.impl.UsersServiceImpl.deleteById(..))||"+
//           "execution(* org.leon.usermodule.service.impl.UsersServiceImpl.selectAll(..))")

    @Before("@annotation(org.leon.usermodule.annotation.LogOperation)")
    public void before(){
        log.info("MyAspect---->before ...");
    }

//    @Around("execution(* org.leon.usermodule.service.impl.*.*(..))")
//    public Object recordTime(ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
//        //1.记录开始时间
//        long begin = System.currentTimeMillis();
//        //2.执行原始方法
//        Object result = proceedingJoinPoint.proceed();
//        //3.记录结束时间
//        long end = System.currentTimeMillis();
//        log.info("方法执行用时{}ms", end - begin);
//        return result;
//    }


}
