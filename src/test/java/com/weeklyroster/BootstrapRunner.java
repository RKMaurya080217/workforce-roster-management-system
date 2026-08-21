package com.weeklyroster;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class BootstrapRunner {
    public static void main(String[] args) throws Exception {
        String projectRoot = "C:\\Users\\RK Maurya\\Documents\\Roster Management System";
        List<URL> urls = new ArrayList<>();
        urls.add(Paths.get(projectRoot, "target", "test-classes").toUri().toURL());
        urls.add(Paths.get(projectRoot, "target", "classes").toUri().toURL());

        File cachedCp = new File(projectRoot, "target/cached_cp.txt");
        if (cachedCp.exists()) {
            String cpText = Files.readString(cachedCp.toPath());
            String[] entries = cpText.split(";");
            for (String entry : entries) {
                entry = entry.trim();
                if (!entry.isBlank()) {
                    File f = new File(entry);
                    if (f.exists()) {
                        urls.add(f.toURI().toURL());
                    }
                }
            }
        }

        System.out.println("BootstrapRunner: Loaded " + urls.size() + " exact classpath entries.");
        URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        Thread.currentThread().setContextClassLoader(classLoader);

        String targetClassName = args.length > 0 ? args[0] : "com.weeklyroster.TestLauncher";
        String[] forwardArgs = args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0];

        Class<?> targetClass = Class.forName(targetClassName, true, classLoader);
        Method mainMethod = targetClass.getMethod("main", String[].class);
        mainMethod.invoke(null, (Object) forwardArgs);
    }
}
