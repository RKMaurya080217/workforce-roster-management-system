package com.weeklyroster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WeeklyRosterManagementApplication {
    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        SpringApplication app = new SpringApplication(WeeklyRosterManagementApplication.class);
        app.setHeadless(true);
        app.run(args);
    }
}
