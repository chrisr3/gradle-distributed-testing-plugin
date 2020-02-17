# Gradle Distributed Testing Plugin

The Gradle Distributed Testing Plugin enables distributed testing using Kubernetes to improve build times. The entire set of tests is divided into a number of buckets, approximately evening out the duration between them, and are subsequently executed in a distributed fashion on a matching number of pods. In this way, it is possible to "parallelise" test execution.

## Usage
To install the plugin, put the following in the host project root `build.gradle`:
```
import com.r3.testing.DistributeTestsBy
import com.r3.testing.ParallelTestGroup

buildscript {
	dependencies {
		classpath group: "com.r3.testing", name: "gradle-distributed-testing-plugin", version: "1.2-LOCAL-K8S-SHARED-CACHE-SNAPSHOT", changing: true
        ...
	}
}

apply plugin: 'com.r3.testing.distributed-testing'
apply plugin: 'com.r3.testing.image-building'

```

Additionally, test distributed test tasks must be configured in the host project root `build.gradle`.

Example:

```
task allPostgresDistributedDatabaseIntegrationTest(type: ParallelTestGroup) {
    testGroups "integrationTest"
    numberOfShards 10
    streamOutput false
    coresPerFork 6
    memoryInGbPerFork 12
    distribute DistributeTestsBy.CLASS
    sidecarImage "postgres:11"
    additionalArgs "-Dcustom.databaseProvider=integration-postgres",
            "-Dtest.db.script.dir=database-scripts/postgres",
            "-Dtest.db.admin.user=postgres",
            "-Dtest.db.admin.password=postgres",
            "-Dcorda.dataSourceProperties.dataSource.url=jdbc:postgresql://localhost:5432/postgres"
    nodeTaints "big"
}
```

| Property | Type | Description |
|----------|------|-------------|
| `testGroups` | List\<String> | Name of Gradle project test group e.g. `test` `integrationTest` `smokeTest` |
| `numberOfShards` | int | The number of test buckets / k8s pods |
| `streamOutput` | boolean | When this is false, only containers with "failed" exit codes will be printed to stdout |
| `coresPerFork` | int | Minimum required number of cores on AKS node |
| `memoryInGbPerFork` | int | Minimum required amount of memory on AKS node |
| `podLogLevel` | PodLogLevel | Enumerated: QUIET, WARN, INFO, DEBUG |
| `distribute` | DistributedTestsBy | Enumerated: CLASS, METHOD |
| `nodeTaints` | List\<String> | Tolerated node taints | 

## Development

To test changes made to the plugin, the JAR can be published to the local Maven repository via `./gradlew publishToMavenLocal`. Subsequently `mavenLocal()` should be added as the first entry in the host project root `build.gradle` `repositories` block, to ensure that the newly published JAR is used to resolve the dependency. The project can then be built normally.

Distributed test tasks can be tested by executing `./gradlew allExampleDistributedIntegrationTest -Dkubenetize -Ddocker.push.password=container-registry-password`. Either Minikube or another specified k8s cluster can be used to run the pods. e.g. [Connect to AKS](https://docs.microsoft.com/en-us/azure/aks/kubernetes-walkthrough#connect-to-the-cluster) and set `kubectl config use-context [your-aks-cluster-name]`.

## Tips
It can be useful to alter the plugin to only run a single test to get rapid feedback during development. This can be achieved by replacing the `fullTaskToExecutePath` in plugin `KubesTest` with e.g. `:integrationTest:someModule:someTest.
Additionally, it can be useful to set the `numberOfShards` to 1 on the distributed test task to run a single pod.