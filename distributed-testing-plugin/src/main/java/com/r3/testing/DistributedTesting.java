package com.r3.testing;

import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage;
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage;
import groovy.lang.Closure;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.*;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestResult;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This plugin is responsible for wiring together the various components of test task modification
 */
class DistributedTesting implements Plugin<Project> {

    public static final String GRADLE_GROUP = "Distributed Testing";

    public static Integer getPropertyAsInt(Project proj, String property, Integer defaultValue) {
        return proj.hasProperty(property) ? Integer.parseInt(proj.property(property).toString()) : defaultValue;
    }

    private static class TestingGroupData {
        private final Map<String, List<Test>> allTestTasksGroupedByType;
        private final List<ParallelTestGroup> testGroupings;
        private final List<Test> parallelTests;
        private final Set<String> requestedTaskNames;

        private final DockerPushImage imagePushTask;
        private final DockerBuildImage imageBuildTask;
        private final String tagToUseForRunningTests;

        TestingGroupData(Project project, ImageBuilding imagePlugin, Set<String> requestedTaskNames) {
            testGroupings = new LinkedList<>();
            parallelTests = new LinkedList<>();
            this.requestedTaskNames = requestedTaskNames;

            //now we are going to create "super" groupings of the Test tasks, so that it is possible to invoke all submodule tests with a single command
            //group all test Tasks by their underlying target task (test/integrationTest/smokeTest ... etc)
            if (project.getSubprojects().size() != 0) {
                allTestTasksGroupedByType = project.getSubprojects().stream()
                    .flatMap(prj ->
                        prj.getAllTasks(false).values().stream()
                            .flatMap(Collection::stream)
                            .filter(task -> task instanceof Test)
                            .map(Test.class::cast))
                    .collect(Collectors.groupingBy(Task::getName));
            } else {
                allTestTasksGroupedByType = project.getAllTasks(false).values().stream()
                    .flatMap(Collection::stream)
                    .filter(task -> task instanceof Test)
                    .map(Test.class::cast)
                    .collect(Collectors.groupingBy(Task::getName));
            }

            imagePushTask = imagePlugin.pushTask;
            imageBuildTask = imagePlugin.buildTask;
            tagToUseForRunningTests = System.getProperty(ImageBuilding.PROVIDE_TAG_FOR_RUNNING_PROPERTY);
        }

        void addTestGroup(ParallelTestGroup testGroup) {
            testGroupings.add(testGroup);
        }

        void addTest(Test test) {
            parallelTests.add(test);
        }

        void configure(Project project) {
            for (ParallelTestGroup testGroup : testGroupings) {
                configureTestGrouping(testGroup, project);
            }
            for (Test test : parallelTests) {
                generateParallelTestingTask(project, test);
            }
        }

