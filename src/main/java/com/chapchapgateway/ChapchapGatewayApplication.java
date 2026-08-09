package com.chapchapgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ChapchapGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChapchapGatewayApplication.class, args);
    }

}
