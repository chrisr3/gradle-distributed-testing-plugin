package com.r3.testing;

import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage;
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage;
import groovy.lang.Closure;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestResult;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public final class Kubernetizer {

    public static Kubernetizer forProject(DistributedTestingProject project) {
        ImageBuilding imagePlugin = project.getImageBuildingPlugin();
        DockerPushImage imagePushTask = imagePlugin.pushTask;
        DockerBuildImage imageBuildTask = imagePlugin.buildTask;

        String tagToUseForRunningTests = System.getProperty(ImageBuilding.PROVIDE_TAG_FOR_RUNNING_PROPERTY);
        BucketingAllocatorTask globalAllocator = project.createGlobalAllocator();

        TestDistributor testDistributor = new TestDistributor(project, globalAllocator, imagePushTask, tagToUseForRunningTests);
        ParallelTestGroupConfigurer parallelTestGroupConfigurer = new ParallelTestGroupConfigurer(project, imageBuildTask, imagePushTask, tagToUseForRunningTests);

        return new Kubernetizer(
                project,
                testDistributor,
                parallelTestGroupConfigurer
        );
    }

    private final DistributedTestingProject project;
    private final TestDistributor testDistributor;
    private final ParallelTestGroupConfigurer parallelTestGroupConfigurer;

    private Kubernetizer(DistributedTestingProject project, TestDistributor testDistributor, ParallelTestGroupConfigurer parallelTestGroupConfigurer) {
        this.project = project;
        this.testDistributor = testDistributor;
        this.parallelTestGroupConfigurer = parallelTestGroupConfigurer;
    }

    public void kubernetize() {
        //in each subproject
        //1. add the task to determine all tests within the module and register this as a source to the global allocator
        //2. modify the underlying testing task to use the output of the global allocator to include a subset of tests for each fork
        //3. KubesTest will invoke these test tasks in a parallel fashion on a remote k8s cluster
        //4. after each completed test write its name to a file to keep track of what finished for restart purposes
        project.traverseRequestedTasks(testDistributor);

        //first step is to create a single task which will invoke all the submodule tasks for each grouping
        //ie allParallelTest will invoke [node:test, core:test, client:rpc:test ... etc]
        //ie allIntegrationTest will invoke [node:integrationTest, core:integrationTest, client:rpc:integrationTest ... etc]
        //ie allUnitAndIntegrationTest will invoke [node:integrationTest, node:test, core:integrationTest, core:test, client:rpc:test , client:rpc:integrationTest ... etc]
        project.traverseParallelTestGroups(parallelTestGroupConfigurer);
    }

}
