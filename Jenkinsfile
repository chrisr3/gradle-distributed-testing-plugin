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
        stage('Distributed Testing Plugin Build') {
            steps {
                withCredentials([string(credentialsId: 'container_reg_passwd', variable: 'DOCKER_PUSH_PWD')]) {
                    sh "./gradlew clean build --stacktrace"
                }
            }
        }
    }

    post {
        always {
            junit '**/build/test-results-xml/**/*.xml'
        }
        cleanup {
            deleteDir() /* clean up our workspace */
        }
    }
}