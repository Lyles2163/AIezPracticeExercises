package org.leon.gatewaymodule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@ComponentScan(basePackages = {"org.leon.gatewaymodule", "org.leon.commonjwt"})
@SpringBootApplication
public class GatewayModuleApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayModuleApplication.class, args);
    }

}
