package org.leon.aimodule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class AiModuleApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiModuleApplication.class, args);
    }

}
