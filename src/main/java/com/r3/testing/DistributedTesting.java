package com.r3.testing;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.jetbrains.annotations.NotNull;

/**
 * This plugin is responsible for wiring together the various components of test task modification
 */
class DistributedTesting implements Plugin<Project> {

    public static final String GRADLE_GROUP = "Distributed Testing";

    @Override
    public void apply(Project project) {
        if (System.getProperty("kubenetize") != null) {
            Properties.setRootProjectType(project.getRootProject().getName());
            Kubernetizer.forProject(DistributedTestingProject.forProject(project)).kubernetize();
        }

        //  Added only so that we can manually run zipTask on the command line as a test.
        TestDurationArtifacts.createZipTask(project.getRootProject(), "zipTask", null)
                .setDescription("Zip task that can be run locally for testing");
    }
}