        private void configureTestGrouping(ParallelTestGroup testGrouping, Project project) {
            List<Test> testTasksToRunInGroup = testGrouping.getGroups().stream()
                .flatMap(group -> allTestTasksGroupedByType.get(group).stream())
                .collect(Collectors.toList());
            //join up these test tasks into a single set of tasks to invoke (node:test, node:integrationTest...)
            String superListOfTasks = testTasksToRunInGroup.stream()
                .map(Task::getPath)
                .collect(Collectors.joining(" "));

            //generate a preAllocate / deAllocate task which allows you to "pre-book" a node during the image building phase
            //this prevents time lost to cloud provider node spin up time (assuming image build time > provider spin up time)
            List<TaskProvider<? extends Task>> returnedList = generatePreAllocateAndDeAllocateTasksForGrouping(project, testGrouping);
            TaskProvider<? extends Task> preAllocateTask = returnedList.get(0);
            TaskProvider<? extends Task> deAllocateTask = returnedList.get(1);

            //modify the image building task to depend on the preAllocate task (if specified on the command line) - this prevents gradle running out of order
            if (requestedTaskNames.contains(preAllocateTask.getName())) {
                imageBuildTask.dependsOn(preAllocateTask);
                imagePushTask.finalizedBy(deAllocateTask);
            }

            TaskProvider<KubesTest> userDefinedParallelTask = project.getRootProject().getTasks()
                .register("userDefined" + capitalize(testGrouping.getName()), KubesTest.class, kubesTest -> {
                    kubesTest.setGroup(GRADLE_GROUP);
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
                });

            TaskProvider<KubesReporting> reportOnAllTask = project.getRootProject().getTasks()
                .register("userDefinedReports" + capitalize(testGrouping.getName()), KubesReporting.class, kubesReporting -> {
                    kubesReporting.setGroup(GRADLE_GROUP);
                    kubesReporting.dependsOn(userDefinedParallelTask);
                    kubesReporting.setDestinationDir(new File(project.getRootProject().getBuildDir(), "userDefinedReports" + capitalize(testGrouping.getName())));
                    kubesReporting.doFirst(task -> {
                        ((KubesReporting) task).getDestinationDir().delete();
                        ((KubesReporting) task).shouldPrintOutput = !testGrouping.getPrintToStdOut();
                        ((KubesReporting) task).podResults = userDefinedParallelTask.get().containerResults;
                        ((KubesReporting) task).reportOn(userDefinedParallelTask.get().testOutput);
                    });
                });
            // Task to zip up test results, and upload them to somewhere (Artifactory).
            TaskProvider<? extends Task> zipTask = TestDurationArtifacts.createZipTask(project.getRootProject(), testGrouping.getName(), userDefinedParallelTask);

            userDefinedParallelTask.configure(task -> task.finalizedBy(reportOnAllTask));
            zipTask.configure(t -> t.dependsOn(userDefinedParallelTask));
            testGrouping.dependsOn(zipTask);
        }

