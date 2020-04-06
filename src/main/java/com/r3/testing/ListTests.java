package com.r3.testing;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.TaskAction;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.FileReader;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.r3.testing.TestPlanUtils.getTestClasses;
import static com.r3.testing.TestPlanUtils.getTestMethods;
import static org.junit.platform.engine.discovery.DiscoverySelectors.*;

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
        System.out.println("--- scanclasspath ---");
        System.out.println(scanClassPath);
        System.out.println("--- print files in scanclasspath ---");
        scanClassPath.getAsFileTree().getFiles().forEach(System.out::println);
        System.out.println("--- discover tests with JUnit5 launcher ---");
        Set<Path> classpathRoots = scanClassPath.getFiles()
                .stream().map(file -> Paths.get(file.toURI())).collect(Collectors.toSet());
        TestPlan testPlan = LauncherFactory.create().discover(
                LauncherDiscoveryRequestBuilder.request().selectors(
                        selectClasspathRoots(classpathRoots)
                ).build());
        if (getTestClasses(testPlan).size() > 0 || getTestMethods(testPlan).size() > 0) {
            System.out.println("+++++ Success +++++");
            System.out.println("--- test classes ---");
            getTestClasses(testPlan).forEach(System.out::println);
            System.out.println("--- test methods ---");
            getTestMethods(testPlan).forEach(System.out::println);
        } else {
            System.out.println(">>>>> Failure <<<<<");
        }
        switch (distribution) {
            case METHOD:
                this.allTests = new ArrayList<>(getTestMethods(testPlan));
                break;
            case CLASS:
                this.allTests = new ArrayList<>(getTestClasses(testPlan));
                break;
        }
    }
}