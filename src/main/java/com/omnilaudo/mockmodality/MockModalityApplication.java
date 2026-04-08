package com.omnilaudo.mockmodality;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MockModalityApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockModalityApplication.class, args);
    }
}
