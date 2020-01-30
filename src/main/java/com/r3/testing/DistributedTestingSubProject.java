package com.r3.testing;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.testing.Test;

import java.io.File;
import java.util.function.Consumer;

public final class DistributedTestingSubProject {

    public static DistributedTestingSubProject forSubProject(Project subProject) {
        return new DistributedTestingSubProject(subProject);
    }

    private final Project subProject;

    private DistributedTestingSubProject(Project subProject) {
        this.subProject = subProject;
    }

    public void logInfo(String info) {
        subProject.getLogger().info(info);
    }

    public void logDebug(String debug) {
        subProject.getLogger().debug(debug);
    }

    public int getPropertyAsInt(String propertyName, int defaultValue) {
        return subProject.hasProperty(propertyName) ? Integer.parseInt(subProject.property(propertyName).toString()) : defaultValue;
    }

    public void traverseTestTasks(Consumer<Test> consumer) {
        subProject.getTasks().withType(Test.class, consumer::accept);
    }

    private <T extends Task> T createTask(String prefix, Task task, Class<T> taskType, Consumer<T> configure) {
        return subProject.getTasks().create(newTaskName(prefix, task), taskType, configure::accept);
    }

    public ListTests createListTestsTaskFor(Test task, Consumer<ListTests> configure) {
        return createTask("listTestsFor", task, ListTests.class, configure);
    }

    public Task createPrintTaskFor(Test task, Consumer<Task> configure) {
        return createTask("printTestsFor", task, Task.class, configure);
    }

    public Task getClassesTaskFor(Test task) {
        return subProject.getTasks().getByName(task.getName() + "Classes");
    }

    public File createReportsDirFor(Test task) {
        return new File(new File(KubesTest.TEST_RUN_DIR, "test-reports"), subProject.getName() + "-" + task.getName());
    }

    public KubesTest createKubesTestTaskFor(Test task, Consumer<KubesTest> configure) {
        return createTask("parallel", task, KubesTest.class, configure);
    }

    private String newTaskName(String prefix, Task task) {
        final String taskName = task.getName();
        return prefix + (taskName.substring(0, 1).toUpperCase() + taskName.substring(1));
    }

    @Override
    public String toString() {
        return subProject.toString();
    }
}
