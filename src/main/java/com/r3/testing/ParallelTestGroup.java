package com.r3.testing;

import org.gradle.api.DefaultTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ParallelTestGroup extends DefaultTask {

    private PodLogLevel logLevel = PodLogLevel.INFO;
    private List<String> groups = new ArrayList<>();
    private InfrastructureProfile profile;
    private DistributeTestsBy distribution = DistributeTestsBy.METHOD;
    private String sidecarImage;
    private List<String> additionalArgs = new ArrayList<>();

    public DistributeTestsBy getDistribution() {
        return distribution;
    }

    public List<String> getGroups() {
        return groups;
    }

    public int getShardCount() {
        return profile.getNumberOfShards();
    }

    public int getCoresToUse() {
        return profile.getCoresPerFork();
    }

    public int getGbOfMemory() {
        return profile.getMemoryInGbPerFork();
    }

    public boolean getPrintToStdOut() {
        return profile.isStreamOutput();
    }

    public PodLogLevel getLogLevel() {
        return logLevel;
    }

    public String getSidecarImage() {
        return sidecarImage;
    }

    public List<String> getAdditionalArgs() {
        return additionalArgs;
    }

    public List<String> getNodeTaints(){
        return new ArrayList<>(profile.getNodeTaints());
    }

    public void profile(InfrastructureProfile profile) {
        this.profile = profile;
    }

    public void podLogLevel(PodLogLevel level) {
        this.logLevel = level;
    }

    public void distribute(DistributeTestsBy dist) {
        this.distribution = dist;
    }

    public void testGroups(String... group) {
        testGroups(Arrays.asList(group));
    }

    private void testGroups(List<String> group) {
        groups.addAll(group);
    }

    public void sidecarImage(String sidecarImage) {
        this.sidecarImage = sidecarImage;
    }

    public void additionalArgs(String... additionalArgs) {
        additionalArgs(Arrays.asList(additionalArgs));
    }

    private void additionalArgs(List<String> additionalArgs) {
        this.additionalArgs.addAll(additionalArgs);
    }

}
