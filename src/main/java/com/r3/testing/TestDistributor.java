package com.r3.testing;

import com.bmuschko.gradle.docker.tasks.image.DockerPushImage;
import groovy.lang.Closure;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Task;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestFilter;
import org.gradle.api.tasks.testing.TestResult;
import org.jetbrains.annotations.NotNull;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.r3.testing.TestPlanUtils.getTestClasses;
import static com.r3.testing.TestPlanUtils.getTestMethods;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

public final class TestDistributor {

    private final DistributedTestingProject project;
    private final BucketingAllocatorTask globalAllocator;
    private final DockerPushImage imagePushTask;
    private final String tagToUseForRunningTests;

    public TestDistributor(DistributedTestingProject project, BucketingAllocatorTask globalAllocator, DockerPushImage imagePushTask, String tagToUseForRunningTests) {
        this.project = project;
        this.globalAllocator = globalAllocator;
        this.imagePushTask = imagePushTask;
        this.tagToUseForRunningTests = tagToUseForRunningTests;
    }

    private void logInfo(String info) {
        project.logInfo(info);
    }

    public void generateDistributedTestTasksFor(DistributedTestingSubProject subProject, List<Task> requestedTasks) {
        subProject.traverseTestTasks(test -> distribute(subProject, requestedTasks, test));
    }

    private void distribute(DistributedTestingSubProject subProject, List<Task> requestedTasks, Test test) {
        logInfo("Evaluating " + test.getPath());
        if (requestedTasks.contains(test) && !test.hasProperty("ignoreForDistribution")) {
            logInfo("Modifying " + test.getPath());
            Task testListerTask = createTestListingTasks(test, subProject);
            globalAllocator.addSource((TestLister) testListerTask, test);
            Test modifiedTestTask = modifyTestTaskForParallelExecution(subProject, test);
        } else {
            logInfo("Skipping modification of " + test.getPath() + " as it's not scheduled for execution");
        }

        if (!test.hasProperty("ignoreForDistribution")) {
            //this is what enables execution of a single test suite - for example node:parallelTest would execute all unit tests in node, node:parallelIntegrationTest would do the same for integration tests
            KubesTest parallelTestTask = generateParallelTestingTask(subProject, test, imagePushTask, tagToUseForRunningTests);
        }
    }

    private Task createTestListingTasks(Test task, DistributedTestingSubProject subProject) {
        //determine all the tests which are present in this test task.
        //this list will then be shared between the various worker forks
        ListTests createdListTask = subProject.createListTestsTaskFor(task, listTask -> {
            listTask.setGroup(DistributedTesting.GRADLE_GROUP);
            //the convention is that a testing task is backed by a sourceSet with the same name
            listTask.dependsOn(subProject.getClassesTaskFor(task));
            listTask.doFirst(task1 -> {
                //we want to set the test scanning classpath to only the output of the sourceSet - this prevents dependencies polluting the list
                ((ListTests) task1).scanClassPath = !task.getTestClassesDirs().isEmpty() ? task.getTestClassesDirs() : null;
            });
        });

        //convenience task to utilize the output of the test listing task to display to local console, useful for debugging missing tests
        Task createdPrintTask = subProject.createPrintTaskFor(task, printTask -> configurePrintTask(subProject, createdListTask, printTask));

        subProject.logInfo("created task: " + createdListTask.getPath() + " in project: " + subProject + " it dependsOn: " + createdListTask.dependsOn());
        subProject.logInfo("created task: " + createdPrintTask.getPath() + " in project: " + subProject + " it dependsOn: " + createdPrintTask.dependsOn());

        return createdListTask;
    }

    private void configurePrintTask(DistributedTestingSubProject subProject, ListTests createdListTask, Task printTask) {
        printTask.setGroup(DistributedTesting.GRADLE_GROUP);
        printTask.dependsOn(createdListTask);
        printTask.doLast(task1 -> configurePrintTaskDoLast(subProject, createdListTask));
    }

    private void configurePrintTaskDoLast(DistributedTestingSubProject subProject, ListTests createdListTask) {
        createdListTask.getTestsForFork(
                subProject.getPropertyAsInt("dockerFork", 0),
                subProject.getPropertyAsInt("dockerForks", 1),
                42).forEach(subProject::logInfo);
    }

