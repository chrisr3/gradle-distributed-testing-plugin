package com.r3.testing;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.testing.Test;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class DistributedTestingProject {

    public static DistributedTestingProject forProject(Project project) {
        Set<String> requestedTaskNames = new HashSet<>(project.getGradle().getStartParameter().getTaskNames());
        List<Task> requestedTasks = requestedTaskNames.stream()
                .map(it -> project.getTasks().findByPath(it)).collect(Collectors.toList());
        return new DistributedTestingProject(project, requestedTaskNames, requestedTasks);
    }

    private final Project project;
    private final Set<String> requestedTaskNames;
    private final List<Task> requestedTasks;

    public DistributedTestingProject(Project project, Set<String> requestedTaskNames, List<Task> requestedTasks) {
        this.project = project;
        this.requestedTaskNames = requestedTaskNames;
        this.requestedTasks = requestedTasks;
    }

    public int getPropertyAsInt(String propertyName, int defaultValue) {
        return project.hasProperty(propertyName) ? Integer.parseInt(project.property(propertyName).toString()) : defaultValue;
    }

    public boolean isTaskRequested(String taskName) {
        return requestedTaskNames.contains(taskName);
    }

    public ImageBuilding getImageBuildingPlugin() {
        project.getPlugins().apply(ImageBuilding.class);
        return project.getPlugins().getPlugin(ImageBuilding.class);
    }

    public BucketingAllocatorTask createGlobalAllocator() {
        int forks = getPropertyAsInt("dockerForks", 1);
        BucketingAllocatorTask globalAllocator = project.getTasks().create("bucketingAllocator", BucketingAllocatorTask.class, forks);
        globalAllocator.setGroup(DistributedTesting.GRADLE_GROUP);
        globalAllocator.setDescription("Allocates tests to buckets");
        return globalAllocator;
    }

    private boolean hasSubProjects() {
        return !project.getSubprojects().isEmpty();
    }

    public void traverseRequestedTasks(TestDistributor testDistributor) {
        if (hasSubProjects()) {
            project.getSubprojects().forEach(subProject -> testDistributor.generateDistributedTestTasksFor(
                    DistributedTestingSubProject.forSubProject(subProject),
                    requestedTasks));
        } else {
            testDistributor.generateDistributedTestTasksFor(
                    DistributedTestingSubProject.forSubProject(project),
                    requestedTasks);
        }
    }

    private Map<String, List<Test>> getTestsGroupedByType() {
        return hasSubProjects()
                ? getSubProjectTasksGroupedByType()
                : getRootProjectTasksGroupedByType();
    }

    private Map<String, List<Test>> getRootProjectTasksGroupedByType() {
        return project.getAllTasks(false).values().stream()
                .flatMap(Collection::stream)
                .filter(task -> task instanceof Test)
                .map(Test.class::cast)
                .collect(Collectors.groupingBy(Task::getName));
    }

    private Map<String, List<Test>> getSubProjectTasksGroupedByType() {
        return project.getSubprojects().stream()
                .flatMap(prj ->
                        prj.getAllTasks(false).values().stream()
                                .flatMap(Collection::stream)
                                .filter(task -> task instanceof Test)
                                .map(Test.class::cast))
                .collect(Collectors.groupingBy(Task::getName));
    }

    public void traverseParallelTestGroups(ParallelTestGroupConfigurer configurer) {
        Map<String, List<Test>> testsGroupedByType = getTestsGroupedByType();

        Set<ParallelTestGroup> parallelTestGroups = new HashSet<>(project.getTasks().withType(ParallelTestGroup.class));
        parallelTestGroups.forEach(parallelTestGroup ->
                configurer.configureParallelTestGroup(
                    parallelTestGroup,
                    parallelTestGroup.getGroups().stream()
                            .flatMap(group -> testsGroupedByType.get(group).stream())
                            .collect(Collectors.toList())));
    }

    public PodAllocator createPodAllocator() {
        return new PodAllocator(project.getLogger());
    }

    private <T extends Task> T createTask(String prefix, Task task, Class<T> taskType, Consumer<T> configure) {
        return project.getRootProject().getTasks().create(newTaskName(prefix, task), taskType, configure::accept);
    }

    public Task createPreAllocateTaskFor(ParallelTestGroup testGroup, Consumer<Task> configure) {
        return createTask("preAllocateFor", testGroup, Task.class, configure);
    }

    public Task createDeallocateTaskFor(ParallelTestGroup testGroup, Consumer<Task> configure) {
        return createTask("deAllocateFor", testGroup, Task.class, configure);
    }

    public KubesTest createKubesTestFor(ParallelTestGroup testGroup, Consumer<KubesTest> configure) {
        return createTask("userDefined", testGroup, KubesTest.class, configure);
    }

    public KubesReporting createKubesReportingTaskFor(ParallelTestGroup testGroup, Consumer<KubesReporting> configure) {
        return createTask("userDefinedReports", testGroup, KubesReporting.class, configure);
    }

    public Task createZipTaskFor(ParallelTestGroup testGroup, KubesTest kubesTest) {
        return TestDurationArtifacts.createZipTask(project.getRootProject(), testGroup.getName(), kubesTest);
    }

    public File createUserDefinedReportsDirFor(ParallelTestGroup testGroup) {
        return new File(project.getRootProject().getBuildDir(), newTaskName("userDefinedReports", testGroup));
    }

    private String newTaskName(String prefix, Task task) {
        final String taskName = task.getName();
        return prefix + (taskName.substring(0, 1).toUpperCase() + taskName.substring(1));
    }

    public void logInfo(String s) {
        project.getLogger().info(s);
    }
}
