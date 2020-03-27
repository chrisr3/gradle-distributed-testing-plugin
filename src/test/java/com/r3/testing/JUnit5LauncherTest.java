package com.r3.testing;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.r3.testing.TestPlanUtils.getTestClasses;
import static com.r3.testing.TestPlanUtils.getTestMethods;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

public class JUnit5LauncherTest {

    private static TestPlan testPlan;

    @BeforeAll
    public static void beforeClass() {
        testPlan = LauncherFactory.create().discover(
                LauncherDiscoveryRequestBuilder.request().selectors(selectPackage("com.r3.testing")).build());
    }

    @Test
    void getTestMethodsFromTestPlan() {
        Set<String> expected = discoverTestMethodsUsingClassGraphStream();
        Set<String> testSet = getTestMethods(testPlan);
        assertThat(testSet).containsOnlyElementsOf(expected);
    }

    @Test
    void getTestClassesFromTestPlan() {
        Set<String> expected = discoverTestClassesWithClassGraphStream();
        Set<String> testSet = getTestClasses(testPlan);
        assertThat(testSet).containsOnlyElementsOf(expected);
    }

    private Set<String> discoverTestMethodsUsingClassGraphStream() {
        return getClassGraphStreamOfTestClasses()
                .map(classInfo -> classInfo.getMethodInfo()
                        .filter(methodInfo ->
                                methodInfo.hasAnnotation("org.junit.Test")
                                        || methodInfo.hasAnnotation("org.junit.jupiter.api.Test"))
                        .stream().map(methodInfo -> classInfo.getName() + "." + methodInfo.getName()))
                .flatMap(Function.identity())
                .collect(Collectors.toSet());
    }

    private Set<String> discoverTestClassesWithClassGraphStream() {
        return getClassGraphStreamOfTestClasses()
                            .map(ClassInfo::getName)
                            .collect(Collectors.toSet());
    }

    private Stream<ClassInfo> getClassGraphStreamOfTestClasses() {
        Stream<ClassInfo> junit4ClassGraphStream = getClassGraphStreamForAnnotation("org.junit.Test");
        Stream<ClassInfo> junit5ClassGraphStream = getClassGraphStreamForAnnotation("org.junit.jupiter.api.Test");
        return Stream.concat(junit4ClassGraphStream, junit5ClassGraphStream);
    }

    private Stream<ClassInfo> getClassGraphStreamForAnnotation(String annotation) {
        return new ClassGraph()
                .enableClassInfo()
                .enableMethodInfo()
                .ignoreClassVisibility()
                .ignoreMethodVisibility()
                .enableAnnotationInfo()
                .scan()
                .getClassesWithMethodAnnotation(annotation)
                .stream()
                .map(classInfo -> {
                    ClassInfoList returnList = new ClassInfoList();
                    returnList.add(classInfo);
                    returnList.addAll(classInfo.getSubclasses());
                    return returnList;
                })
                .flatMap(ClassInfoList::stream);
    }
}
