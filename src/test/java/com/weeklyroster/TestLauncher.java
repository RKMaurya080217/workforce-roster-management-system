package com.weeklyroster;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

import java.io.PrintWriter;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

public class TestLauncher {
	public static void main(String[] args) throws Exception {
		System.out.println("Starting TestLauncher...");
		LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
				.selectors(selectPackage("com.weeklyroster")).build();

		Launcher launcher = LauncherFactory.create();
		SummaryGeneratingListener listener = new SummaryGeneratingListener();
		launcher.registerTestExecutionListeners(listener);
		launcher.execute(request);

		TestExecutionSummary summary = listener.getSummary();
		PrintWriter pw = new PrintWriter(System.out, true);
		summary.printTo(pw);

		if (summary.getTotalFailureCount() > 0) {
			System.err.println("\n--- DETAILED FAILURES (" + summary.getTotalFailureCount() + ") ---");
			for (TestExecutionSummary.Failure failure : summary.getFailures()) {
				System.err.println("Test: " + failure.getTestIdentifier().getDisplayName());
				if (failure.getException() != null) {
					failure.getException().printStackTrace(System.err);
				}
				System.err.println("----------------------------------------");
			}
			System.exit(1);
		} else {
			System.out.println("\n>>> ALL " + summary.getTestsSucceededCount() + " TESTS PASSED! <<<");
			System.exit(0);
		}
	}
}
