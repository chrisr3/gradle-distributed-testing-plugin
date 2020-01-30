package com.r3.testing;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class KubernetizerTest {

    @Test
    public void projectWithTest() {
        Project testProject = ProjectBuilder.builder().build();

        testProject.getTasks().create("test", org.gradle.api.tasks.testing.Test.class, test -> test.setGroup("test"));
        testProject.getTasks().create("testClasses", org.gradle.api.tasks.testing.Test.class, test -> test.setGroup("test"));
        testProject.getGradle().getStartParameter().setTaskNames(Arrays.asList("test"));

        Kubernetizer.configuredWith(DistributedTestingConfiguration.fromSystem()).kubernetize(testProject);

        List<String> resultingTaskNames = testProject.getTasks().stream().map(Task::getName).collect(Collectors.toList());

        assertThat(resultingTaskNames).contains("parallelTest", "parallelTestClasses", "printTestsForTest", "listTestsForTest");
    }
}
