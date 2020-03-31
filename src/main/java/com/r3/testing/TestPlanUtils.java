package com.r3.testing;

import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.substringBetween;

public class TestPlanUtils {

    /**
     * Returns set of test classes in TestPlan
     * @param testPlan
     * @return
     */
    public static Set<String> getTestClasses(TestPlan testPlan) {
        return convertToSet(testPlan).stream()
                .filter(ti -> !ti.contains("()"))
                .collect(Collectors.toSet());
    }

    /**
     * Returns set of test methods in TestPlan
     * @param testPlan
     * @return
     */
    public static Set<String> getTestMethods(TestPlan testPlan) {
        return convertToSet(testPlan).stream()
                .filter(ti -> ti.contains("()"))
                .map(ti -> ti.replaceAll("\\(\\)", ""))
                .collect(Collectors.toSet());
    }

    /**
     * Removes root nodes (test engines) from TestPlan and returns all other nodes i.e. test classes and methods
     */
    private static Set<String> convertToSet(TestPlan testPlan) {
        Set<String> tests = new HashSet<>();
        for (TestIdentifier root: testPlan.getRoots()) {
            for (TestIdentifier descendant: testPlan.getDescendants(root)) {
                if (descendant.isTest()) {
                    tests.add(convertTestIdentifierToString(descendant));
                } else {
                    tests.add(convertTestIdentifierToString(descendant));
                }
            }
        }
        return tests;
    }

    /**
     * Helper method to convert JUnit5 Launcher API TestIdentifier to appropriate class / method name string
     */
    private static String convertTestIdentifierToString(TestIdentifier testIdentifier) {
        String uniqueId = testIdentifier.getUniqueId();
        if (uniqueId.contains("()")) {
            return substringBetween(uniqueId, "class:", "]")
                    + "." + substringBetween(uniqueId, "method:", "]");
        } else {
            return substringBetween(testIdentifier.getUniqueId(), "class:", "]");

        }
    }
}
