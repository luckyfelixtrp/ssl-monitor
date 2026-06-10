package com.sslmonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SslMonitorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SslMonitorApplication.class, args);
    }
}
