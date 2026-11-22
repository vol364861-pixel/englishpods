package com.englishpods;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EnglishPodsApplication {
    public static void main(String[] args) {
        SpringApplication.run(EnglishPodsApplication.class, args);
    }
}
