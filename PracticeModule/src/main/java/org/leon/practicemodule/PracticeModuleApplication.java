package org.leon.practicemodule;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@MapperScan("org.leon.practicemodule.mapper")
@SpringBootApplication
public class PracticeModuleApplication {

    public static void main(String[] args) {
        SpringApplication.run(PracticeModuleApplication.class, args);
    }

}
