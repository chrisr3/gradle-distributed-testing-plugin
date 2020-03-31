package com.r3.testing;

import java.util.ArrayList;
import java.util.List;

public class InfrastructureProfile {
    private int numberOfShards = 20;
    private boolean streamOutput = true;
    private int coresPerFork = 4;
    private int memoryInGbPerFork = 4;
    private List<String> nodeTaints = new ArrayList<>();

    public int getNumberOfShards() {
        return numberOfShards;
    }

    public void setNumberOfShards(int numberOfShards) {
        this.numberOfShards = numberOfShards;
    }

    public boolean isStreamOutput() {
        return streamOutput;
    }

    public void setStreamOutput(boolean streamOutput) {
        this.streamOutput = streamOutput;
    }

    public int getCoresPerFork() {
        return coresPerFork;
    }

    public void setCoresPerFork(int coresPerFork) {
        this.coresPerFork = coresPerFork;
    }

    public int getMemoryInGbPerFork() {
        return memoryInGbPerFork;
    }

    public void setMemoryInGbPerFork(int memoryInGbPerFork) {
        this.memoryInGbPerFork = memoryInGbPerFork;
    }

    public List<String> getNodeTaints() {
        return nodeTaints;
    }

    public void setNodeTaints(List<String> nodeTaints) {
        this.nodeTaints = nodeTaints;
    }
}
