package com.r3.testing;

public final class DistributedTestingConfiguration {

    public static DistributedTestingConfiguration fromSystem() {
        return new DistributedTestingConfiguration(
                System.getProperty(ImageBuilding.PROVIDE_TAG_FOR_RUNNING_PROPERTY)
        );
    }

    private final String tagToUseForRunningTests;

    public DistributedTestingConfiguration(String tagToUseForRunningTests) {
        this.tagToUseForRunningTests = tagToUseForRunningTests;
    }

    public String getTagToUseForRunningTests() {
        return tagToUseForRunningTests;
    }
}
