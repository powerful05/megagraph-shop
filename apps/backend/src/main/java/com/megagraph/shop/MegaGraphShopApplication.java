package com.megagraph.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MegaGraphShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(MegaGraphShopApplication.class, args);
    }
}
