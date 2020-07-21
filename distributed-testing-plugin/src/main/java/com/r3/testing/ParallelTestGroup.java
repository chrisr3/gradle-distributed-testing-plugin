package com.r3.testing;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Console;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SuppressWarnings("unused")
public class ParallelTestGroup extends DefaultTask {

    private DistributeTestsBy distribution = DistributeTestsBy.METHOD;
    private List<String> groups = new ArrayList<>();
    private int shardCount = 20;
    private int coresToUse = 4;
    private int gbOfMemory = 4;
    private boolean printToStdOut = true;
    private PodLogLevel logLevel = PodLogLevel.INFO;
    private String sidecarImage;
    private List<String> additionalArgs = new ArrayList<>();
    private List<String> taints = new ArrayList<>();

    @Input
    public DistributeTestsBy getDistribution() {
        return distribution;
    }

    @Input
    public List<String> getGroups() {
        return groups;
    }

    @Input
    public int getShardCount() {
        return shardCount;
    }

    @Input
    public int getCoresToUse() {
        return coresToUse;
    }

    @Input
    public int getGbOfMemory() {
        return gbOfMemory;
    }

    @Console
    public boolean getPrintToStdOut() {
        return printToStdOut;
    }

    @Console
    public PodLogLevel getLogLevel() {
        return logLevel;
    }

    @Optional
    @Input
    public String getSidecarImage() {
        return sidecarImage;
    }

    @Input
    public List<String> getAdditionalArgs() {
        return additionalArgs;
    }

    @Input
    public List<String> getNodeTaints(){
        return new ArrayList<>(taints);
    }

    public void numberOfShards(int shards) {
        this.shardCount = shards;
    }

    public void podLogLevel(PodLogLevel level) {
        this.logLevel = level;
    }

    public void distribute(DistributeTestsBy dist) {
        this.distribution = dist;
    }

    public void coresPerFork(int cores) {
        this.coresToUse = cores;
    }

    public void memoryInGbPerFork(int gb) {
        this.gbOfMemory = gb;
    }

    //when this is false, only containers will "failed" exit codes will be printed to stdout
    public void streamOutput(boolean print) {
        this.printToStdOut = print;
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

    public void nodeTaints(String... additionalArgs) {
        nodeTaints(Arrays.asList(additionalArgs));
    }

    private void nodeTaints(List<String> additionalArgs) {
        this.taints.addAll(additionalArgs);
    }

}
