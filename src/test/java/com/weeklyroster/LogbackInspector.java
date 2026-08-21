package com.weeklyroster;

public class LogbackInspector {
    public static void main(String[] args) {
        try {
            Class<?> lcClass = Class.forName("ch.qos.logback.classic.LoggerContext");
            System.out.println("LoggerContext loaded from: " + lcClass.getProtectionDomain().getCodeSource().getLocation());
            
            for (java.lang.reflect.Method m : lcClass.getMethods()) {
                if (m.getName().equals("getConfigurationLock")) {
                    System.out.println("Found getConfigurationLock(): " + m);
                }
            }

            Class<?> cbClass = Class.forName("ch.qos.logback.core.ContextBase");
            System.out.println("ContextBase loaded from: " + cbClass.getProtectionDomain().getCodeSource().getLocation());
            for (java.lang.reflect.Method m : cbClass.getMethods()) {
                if (m.getName().equals("getConfigurationLock")) {
                    System.out.println("Found in ContextBase: " + m);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
