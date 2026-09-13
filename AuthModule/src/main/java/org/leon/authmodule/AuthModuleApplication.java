package org.leon.authmodule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan; // 引入这个包

@SpringBootApplication
// 指定扫描 CommonJwt 模块下的组件，加上自己模块的包路径防止冲突
@ComponentScan(basePackages = {"org.leon.authmodule", "org.leon.commonjwt"})
public class AuthModuleApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthModuleApplication.class, args);
    }
}