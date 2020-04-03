# __Distributed testing plugin__

This plugin enables users to run their test sets in a distributed fashion minimal configuration, 
using established tools like Docker and Kubernetes, all within the established Corda Jenkins CI 
Pipeline.

### Setup Guide

If you haven't already, add the `corda-dependencies-dev` repository to your `build.gradle` file
```groovy 
maven {
    url "https://software.r3.com/artifactory/corda-dependencies-dev"
}
```
And include the plugin and snakeyaml as a dependency
```groovy
classpath group: "com.r3.testing", name: "gradle-distributed-testing-plugin", version: "1.3-SNAPSHOT", changing: true
classpath 'org.yaml:snakeyaml:1.23'
```

Once that's done, you will need to apply the two components that the plugin comes with for it to work.
The image building and distributed testing components.
```groovy
apply plugin: "com.r3.testing.image-building"
apply plugin: 'com.r3.testing.distributed-testing'
``` 
After this we can move to creating and configuring the parallel testing tasks 
The fields we need to consider are: 
- the logging level of each pods (INFO, DEBUG, etc)
- the test groups the task will run
- the infrastructure profile (more description below)
- the distribution method (either by CLASS or by METHOD). By METHOD will generally give better 
distribution in cases where you have a class with a lot of tests that take a long time as each method 
will get distributed to a different pod
```groovy
ext.generalPurpose = new Yaml().
    loadAs(new URL("https://raw.githubusercontent.com/corda/infrastructure-profiles/master/generalPurpose.yml").
    newInputStream(), InfrastructureProfile.class)

task allParallelTests(type: ParallelTestGroup) {
    podLogLevel PodLogLevel.INFO
    testGroups "test", "myCustomTestGroup"
    profile generalPurpose
    distribute DistributeTestsBy.CLASS
}
```
In terms of profile, you can make use of the general purpose one offered by the Infrastructure team 
as we will make sure that it will work with our current cloud infrastructure. But if you're a more savvy
user you can define one locally and use that instead. Here is the profile format:
```groovy
numberOfShards: 10
streamOutput: false
coresPerFork: 2
memoryInGbPerFork: 12
nodeTaints: ["small"]
``` 
- Number of shards defines how many pods your tests will run across. 
- Stream output enables pod log streaming to the main log file. Be warned as the log might become 
unreadable depending on how many pods stream their output. 
- Cores per fork is the number of cpus allocated to the each pod. 
- Same for memory. 
- Node taints is a special feature that allows you to specify a particular resource pool on the cloud 
from which these pods will be created. Do consult with the Infrastructure team in case you have 
different requirements

Now that all pieces are in place we can move to creating our Jenkinsfile. Here is an example:
```groovy
@Library('existing-build-control')
import static com.r3.build.BuildControl.killAllExistingBuildsForJob

killAllExistingBuildsForJob(env.JOB_NAME, env.BUILD_NUMBER.toInteger())

pipeline {
    agent { label 'k8s' }
    options { timestamps() }

    environment {
            DOCKER_TAG_TO_USE = "${env.GIT_COMMIT.subSequence(0, 8)}"
            EXECUTOR_NUMBER = "${env.EXECUTOR_NUMBER}"
            BUILD_ID = "${env.BUILD_ID}-${env.JOB_NAME}"
            ARTIFACTORY_CREDENTIALS = credentials('artifactory-credentials')
        }

    stages {
        stage('Generate Build Image') {
            steps {
                withCredentials([string(credentialsId: 'container_reg_passwd', variable: 'DOCKER_PUSH_PWD')]) {
                    sh "./gradlew " +
                            "-Dkubenetize=true " +
                            "-Ddocker.push.password=\"\${DOCKER_PUSH_PWD}\" " +
                            "-Ddocker.work.dir=\"/tmp/\${EXECUTOR_NUMBER}\" " +
                            "-Ddocker.build.tag=\"\${DOCKER_TAG_TO_USE}\" " +
                            "-Ddocker.build.image.arg.myBuildArg=\"true\" " +
                            "-Ddocker.container.env.parameter.myEnvVariable=\"true\" " +
                            "-Ddocker.build.image.arg.myBuildArg2=\"true\" " +
                            "-Ddocker.container.env.parameter.myEnvVariable2=\"true\"" +
                            " clean pushBuildImage --stacktrace --info"
                }
                sh "kubectl auth can-i get pods"
            }
        }
        stage('Run Tests') {
            steps {
                sh "./gradlew " +
                        "-DbuildId=\"\${BUILD_ID}\" " +
                        "-Dkubenetize=true " +
                        "-Ddocker.run.tag=\"\${DOCKER_TAG_TO_USE}\" " +
                        "-Dartifactory.username=\"\${ARTIFACTORY_CREDENTIALS_USR}\" " +
                        "-Dartifactory.password=\"\${ARTIFACTORY_CREDENTIALS_PSW}\" " +
                        "-Dgit.branch=\"\${GIT_BRANCH}\" " +
                        "-Dgit.target.branch=\"\${CHANGE_TARGET}\" " +
                        " allParallelTest --stacktrace"
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: '**/pod-logs/**/*.log', fingerprint: false
            junit '**/build/test-results-xml/**/*.xml'
        }
        cleanup {
            deleteDir() /* clean up our workspace */
        }
    }
}
```
The main things you will be concerned with changing in this example are the two stages. 

For image building, if our image requires any form of build argument or environment variable, those 
can be defined using the prefixes `docker.build.image.arg.` and `docker.container.env.parameter.` 
followed by the name and value of the argument/variable. You can define as many as you need.

As for the testing stage, all we have to change is the name of the task being invoked at the end to 
be the one we created and configured previously, in our case `allParallelTest`. 

But if we don't mind some parallelception we can parallelise two or more tasks using Jenkins parallel 
stages. Here's an example:
```groovy
stage('Run Tests') {
    parallel {
        stage('Integration Tests') {
            steps {
                sh "./gradlew --no-daemon " +
                        "-DbuildId=\"\${BUILD_ID}\" " +
                        "-Dkubenetize=true " +
                        "-Ddocker.run.tag=\"\${DOCKER_TAG_TO_USE}\" " +
                        "-Dartifactory.username=\"\${ARTIFACTORY_CREDENTIALS_USR}\" " +
                        "-Dartifactory.password=\"\${ARTIFACTORY_CREDENTIALS_PSW}\" " +
                        "-Dgit.branch=\"\${GIT_BRANCH}\" " +
                        "-Dgit.target.branch=\"\${CHANGE_TARGET}\" " +
                        " allParallelIntegrationTest  --stacktrace"
            }
        }
        stage('Unit Tests') {
            steps {
                sh "./gradlew --no-daemon " +
                        "-DbuildId=\"\${BUILD_ID}\" " +
                        "-Dkubenetize=true " +
                        "-Ddocker.run.tag=\"\${DOCKER_TAG_TO_USE}\" " +
                        "-Dartifactory.username=\"\${ARTIFACTORY_CREDENTIALS_USR}\" " +
                        "-Dartifactory.password=\"\${ARTIFACTORY_CREDENTIALS_PSW}\" " +
                        "-Dgit.branch=\"\${GIT_BRANCH}\" " +
                        "-Dgit.target.branch=\"\${CHANGE_TARGET}\" " +
                        " allParallelUnitTest --stacktrace"
            }
        }
    }
}
```
This enables us to run unit and integration tests in parallel while also parallelising the actual 
tests themselves.

And that's all you need. Happy hunting