package com.weeklyroster;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.io.PrintWriter;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import com.weeklyroster.service.Batch13AnalyticsAndConflictDetectorTest;

public class Batch13TestRunner {
    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(Batch13AnalyticsAndConflictDetectorTest.class))
                .build();

        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));

        for (TestExecutionSummary.Failure failure : summary.getFailures()) {
            System.err.println("FAILED TEST: " + failure.getTestIdentifier().getDisplayName());
            failure.getException().printStackTrace();
        }

        if (summary.getTotalFailureCount() > 0) {
            System.exit(1);
        } else {
            System.out.println("ALL BATCH 13 TESTS PASSED SUCCESSFULLY!");
            System.exit(0);
        }
    }
}