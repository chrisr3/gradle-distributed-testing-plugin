package com.r3.testing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

class DistributedTestingPluginTest {
    private GradleProject testProject;

    @BeforeEach
    void setup(@TempDir Path testProjectDir) throws IOException {
        testProject = new GradleProject(testProjectDir, "distributed-testing")
            .build("zipCsvFilesAndUploadForZipTask");
    }

    @Test
    void testPlugin() {
        assertThat(testProject.getOutcomeOf("zipCsvFilesAndUploadForZipTask")).isEqualTo(SUCCESS);
        assertThat(testProject.getOutcomeOf("createCsvFromXmlForZipTask")).isEqualTo(SUCCESS);
    }
}
