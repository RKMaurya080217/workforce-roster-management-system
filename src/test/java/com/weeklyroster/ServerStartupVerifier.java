package com.weeklyroster;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

public class ServerStartupVerifier {
    public static void main(String[] args) {
        System.out.println("Starting 3 Consecutive Spring Boot Start/Stop Verifications with Java " + System.getProperty("java.version"));

        for (int i = 1; i <= 3; i++) {
            System.out.println("\n--- [STARTUP RUN " + i + "/3] ---");
            long start = System.currentTimeMillis();
            try {
                // Launch Spring Boot application context
                ConfigurableApplicationContext context = SpringApplication.run(WeeklyRosterManagementApplication.class,
                        "--server.port=0", // Random port to avoid bind conflicts
                        "--spring.main.banner-mode=off");

                long elapsed = System.currentTimeMillis() - start;
                System.out.println("SUCCESS: Spring Boot Run " + i + " started in " + elapsed + " ms! Active beans: " + context.getBeanDefinitionCount());

                // Verify core beans
                assert context.containsBean("rosterService");
                assert context.containsBean("employeeService");
                assert context.containsBean("securityFilterChain");

                // Gracefully stop application context
                context.close();
                System.out.println("SUCCESS: Spring Boot Run " + i + " closed gracefully.");
            } catch (Throwable t) {
                System.err.println("FAILED: Spring Boot Run " + i + " failed with exception: " + t.getMessage());
                t.printStackTrace();
                System.exit(1);
            }
        }

        System.out.println("\n>>> ALL 3 CONSECUTIVE SPRING BOOT STARTUPS PASSED PERFECTLY! <<<");
    }
}
