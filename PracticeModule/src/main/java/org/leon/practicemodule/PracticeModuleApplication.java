package org.leon.practicemodule;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan({"org.leon.practicemodule", "org.leon.commonjwt"})
@MapperScan("org.leon.practicemodule.mapper")
public class PracticeModuleApplication {

    public static void main(String[] args) {
        SpringApplication.run(PracticeModuleApplication.class, args);
    }
}
