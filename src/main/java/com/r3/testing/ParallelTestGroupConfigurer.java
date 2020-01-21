package com.r3.testing;

import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage;
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.tasks.testing.Test;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

public final class ParallelTestGroupConfigurer {

    private final DistributedTestingProject project;
    private final DockerBuildImage imageBuildTask;
    private final DockerPushImage imagePushTask;
    private final String tagToUseForRunningTests;

    public ParallelTestGroupConfigurer(DistributedTestingProject project, DockerBuildImage imageBuildTask, DockerPushImage imagePushTask, String tagToUseForRunningTests) {
        this.project = project;
        this.imageBuildTask = imageBuildTask;
        this.imagePushTask = imagePushTask;
        this.tagToUseForRunningTests = tagToUseForRunningTests;
    }

    public void configureParallelTestGroup(ParallelTestGroup testGrouping, Set<String> requestedTaskNames, Map<String, List<Test>> allTestTasksGroupedByType) {
        List<Test> testTasksToRunInGroup = testGrouping.getGroups().stream()
                .flatMap(group -> allTestTasksGroupedByType.get(group).stream())
                .collect(Collectors.toList());

        //join up these test tasks into a single set of tasks to invoke (node:test, node:integrationTest...)
        String superListOfTasks = testTasksToRunInGroup.stream()
                .map(Task::getPath)
                .collect(Collectors.joining(" "));

        //generate a preAllocate / deAllocate task which allows you to "pre-book" a node during the image building phase
        //this prevents time lost to cloud provider node spin up time (assuming image build time > provider spin up time)
        List<Task> returnedList = generatePreAllocateAndDeAllocateTasksForGrouping(testGrouping);
        Task preAllocateTask = returnedList.get(0);
        Task deAllocateTask = returnedList.get(1);

        //modify the image building task to depend on the preAllocate task (if specified on the command line) - this prevents gradle running out of order
        if (requestedTaskNames.contains(preAllocateTask.getName())) {
            imageBuildTask.dependsOn(preAllocateTask);
            imagePushTask.finalizedBy(deAllocateTask);
        }

        KubesTest userDefinedParallelTask = project.createKubesTestFor(testGrouping, kubesTest ->
            configureKubesTest(requestedTaskNames, testGrouping, superListOfTasks, deAllocateTask, kubesTest)
        );

        KubesReporting reportOnAllTask = project.createKubesReportingTaskFor(testGrouping, kubesReporting -> {
            configureKubesReporting(testGrouping, userDefinedParallelTask, kubesReporting);
        });

        // Task to zip up test results, and upload them to somewhere (Artifactory).
        Task zipTask = project.createZipTaskFor(testGrouping, userDefinedParallelTask);

        userDefinedParallelTask.finalizedBy(reportOnAllTask);
        zipTask.dependsOn(userDefinedParallelTask);
        testGrouping.dependsOn(zipTask);
    }

    private void configureKubesTest( Set<String> requestedTaskNames, ParallelTestGroup testGrouping, String superListOfTasks, Task deAllocateTask, KubesTest kubesTest) {
        kubesTest.setGroup(DistributedTesting.GRADLE_GROUP);
        if (StringUtils.isEmpty(tagToUseForRunningTests)) {
            kubesTest.dependsOn(imagePushTask);
        }
        if (requestedTaskNames.contains(deAllocateTask.getName())) {
            kubesTest.dependsOn(deAllocateTask);
        }

        kubesTest.numberOfPods = testGrouping.getShardCount();
        kubesTest.printOutput = testGrouping.getPrintToStdOut();
        kubesTest.fullTaskToExecutePath = superListOfTasks;
        kubesTest.taskToExecuteName = String.join("And", testGrouping.getGroups());
        kubesTest.memoryGbPerFork = testGrouping.getGbOfMemory();
        kubesTest.numberOfCoresPerFork = testGrouping.getCoresToUse();
        kubesTest.distribution = testGrouping.getDistribution();
        kubesTest.podLogLevel = testGrouping.getLogLevel();
        kubesTest.taints = testGrouping.getNodeTaints();
        kubesTest.sidecarImage = testGrouping.getSidecarImage();
        kubesTest.additionalArgs = testGrouping.getAdditionalArgs();
        kubesTest.doFirst(task -> ((KubesTest) task).dockerTag = !StringUtils.isEmpty(tagToUseForRunningTests) ?
                (ImageBuilding.registryName + ":" + tagToUseForRunningTests) :
                (imagePushTask.getImageName().get() + ":" + imagePushTask.getTag().get()));
    }

