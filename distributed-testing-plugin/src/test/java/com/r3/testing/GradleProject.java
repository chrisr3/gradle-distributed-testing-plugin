package com.r3.testing;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

@SuppressWarnings({"WeakerAccess", "unused"})
public class GradleProject {
    private static final String TEST_GRADLE_USER_HOME = System.getProperty("test.gradle.user.home", "");

    private final Path projectDir;
    private final String name;

    private BuildResult result;
    private String output;

    public GradleProject(Path projectDir, String name) {
        this.projectDir = projectDir;
        this.name = name;
        this.output = "";
    }

    public GradleProject withResource(String resourceName) throws IOException {
        installResource(projectDir, resourceName);
        return this;
    }

    public String getOutput() {
        return output;
    }

    public TaskOutcome getOutcomeOf(String taskName) {
        BuildTask task = result.task(":" + taskName);
        return (task == null) ? null : task.getOutcome();
    }

    private void configureGradle(Function<GradleRunner, BuildResult> builder, String[] args) throws IOException {
        installResource(projectDir, name + "/build.gradle");
        installResource(projectDir, "repositories.gradle");
        installResource(projectDir, "gradle.properties");
        if (!installResource(projectDir, name + "/settings.gradle")) {
            installResource(projectDir, "settings.gradle");
        }

        GradleRunner runner = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments(getGradleArgsForTask(args))
            .withPluginClasspath()
            .withDebug(true);
        result = builder.apply(runner);
        output = result.getOutput();
        System.out.println(output);
    }

    public GradleProject build(String... args) throws IOException {
        configureGradle(GradleRunner::build, args);
        return this;
    }

    public GradleProject buildAndFail(String... args) throws IOException {
        configureGradle(GradleRunner::buildAndFail, args);
        return this;
    }

    @Nonnull
    private List<String> getGradleArgsForTask(@Nonnull String... args) {
        List<String> allArgs = new ArrayList<>(args.length + 4);
        Collections.addAll(allArgs, "--info", "--stacktrace");
        if (!TEST_GRADLE_USER_HOME.isEmpty()) {
            Collections.addAll(allArgs, "-g", TEST_GRADLE_USER_HOME);
        }
        Collections.addAll(allArgs, args);
        return allArgs;
    }

    private static boolean installResource(@Nonnull Path folder, @Nonnull String resourceName) throws IOException {
        Path buildFile = folder.resolve(resourceName.substring(1 + resourceName.lastIndexOf('/')));
        return copyResourceTo(resourceName, buildFile) >= 0;
    }

    private static long copyResourceTo(String resourceName, Path target) throws IOException {
        try (InputStream input = GradleProject.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                return -1;
            }
            return Files.copy(input, target, REPLACE_EXISTING);
        }
    }
}
