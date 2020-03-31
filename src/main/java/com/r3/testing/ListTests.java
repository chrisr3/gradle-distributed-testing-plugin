package com.r3.testing;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.TaskAction;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static com.r3.testing.TestPlanUtils.getTestClasses;
import static com.r3.testing.TestPlanUtils.getTestMethods;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

interface TestLister {
    List<String> getAllTestsDiscovered();
}

public class ListTests extends DefaultTask implements TestLister {

    public static final String DISTRIBUTION_PROPERTY = "distributeBy";

    public FileCollection scanClassPath;
    private List<String> allTests;
    private DistributeTestsBy distribution = System.getProperty(DISTRIBUTION_PROPERTY) != null && !System.getProperty(DISTRIBUTION_PROPERTY).isEmpty() ?
            DistributeTestsBy.valueOf(System.getProperty(DISTRIBUTION_PROPERTY)) : DistributeTestsBy.METHOD;

    public List<String> getTestsForFork(int fork, int forks, Integer seed) {
        BigInteger gitSha = new BigInteger(getProject().hasProperty("corda_revision") ?
                getProject().property("corda_revision").toString() : "0", 36);
        if (fork >= forks) {
            throw new IllegalArgumentException("requested shard " + (fork + 1) + " for total shards " + forks);
        }
        int seedToUse = seed != null ? (seed + (this.getPath()).hashCode() + gitSha.intValue()) : 0;
        return new ListShufflerAndAllocator(allTests).getTestsForFork(fork, forks, seedToUse);
    }

    @Override
    public List<String> getAllTestsDiscovered() {
        return new ArrayList<>(allTests);
    }

    @TaskAction
    void discoverTests() {
        System.out.println("--- discoverTests ---");
        System.out.println("--- scanClassPath : " + scanClassPath + "---");
        System.out.println("--- scanClassPath : " + scanClassPath.getAsPath() + "---");
        // TODO: Devise mechanism for package selection
        TestPlan testPlan = LauncherFactory.create().discover(
                LauncherDiscoveryRequestBuilder.request().selectors(selectPackage("com")).build());

        switch (distribution) {
            case METHOD:
                this.allTests = new ArrayList<>(getTestMethods(testPlan));
                break;
            case CLASS:
                this.allTests = new ArrayList<>(getTestClasses(testPlan));
                break;
        }
    }

    @Test
    public void someTest() {
        assertTrue(true);
    }
}