    private Test modifyTestTaskForParallelExecution(DistributedTestingSubProject subProject, Test task) {
        subProject.logInfo("modifying task: " + task.getPath() + " to depend on task " + globalAllocator.getPath());
        File reportsDir = subProject.createReportsDirFor(task);
        reportsDir.mkdirs();
        File executedTestsFile = new File(KubesTest.TEST_RUN_DIR + "/executedTests.txt");
        task.dependsOn(globalAllocator);
        task.setBinResultsDir(new File(reportsDir, "binary"));
        task.getReports().getJunitXml().setDestination(new File(reportsDir, "xml"));
        task.setMaxHeapSize("10g");

        task.doFirst(task1 -> {
            try {
                executedTestsFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            ((Test)task1).filter(testFilter -> filterTestTask(subProject, executedTestsFile, task1, testFilter));
        });

        task.afterTest(new Closure(this, this) {
            public void doCall(TestDescriptor desc, TestResult result) {
                if (result.getResultType() == TestResult.ResultType.SUCCESS) {
                    try {
                        FileWriter fr = new FileWriter(executedTestsFile, true);
                        fr.write(desc.getClassName() + "." + desc.getName());
                        fr.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        return task;
    }

    private void filterTestTask(DistributedTestingSubProject subProject, File executedTestsFile, Task task1, TestFilter testFilter) {
        List<String> executedTests = getExecutedTests(executedTestsFile);
        int fork = subProject.getPropertyAsInt("dockerFork", 0);
        subProject.logInfo("requesting tests to include in testing task " + task1.getPath() + " (idx: " + fork);
        List<String> includes = globalAllocator.getTestIncludesForForkAndTestTask(fork, (Test) task1);
        subProject.logInfo("got " + includes.size() + " tests to include into testing task " + task1.getPath());
        subProject.logInfo("INCLUDE: " + includes.toString());
        subProject.logInfo("got " + executedTests.size() + " tests to exclude from testing task " + task1.getPath());
        subProject.logDebug("EXCLUDE: " + executedTests.toString());

        if (includes.isEmpty()) {
            subProject.logInfo("Disabling test execution for testing task " + task1.getPath());
            testFilter.excludeTestsMatching("*");
        }

        List<String> intersection = executedTests.stream().filter(includes::contains).collect(Collectors.toList());

        subProject.logInfo("got " + intersection.size() + " tests in intersection");
        subProject.logInfo("INTERSECTION: " + intersection.toString());
        includes.removeAll(intersection);

        intersection.forEach(exclude -> {
            subProject.logInfo("excluding: " + exclude + " for testing task " + task1.getPath());
            testFilter.excludeTestsMatching(exclude);
        });

        includes.forEach(include -> {
            subProject.logInfo("including: " + include + " for testing task " + task1.getPath());
            testFilter.includeTestsMatching(include);
        });

        testFilter.setFailOnNoMatchingTests(false);
    }

    private KubesTest generateParallelTestingTask(DistributedTestingSubProject projectContainingTask, Test task, DockerPushImage imageBuildingTask, String providedTag) {
        KubesTest createdParallelTestTask = projectContainingTask.createKubesTestTaskFor(task, kubesTest -> {
            kubesTest.setGroup(DistributedTesting.GRADLE_GROUP + " Parallel Test Tasks");
            if (StringUtils.isEmpty(providedTag)) {
                kubesTest.dependsOn(imageBuildingTask);
            }
            kubesTest.printOutput = true;
            kubesTest.fullTaskToExecutePath = task.getPath();
            kubesTest.taskToExecuteName = task.getName();
            kubesTest.doFirst(task1 -> {
                ((KubesTest) task1).dockerTag = !StringUtils.isEmpty(providedTag) ? ImageBuilding.registryName + ":" + providedTag :
                        (imageBuildingTask.getImageName().get() + ":" + imageBuildingTask.getTag().get());
            });
        });

        projectContainingTask.logInfo("Created task: " + createdParallelTestTask.getPath() + " to enable testing on kubenetes for task: " + task.getPath());

        return createdParallelTestTask;
    }

    @NotNull
    private List<String> getExecutedTests(@NotNull File executedTestsFile) {
        try {
            //adding wildcard to each test so they match the ones in the includes list
            return Files.readAllLines(executedTestsFile.toPath()).stream()
                    .map(executedTest -> executedTest + "*")
                    .collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
}