    private void configureKubesReporting(ParallelTestGroup testGrouping, KubesTest userDefinedParallelTask, KubesReporting kubesReporting) {
        kubesReporting.setGroup(DistributedTesting.GRADLE_GROUP);
        kubesReporting.dependsOn(userDefinedParallelTask);
        kubesReporting.setDestinationDir(project.createUserDefinedReportsDirFor(testGrouping));
        kubesReporting.doFirst(task -> {
            ((KubesReporting) task).getDestinationDir().delete();
            ((KubesReporting) task).shouldPrintOutput = !testGrouping.getPrintToStdOut();
            ((KubesReporting) task).podResults = userDefinedParallelTask.containerResults;
            ((KubesReporting) task).reportOn(userDefinedParallelTask.testOutput);
        });
    }

    private List<Task> generatePreAllocateAndDeAllocateTasksForGrouping(ParallelTestGroup testGrouping) {
        PodAllocator allocator = project.createPodAllocator();
        Task preAllocateTask = project.createPreAllocateTaskFor(testGrouping, task -> {
            task.setGroup(DistributedTesting.GRADLE_GROUP);
            task.doFirst(task1 -> {
                String dockerTag = System.getProperty(ImageBuilding.PROVIDE_TAG_FOR_BUILDING_PROPERTY);
                if (dockerTag == null) {
                    throw new GradleException("pre allocation cannot be used without a stable docker tag - please provide one  using -D" + ImageBuilding.PROVIDE_TAG_FOR_BUILDING_PROPERTY);
                }
                int seed = (dockerTag.hashCode() + testGrouping.getName().hashCode());
                String podPrefix = new BigInteger(64, new Random(seed)).toString(36);
                //here we will pre-request the correct number of pods for this testGroup
                int numberOfPodsToRequest = testGrouping.getShardCount();
                int coresPerPod = testGrouping.getCoresToUse();
                int memoryGBPerPod = testGrouping.getGbOfMemory();
                allocator.allocatePods(numberOfPodsToRequest, coresPerPod, memoryGBPerPod, podPrefix, testGrouping.getNodeTaints());
            });
        });

        Task deAllocateTask = project.createDeallocateTaskFor(testGrouping, task -> {
            task.setGroup(DistributedTesting.GRADLE_GROUP);
            task.doFirst(task1 -> {
                String dockerTag = !StringUtils.isEmpty(System.getProperty(ImageBuilding.PROVIDE_TAG_FOR_RUNNING_PROPERTY)) ?
                        System.getProperty(ImageBuilding.PROVIDE_TAG_FOR_RUNNING_PROPERTY) :
                        System.getProperty(ImageBuilding.PROVIDE_TAG_FOR_BUILDING_PROPERTY);
                if (dockerTag == null) {
                    throw new GradleException("pre allocation cannot be used without a stable docker tag - please provide one using -D" + ImageBuilding.PROVIDE_TAG_FOR_RUNNING_PROPERTY);
                }
                int seed = (dockerTag.hashCode() + testGrouping.getName().hashCode());
                String podPrefix = new BigInteger(64, new Random(seed)).toString(36);
                allocator.tearDownPods(podPrefix);
            });
        });
        return new ArrayList<>(Arrays.asList(preAllocateTask, deAllocateTask));
    }
}
