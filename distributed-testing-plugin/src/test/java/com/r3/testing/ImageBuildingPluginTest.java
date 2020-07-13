package com.r3.testing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE;

class ImageBuildingPluginTest {
    private GradleProject testProject;

    @BeforeEach
    void setup(@TempDir Path testProjectDir) throws IOException {
        testProject = new GradleProject(testProjectDir, "image-building")
            .buildAndFail("pushBuildImage");
    }

    @Test
    void testPlugin() {
        assertThat(testProject.getOutcomeOf("clean")).isEqualTo(UP_TO_DATE);
        assertThat(testProject.getOutcomeOf("pullBaseImage")).isEqualTo(FAILED);
        assertThat(testProject.getOutcomeOf("buildDockerImageFromSource")).isNull();
        assertThat(testProject.getOutcomeOf("createBuildContainer")).isNull();
        assertThat(testProject.getOutcomeOf("startBuildContainer")).isNull();
        assertThat(testProject.getOutcomeOf("logBuildContainer")).isNull();
        assertThat(testProject.getOutcomeOf("waitForBuildContainer")).isNull();
        assertThat(testProject.getOutcomeOf("commitBuildImageResult")).isNull();
        assertThat(testProject.getOutcomeOf("tagBuildImageResult")).isNull();
        assertThat(testProject.getOutcomeOf("pushBuildImage")).isNull();
    }
}
