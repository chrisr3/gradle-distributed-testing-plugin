package com.r3.testing;

import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage;
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage;
import org.gradle.api.Project;

public final class Kubernetizer {

    public static Kubernetizer configuredWith(DistributedTestingConfiguration configuration) {
        return new Kubernetizer(configuration);
    }

    private final DistributedTestingConfiguration configuration;

    private Kubernetizer(DistributedTestingConfiguration configuration) {
        this.configuration = configuration;
    }

    public void kubernetize(Project project) {
        DistributedTestingProject distributedTestingProject = DistributedTestingProject.forProject(project);

        ImageBuilding imagePlugin = distributedTestingProject.getImageBuildingPlugin();
        DockerPushImage imagePushTask = imagePlugin.pushTask;
        DockerBuildImage imageBuildTask = imagePlugin.buildTask;

        String tagToUseForRunningTests = configuration.getTagToUseForRunningTests();
        BucketingAllocatorTask globalAllocator = distributedTestingProject.createGlobalAllocator();

        TestDistributor testDistributor = new TestDistributor(
                distributedTestingProject,
                globalAllocator,
                imagePushTask,
                tagToUseForRunningTests);

        ParallelTestGroupConfigurer parallelTestGroupConfigurer = new ParallelTestGroupConfigurer(
                distributedTestingProject,
                imageBuildTask,
                imagePushTask,
                tagToUseForRunningTests);

        //in each subproject
        //1. add the task to determine all tests within the module and register this as a source to the global allocator
        //2. modify the underlying testing task to use the output of the global allocator to include a subset of tests for each fork
        //3. KubesTest will invoke these test tasks in a parallel fashion on a remote k8s cluster
        //4. after each completed test write its name to a file to keep track of what finished for restart purposes
        distributedTestingProject.traverseRequestedTasks(testDistributor);

        //first step is to create a single task which will invoke all the submodule tasks for each grouping
        //ie allParallelTest will invoke [node:test, core:test, client:rpc:test ... etc]
        //ie allIntegrationTest will invoke [node:integrationTest, core:integrationTest, client:rpc:integrationTest ... etc]
        //ie allUnitAndIntegrationTest will invoke [node:integrationTest, node:test, core:integrationTest, core:test, client:rpc:test , client:rpc:integrationTest ... etc]
        distributedTestingProject.traverseParallelTestGroups(parallelTestGroupConfigurer);
    }

}
