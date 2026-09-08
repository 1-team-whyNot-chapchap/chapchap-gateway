package com.chapchapgateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(locations = "file:.env.example", properties = "spring.config.import=")
class ChapchapGatewayApplicationTests {

    @Test
    void contextLoads() {
    }

}
