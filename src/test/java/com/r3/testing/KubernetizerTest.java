package com.r3.testing;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.testfixtures.ProjectBuilder;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class KubernetizerTest {

    @Test
    public void projectWithTest() {
        Project testProject = ProjectBuilder.builder().build();

        createTestTask(testProject);
        kubernetize(testProject, "test");

        assertThat(getTaskNames(testProject)).contains(
                ":parallelTest",
                ":printTestsForTest",
                ":listTestsForTest");
    }

    @Test
    public void projectWithSubProjectsWithTest() {
        Project testProject = ProjectBuilder.builder().build();
        Project subProjectA = ProjectBuilder.builder().withParent(testProject).withName("subProjectA").build();
        Project subProjectB = ProjectBuilder.builder().withParent(testProject).withName("subProjectB").build();

        createTestTask(subProjectA);
        createTestTask(subProjectB);

        kubernetize(testProject, "subProjectA:test", "subProjectB:test");

        assertThat(getTaskNames(testProject)).contains(
                ":subProjectA:parallelTest",
                ":subProjectA:printTestsForTest",
                ":subProjectA:listTestsForTest",
                ":subProjectB:parallelTest",
                ":subProjectB:printTestsForTest",
                ":subProjectB:listTestsForTest");
    }

    @Test
    public void nonRequestedTasksDoNotHaveTestsListed() {
        Project testProject = ProjectBuilder.builder().build();
        Project subProjectA = ProjectBuilder.builder().withParent(testProject).withName("subProjectA").build();
        Project subProjectB = ProjectBuilder.builder().withParent(testProject).withName("subProjectB").build();

        createTestTask(subProjectA);
        createTestTask(subProjectB);

        kubernetize(testProject, "subProjectA:test");

        assertThat(getTaskNames(testProject)).contains(
                ":subProjectA:parallelTest",
                ":subProjectA:printTestsForTest",
                ":subProjectA:listTestsForTest",
                ":subProjectB:parallelTest").doesNotContain(
                ":subProjectB:printTestsForTest",
                ":subProjectB:listTestsForTest");
    }

    private void createTestTask(Project testProject) {
        testProject.getTasks().create("test", org.gradle.api.tasks.testing.Test.class, test -> test.setGroup("test"));
        testProject.getTasks().create("testClasses", org.gradle.api.tasks.compile.JavaCompile.class, test -> test.setGroup("test"));
    }

    private void kubernetize(Project testProject, String...requestedTasks) {
        testProject.getGradle().getStartParameter().setTaskNames(Arrays.asList(requestedTasks));

        Kubernetizer.configuredWith(DistributedTestingConfiguration.fromSystem()).kubernetize(testProject);
    }

    @NotNull
    private List<String> getTaskNames(Project testProject) {
        return Stream.concat(testProject.getTasks().stream(),
                testProject.getSubprojects().stream().flatMap(s -> s.getTasks().stream()))
                .map(Task::getPath).collect(Collectors.toList());
    }
}
