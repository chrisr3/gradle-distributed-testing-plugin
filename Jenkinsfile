@Library('existing-build-control')
import static com.r3.build.BuildControl.killAllExistingBuildsForJob

killAllExistingBuildsForJob(env.JOB_NAME, env.BUILD_NUMBER.toInteger())

pipeline {
    agent { label 'local-k8s' }
    options { timestamps() }

    environment {
            DOCKER_TAG_TO_USE = "${env.GIT_COMMIT.subSequence(0, 8)}"
            EXECUTOR_NUMBER = "${env.EXECUTOR_NUMBER}"
            BUILD_ID = "${env.BUILD_ID}-${env.JOB_NAME}"
            ARTIFACTORY_CREDENTIALS = credentials('artifactory-credentials')
        }

    stages {
        stage('Gradle Distributed Testing Plugin Pull Request - Generate Build Image') {
            steps {
                withCredentials([string(credentialsId: 'container_reg_passwd', variable: 'DOCKER_PUSH_PWD')]) {
                    sh "./gradlew " +
                            "-Dkubenetize=true " +
                            "-Ddocker.push.password=\"\${DOCKER_PUSH_PWD}\" " +
                            "-Ddocker.work.dir=\"/tmp/\${EXECUTOR_NUMBER}\" " +
                            "-Ddocker.build.tag=\"\${DOCKER_TAG_TO_USE}\" " +
                            "-Ddocker.build.image.arg.testArg=\"true\" " +
                            "-Ddocker.container.env.parameter.testParam=\"true\" " +
                            "-Ddocker.build.image.arg.testArg2=\"true\" " +
                            "-Ddocker.container.env.parameter.testParam2=\"true\"" +
                            " clean pushBuildImage --stacktrace --info"
                }
                sh "kubectl auth can-i get pods"
            }
        }
        stage('Gradle Distributed Testing Plugin Pull Request - Run Tests') {
            steps {
                sh "./gradlew " +
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