        private List<TaskProvider<? extends Task>> generatePreAllocateAndDeAllocateTasksForGrouping(Project project, ParallelTestGroup testGrouping) {
            PodAllocator allocator = new PodAllocator(project.getLogger());
            TaskProvider<Task> preAllocateTask = project.getRootProject().getTasks().register("preAllocateFor" + capitalize(testGrouping.getName()), task -> {
                task.setGroup(GRADLE_GROUP);
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

            TaskProvider<Task> deAllocateTask = project.getRootProject().getTasks().register("deAllocateFor" + capitalize(testGrouping.getName()), task -> {
                task.setGroup(GRADLE_GROUP);
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

        private void generateParallelTestingTask(Project projectContainingTask, Test task) {
            String taskName = task.getName();
            String capitalizedTaskName = capitalize(taskName);

            projectContainingTask.getTasks().register("parallel" + capitalizedTaskName, KubesTest.class, kubesTest -> {
                kubesTest.setGroup(GRADLE_GROUP + " Parallel Test Tasks");
                if (StringUtils.isEmpty(tagToUseForRunningTests)) {
                    kubesTest.dependsOn(imagePushTask);
                }
                kubesTest.printOutput = true;
                kubesTest.fullTaskToExecutePath = task.getPath();
                kubesTest.taskToExecuteName = taskName;
                kubesTest.doFirst(task1 -> {
                    ((KubesTest) task1).dockerTag = !StringUtils.isEmpty(tagToUseForRunningTests) ? ImageBuilding.registryName + ":" + tagToUseForRunningTests :
                            (imagePushTask.getImageName().get() + ":" + imagePushTask.getTag().get());
                });

                projectContainingTask.getLogger().info("Created task: " + kubesTest.getName() + " to enable testing on kubenetes for task: " + task.getName());
            });
        }
    }

    @Override
    public void apply(@NotNull Project project) {
        if (System.getProperty("kubenetize") != null) {
            Properties.setRootProjectType(project.getRootProject().getName());

            Integer forks = getPropertyAsInt(project, "dockerForks", 1);

            ensureImagePluginIsApplied(project);
            TaskProvider<BucketingAllocatorTask> globalAllocator = project.getTasks().register("bucketingAllocator", BucketingAllocatorTask.class, forks);
            globalAllocator.configure(task -> {
                task.setGroup(GRADLE_GROUP);
                task.setDescription("Allocates tests to buckets");
            });

            Set<String> requestedTaskNames = new HashSet<>(project.getGradle().getStartParameter().getTaskNames());
            List<Task> requestedTasks = requestedTaskNames.stream().map(it -> project.getTasks().findByPath(it)).collect(Collectors.toList());

            ImageBuilding imagePlugin = project.getPlugins().getPlugin(ImageBuilding.class);
            TestingGroupData testingGroupData = new TestingGroupData(project, imagePlugin, requestedTaskNames);

            //in each subproject
            //1. add the task to determine all tests within the module and register this as a source to the global allocator
            //2. modify the underlying testing task to use the output of the global allocator to include a subset of tests for each fork
            //3. KubesTest will invoke these test tasks in a parallel fashion on a remote k8s cluster
            //4. after each completed test write its name to a file to keep track of what finished for restart purposes
            if (project.getSubprojects().size() != 0) {
                project.getSubprojects()
                    .forEach(subproject -> generateTasksForProject(subproject, testingGroupData, globalAllocator, requestedTasks));
            } else {
                generateTasksForProject(project, testingGroupData, globalAllocator, requestedTasks);
            }

            //first step is to create a single task which will invoke all the submodule tasks for each grouping
            //ie allParallelTest will invoke [node:test, core:test, client:rpc:test ... etc]
            //ie allIntegrationTest will invoke [node:integrationTest, core:integrationTest, client:rpc:integrationTest ... etc]
            //ie allUnitAndIntegrationTest will invoke [node:integrationTest, node:test, core:integrationTest, core:test, client:rpc:test , client:rpc:integrationTest ... etc]
            TaskCollection<ParallelTestGroup> userGroups = project.getTasks().withType(ParallelTestGroup.class);
            userGroups.configureEach(testingGroupData::addTestGroup);

            project.afterEvaluate(testingGroupData::configure);
        }

        //  Added only so that we can manually run zipTask on the command line as a test.
        TestDurationArtifacts.createZipTask(project.getRootProject(), "zipTask", null)
                .configure(t -> t.setDescription("Zip task that can be run locally for testing"));
    }

    private void generateTasksForProject(
        Project project,
        TestingGroupData testingGroupData,
        TaskProvider<BucketingAllocatorTask> globalAllocator,
        List<Task> requestedTasks
    ) {
        project.getTasks().withType(Test.class).configureEach(test -> {
            test.getLogger().info("Evaluating " + test.getPath());
            if (requestedTasks.contains(test) && !test.hasProperty("ignoreForDistribution")) {
                test.getLogger().info("Modifying " + test.getPath());
                Task testListerTask = createTestListingTasks(test, project);
                globalAllocator.get().addSource((TestLister) testListerTask, test);
                modifyTestTaskForParallelExecution(project, test, globalAllocator);
            } else {
                test.getLogger().info("Skipping modification of " + test.getPath() + " as it\'s not scheduled for execution");
            }

            if (!test.hasProperty("ignoreForDistribution")) {
                //this is what enables execution of a single test suite - for example node:parallelTest would execute all unit tests in node, node:parallelIntegrationTest would do the same for integration tests
                testingGroupData.addTest(test);
            }
        });
    }

    private void modifyTestTaskForParallelExecution(Project subProject, Test task, TaskProvider<BucketingAllocatorTask> globalAllocator) {
        subProject.getLogger().info("modifying task: " + task.getPath() + " to depend on task " + globalAllocator.getName());
        File reportsDir = new File(new File(KubesTest.TEST_RUN_DIR, "test-reports"), subProject.getName() + "-" + task.getName());
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
                e.printStackTrace();
            }
            ((Test)task1).filter(testFilter -> {
                List<String> executedTests = null;
                try {
                    executedTests = Files.readAllLines(executedTestsFile.toPath());
                } catch (IOException e) {
                    e.printStackTrace();
                }
                //adding wildcard to each test so they match the ones in the includes list
                String.join("*", executedTests);
                Integer fork = getPropertyAsInt(subProject, "dockerFork", 0);
                task1.getLogger().info("requesting tests to include in testing task " + task1.getPath() + " (idx: " + fork);
                List<String> includes = globalAllocator.get().getTestIncludesForForkAndTestTask(fork, (Test) task1);
                task1.getLogger().info("got " + includes.size() + " tests to include into testing task " + task1.getPath());
                task1.getLogger().info("INCLUDE: " + includes.toString());
                task1.getLogger().info("got " + executedTests.size() + " tests to exclude from testing task " + task1.getPath());
                task1.getLogger().debug("EXCLUDE: " + executedTests.toString());

                if (includes.size() == 0) {
                    task1.getLogger().info("Disabling test execution for testing task " + task1.getPath());
                    testFilter.excludeTestsMatching("*");
                }

                List<String> intersection = new ArrayList<>();
                for (String test : executedTests) {
                    if (includes.contains(test)) {
                        intersection.add(test);
                    }
                }
                task1.getLogger().info("got " + intersection.size() + " tests in intersection");
                task1.getLogger().info("INTERSECTION: " + intersection.toString());
                includes.removeAll(intersection);

                intersection.forEach(exclude -> {
                    task1.getLogger().info("excluding: " + exclude + " for testing task " + task1.getPath());
                    testFilter.excludeTestsMatching(exclude);
                });
                includes.forEach(include -> {
                    task1.getLogger().info("including: " + include + " for testing task " + task1.getPath());
                    testFilter.includeTestsMatching(include);
                });
                testFilter.setFailOnNoMatchingTests(false);
            });
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
    }

    private static void ensureImagePluginIsApplied(Project project) {
        project.getPlugins().apply(ImageBuilding.class);
    }

    private Task createTestListingTasks(Test task, Project subProject) {
        String taskName = task.getName();
        String capitalizedTaskName = capitalize(taskName);
        //determine all the tests which are present in this test task.
        //this list will then be shared between the various worker forks
        ListTests createdListTask = subProject.getTasks().create("listTestsFor" + capitalizedTaskName, ListTests.class, listTask -> {
            listTask.setGroup(GRADLE_GROUP);
            //the convention is that a testing task is backed by a sourceSet with the same name
            listTask.dependsOn(subProject.getTasks().getByName(taskName + "Classes"));
            listTask.doFirst(task1 -> {
                //we want to set the test scanning classpath to only the output of the sourceSet - this prevents dependencies polluting the list
                ((ListTests) task1).scanClassPath = !task.getTestClassesDirs().isEmpty() ? task.getTestClassesDirs() : null;
            });

            listTask.getLogger().info("created task: " + listTask.getPath() + " in project: " + subProject + " it dependsOn: " + listTask.dependsOn());
        });

        //convenience task to utilize the output of the test listing task to display to local console, useful for debugging missing tests
        subProject.getTasks().register("printTestsFor" + capitalizedTaskName, printTask -> {
            printTask.setGroup(GRADLE_GROUP);
            printTask.dependsOn(createdListTask);
            printTask.doLast(task1 -> createdListTask.getTestsForFork(
                getPropertyAsInt(subProject, "dockerFork", 0),
                getPropertyAsInt(subProject, "dockerForks", 1),
                42).forEach(testName -> subProject.getLogger().info(testName))
            );

            printTask.getLogger().info("created task: " + printTask.getPath() + " in project: " + subProject + " it dependsOn: " + printTask.dependsOn());
        });

        return createdListTask;
    }

    private static String capitalize(final String input) {
        return input.substring(0, 1).toUpperCase() + input.substring(1);
    }
}