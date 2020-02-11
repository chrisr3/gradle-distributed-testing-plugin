package com.r3.testing;

import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.CloudException;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.sql.ServiceObjectiveName;
import com.microsoft.azure.management.sql.SqlDatabase;
import com.microsoft.rest.LogLevel;
import com.r3.testing.retry.Retry;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import okhttp3.Response;
import org.gradle.api.DefaultTask;
import org.gradle.api.InvalidUserCodeException;
import org.gradle.api.tasks.TaskAction;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class KubesTest extends DefaultTask {

    static final String TEST_RUN_DIR = "/test-runs";
    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    /**
     * Name of the k8s Secret object that holds the credentials to access the docker image registry
     */
    private static final String REGISTRY_CREDENTIALS_SECRET_NAME = "regcred";

    private static final int DEFAULT_K8S_TIMEOUT_VALUE_MILLIES = 60 * 1_000;
    private static final int DEFAULT_K8S_WEBSOCKET_TIMEOUT = DEFAULT_K8S_TIMEOUT_VALUE_MILLIES * 30;
    private static final int DEFAULT_POD_ALLOCATION_TIMEOUT = 60;

    String dockerTag;
    String fullTaskToExecutePath;
    String taskToExecuteName;
    String sidecarImage;
    Boolean printOutput = false;
    List<String> additionalArgs;
    List<String> taints = Collections.emptyList();

    Integer numberOfCoresPerFork = 4;
    Integer memoryGbPerFork = 6;
    public volatile List<File> testOutput = Collections.emptyList();
    public volatile List<KubePodResult> containerResults = Collections.emptyList();
    private final Set<String> remainingPods = Collections.synchronizedSet(new HashSet<>());

    public static String NAMESPACE = "thisisatest";

    int numberOfPods = 5;

    DistributeTestsBy distribution = DistributeTestsBy.METHOD;
    PodLogLevel podLogLevel = PodLogLevel.INFO;

    private static final String RESOURCE_GROUP = "build-k8s-infrastructure";
    private static final String CLIENT = System.getProperty("azure.client");
    private static final String TENANT = System.getProperty("azure.tenant");
    private static final String KEY = System.getProperty("azure.key");
    private static final String ASQL_SERVER = "eng-infra-test-db-server-01";
    private static final ApplicationTokenCredentials AZURE_CREDENTIALS = new ApplicationTokenCredentials(
            CLIENT, TENANT, KEY, AzureEnvironment.AZURE
    );

    @TaskAction
    public void runDistributedTests() {

        String buildId = System.getProperty("buildId", "0");
        String currentUser = System.getProperty("user.name", "UNKNOWN_USER");

        String stableRunId = rnd64Base36(new Random(buildId.hashCode() + currentUser.hashCode() + taskToExecuteName.hashCode()));
        String random = rnd64Base36(new Random());

        // Tear down any orphaned dbs from previous test run
        //tearDownOrphanedAzureSQLDbs();

        try (KubernetesClient client = getKubernetesClient()) {
            client.pods().inNamespace(NAMESPACE).list().getItems().forEach(podToDelete -> {
                if (podToDelete.getMetadata().getName().contains(stableRunId)) {
                    getProject().getLogger().lifecycle("deleting: " + podToDelete.getMetadata().getName());
                    deletePodAndWaitForDeletion(NAMESPACE, podToDelete.getMetadata().getName(), client);
                }
            });
        } catch (Exception ignored) {
            //it's possible that a pod is being deleted by the original build, this can lead to racey conditions
        }

        List<Future<KubePodResult>> futures = IntStream.range(0, numberOfPods).mapToObj(i -> {
            String podName = generatePodName(stableRunId, random, i);
            return submitBuild(NAMESPACE, numberOfPods, i, podName, printOutput, 3);
        }).collect(Collectors.toList());

        this.testOutput = Collections.synchronizedList(futures.stream().map(it -> {
            try {
                return it.get().getBinaryResults();
            } catch (InterruptedException | ExecutionException e) {
                throw new InvalidUserCodeException("Exception occurred ", e);
            }
        }).flatMap(Collection::stream).collect(Collectors.toList()));
        this.containerResults = futures.stream().map(it -> {
            try {
                return it.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new InvalidUserCodeException("Exception occurred ", e);
            }
        }).collect(Collectors.toList());

        // Tear down Azure SQL DBs (if any) from this test run
        tearDownAzureSQLDbs(stableRunId, random);
    }

    private void tearDownOrphanedAzureSQLDbs() {
        if (additionalArgs.stream().anyMatch(arg -> arg.contains("azure"))) {
            // get all of the live pod names
            try (KubernetesClient client = getKubernetesClient()) {
                List<String> podNames = client.pods().inNamespace(NAMESPACE).list().getItems().stream().map(pod -> pod.getMetadata().getName()).collect(Collectors.toList());
                try {
                    List<SqlDatabase> sqlDatabases = Azure.configure()
                            .withLogLevel(LogLevel.NONE)
                            .authenticate(AZURE_CREDENTIALS)
                            .withDefaultSubscription().sqlServers()
                            .getByResourceGroup(RESOURCE_GROUP, ASQL_SERVER)
                            .databases().list().stream()
                            .filter(db -> !db.name().contains("master")).collect(Collectors.toList());
                    for (SqlDatabase db : sqlDatabases) {
                        try {
                            db.delete();
                        } catch (Exception ignored) {
                            //it's possible that a db is being deleted by another build, this can lead to racey conditions
                        }
                    }
                } catch (CloudException e) {
                    getProject().getLogger().lifecycle("CloudException thrown when getting Azure client for tearing down orphaned Azure SQL databases.");
                    e.printStackTrace();
                } catch (IOException e) {
                    getProject().getLogger().lifecycle("IOException thrown when getting Azure client for tearing down orphaned Azure SQL databases.");
                    e.printStackTrace();
                }
            }
        }
    }

    private void tearDownAzureSQLDbs(String stableRunId, String random) {
        if (additionalArgs.stream().anyMatch(arg -> arg.contains("azure"))) {
            List<String> podNames = IntStream.range(0, numberOfPods).mapToObj(i -> generatePodName(stableRunId, random, i)).collect(Collectors.toList());
            String basePodName = podNames.get(0).substring(0, podNames.get(0).length() - 2);
            try {
                List<SqlDatabase> sqlDatabases = Azure.configure()
                        .withLogLevel(LogLevel.NONE)
                        .authenticate(AZURE_CREDENTIALS)
                        .withDefaultSubscription().sqlServers()
                        .getByResourceGroup(RESOURCE_GROUP, ASQL_SERVER)
                        .databases().list().stream()
                        .filter(db -> db.name().contains(basePodName)).collect(Collectors.toList());
                for (SqlDatabase db : sqlDatabases) {
                    try {
                        db.delete();
                    } catch (Exception ignored) {
                        //it's possible that a db is being deleted by another build, this can lead to racey conditions
                    }
                }
            } catch (CloudException e) {
                getProject().getLogger().lifecycle("CloudException thrown when getting Azure client for tearing down Azure SQL databases post-test.");
                e.printStackTrace();
            } catch (IOException e) {
                getProject().getLogger().lifecycle("IOException thrown when getting Azure client for tearing down Azure SQL databases post-test.");
                e.printStackTrace();
            }
        }
    }

    @NotNull
    private String generatePodName(String stableRunId, String random, int i) {
        int magicMaxLength = 63;
        String provisionalName = taskToExecuteName.toLowerCase() + "-" + stableRunId + "-" + random + "-" + i;
        //length = 100
        //100-63 = 37
        //subString(37, 100) -? string of 63 characters
        return provisionalName.substring(Math.max(provisionalName.length() - magicMaxLength, 0));
    }

    @NotNull
    private synchronized KubernetesClient getKubernetesClient() {

        try (RandomAccessFile file = new RandomAccessFile("/tmp/refresh.lock", "rw");
             FileChannel c = file.getChannel();
             FileLock lock = c.lock()) {

            getProject().getLogger().quiet("Invoking kubectl to attempt to refresh token");
            ProcessBuilder tokenRefreshCommand = new ProcessBuilder().command("kubectl", "auth", "can-i", "get", "pods");
            Process refreshProcess = tokenRefreshCommand.start();
            int resultCodeOfRefresh = refreshProcess.waitFor();
            getProject().getLogger().quiet("Completed Token refresh");

            if (resultCodeOfRefresh != 0) {
                throw new InvalidUserCodeException("Failed to invoke kubectl to refresh tokens");
            }

        } catch (InterruptedException | IOException e) {
            throw new InvalidUserCodeException("Exception occurred ", e);
        }

        io.fabric8.kubernetes.client.Config config = new io.fabric8.kubernetes.client.ConfigBuilder()
                .withConnectionTimeout(DEFAULT_K8S_TIMEOUT_VALUE_MILLIES)
                .withRequestTimeout(DEFAULT_K8S_TIMEOUT_VALUE_MILLIES)
                .withRollingTimeout(DEFAULT_K8S_TIMEOUT_VALUE_MILLIES)
                .withWebsocketTimeout(DEFAULT_K8S_WEBSOCKET_TIMEOUT)
                .withWebsocketPingInterval(DEFAULT_K8S_WEBSOCKET_TIMEOUT)
                .build();

        return new DefaultKubernetesClient(config);
    }

    private static String rnd64Base36(Random rnd) {
        return new BigInteger(64, rnd)
                .toString(36)
                .toLowerCase();
    }

    private CompletableFuture<KubePodResult> submitBuild(
            String namespace,
            int numberOfPods,
            int podIdx,
            String podName,
            boolean printOutput,
            int numberOfRetries
    ) {
        return CompletableFuture.supplyAsync(() -> {
            PersistentVolumeClaim pvc = createPvc(podName);
            return buildRunPodWithRetriesOrThrow(namespace, numberOfPods, podIdx, podName, printOutput, numberOfRetries, pvc);
        }, executorService);
    }

    private static void addShutdownHook(Runnable hook) {
        Runtime.getRuntime().addShutdownHook(new Thread(hook));
    }

    private PersistentVolumeClaim createPvc(String name) {
        PersistentVolumeClaim pvc;
        try (KubernetesClient client = getKubernetesClient()) {
            pvc = client.persistentVolumeClaims()
                    .inNamespace(NAMESPACE)
                    .createNew()
                    .editOrNewMetadata().withName(name).endMetadata()
                    .editOrNewSpec()
                    .withAccessModes("ReadWriteOnce")
                    .editOrNewResources().addToRequests("storage", new Quantity("100Mi")).endResources()
                    .withStorageClassName("testing-storage")
                    .endSpec()
                    .done();
        }

        addShutdownHook(() -> {
            try (KubernetesClient client = getKubernetesClient()) {
                System.out.println("Deleting PVC: " + pvc.getMetadata().getName());
                client.persistentVolumeClaims().delete(pvc);
            }
        });
        return pvc;
    }

    private KubePodResult buildRunPodWithRetriesOrThrow(
            String namespace,
            int numberOfPods,
            int podIdx,
            String podName,
            boolean printOutput,
            int numberOfRetries,
            PersistentVolumeClaim pvc) {
        addShutdownHook(() -> {
            System.out.println("deleting pod: " + podName);
            try (KubernetesClient client = getKubernetesClient()) {
                client.pods().inNamespace(namespace).withName(podName).delete();
            }
        });

        int podNumber = podIdx + 1;
        final AtomicInteger testRetries = new AtomicInteger(0);
        File podLogsDirectory = new File(getProject().getBuildDir(), "pod-logs");
        if (!podLogsDirectory.exists()) {
            podLogsDirectory.mkdirs();
        }
        File outputDir = new File(podLogsDirectory, taskToExecuteName);
        outputDir.mkdirs();
        File outputFile = new File(outputDir, "container-" + podIdx + ".log");
        try {
            // pods might die, so we retry
            return Retry.fixed(numberOfRetries, getProject().getLogger()).call(() -> {
                outputFile.createNewFile();
                // remove pod if exists
                Pod createdPod;
                try (KubernetesClient client = getKubernetesClient()) {
                    deletePodAndWaitForDeletion(namespace, podName, client);
                    getProject().getLogger().lifecycle("creating pod: " + podName);
                    synchronized (this) {
                        createdPod = client.pods().inNamespace(namespace).create(buildPodRequest(podName, pvc, sidecarImage != null, podIdx));
                        waitForPodToStart(createdPod);
                    }
                    remainingPods.add(podName);
                    getProject().getLogger().lifecycle("scheduled pod: " + podName);
                }

                attachStatusListenerToPod(createdPod);
                PipedOutputStream stdOutOs = new PipedOutputStream();
                PipedInputStream stdOutIs = new PipedInputStream(4096);
                ByteArrayOutputStream errChannelStream = new ByteArrayOutputStream();

                CompletableFuture<Integer> waiter = executeBuild(namespace, numberOfPods, podIdx, podName, podLogsDirectory, printOutput, stdOutOs, stdOutIs, errChannelStream, outputFile);
                int resCode = waiter.join();
                getProject().getLogger().lifecycle("build has ended on on pod " + podName + " (" + podNumber + "/" + numberOfPods + ") with result " + resCode + " , gathering results");
                Collection<File> binaryResults;
                //we don't retry on the final attempt as this will crash the build and some pods might not get to finish
                if (resCode != 0 && testRetries.getAndIncrement() < numberOfRetries - 1) {
                    downloadTestXmlFromPod(namespace, createdPod);
                    getProject().getLogger().lifecycle("There are test failures in this pod. Retrying failed tests!!!");
                    throw new InvalidUserCodeException("There are test failures in this pod");
                } else {
                    binaryResults = downloadTestXmlFromPod(namespace, createdPod);
                }

                getLogger().lifecycle("removing pod " + podName + " (" + podNumber + "/" + numberOfPods + ") after completed build");
                try (KubernetesClient client = getKubernetesClient()) {
                    deletePodAndWaitForDeletion(NAMESPACE, podName, client);
                    client.persistentVolumeClaims().delete(pvc);
                    synchronized (remainingPods) {
                        remainingPods.remove(podName);
                        getLogger().lifecycle("Remaining Pods: ");
                        remainingPods.forEach(pod -> getLogger().lifecycle("\t" + pod));
                    }
                }
                return new KubePodResult(podIdx, resCode, outputFile, binaryResults);
            });
        } catch (Retry.RetryException e) {
            getProject().getLogger().warn("Encountered an exception ");
            try (KubernetesClient client = getKubernetesClient()) {
                deletePodAndWaitForDeletion(NAMESPACE, podName, client);
                Pod reCreatedPod = getKubernetesClient().pods().inNamespace(namespace).create(buildPodRequest(podName, pvc, sidecarImage != null, podIdx));
                client.resource(reCreatedPod).waitUntilReady(10, TimeUnit.MINUTES);
                Collection<File> downloadedFiles = downloadTestXmlFromPod(namespace, reCreatedPod);
                deletePodAndWaitForDeletion(NAMESPACE, podName, client);
                client.persistentVolumeClaims().delete(pvc);
                return new KubePodResult(podIdx, 255, outputFile, downloadedFiles);
            } catch (InterruptedException ex) {
                getProject().getLogger().error("Failed to recreate pod " + podName + "for log and test xml collection");
            }

            throw new InvalidUserCodeException("Failed to build in pod " + podName + " (" + podNumber + "/" + numberOfPods + ") in " + numberOfRetries + " attempts", e);
        }
    }

    private void deletePodAndWaitForDeletion(String namespace, String podName, KubernetesClient client) {
        RetryablePodOperations oldPod = new RetryablePodOperations(client.pods().inNamespace(namespace).withName(podName));
        if (oldPod.get() != null) {
            getLogger().lifecycle("deleting pod: {}", podName);
            oldPod.delete();
            while (oldPod.get() != null) {
                getLogger().info("waiting for pod {} to be removed", podName);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }


    @NotNull
    private CompletableFuture<Integer> executeBuild(String namespace,
                                                    int numberOfPods,
                                                    int podIdx,
                                                    String podName,
                                                    File podLogsDirectory,
                                                    boolean printOutput,
                                                    PipedOutputStream stdOutOs,
                                                    PipedInputStream stdOutIs,
                                                    ByteArrayOutputStream errChannelStream,
                                                    File outputFileForPod) throws IOException {

        CompletableFuture<Integer> waiter = new CompletableFuture<>();

        KubernetesClient client = getKubernetesClient();
        ExecListener execListener = buildExecListenerForPod(podName, errChannelStream, waiter, client);
        stdOutIs.connect(stdOutOs);

        String[] buildCommand = getBuildCommand(numberOfPods, podIdx, podName);
        getProject().getLogger().quiet("About to execute " + Arrays.stream(buildCommand).reduce("", (s, s2) -> s + " " + s2) + " on pod " + podName);
        client.pods().inNamespace(namespace).withName(podName)
                .inContainer(podName)
                .writingOutput(stdOutOs)
                .writingErrorChannel(errChannelStream)
                .usingListener(execListener)
                .exec(buildCommand);

        startLogPumping(stdOutIs, podIdx, outputFileForPod, printOutput);
        return waiter;
    }

    private Pod buildPodRequest(String podName, PersistentVolumeClaim pvc, boolean withDb, int podIdx) {
        if (additionalArgs.stream().anyMatch(arg -> arg.contains("azure"))) {
            // Azure SQL
            setUpAzureSQLDbSchemaForPod(podName);
            return buildPodRequestWithOnlyWorkerNode(podName, pvc, podIdx);
        } else if (withDb) {
            // Other DBs
            return buildPodRequestWithWorkerNodeAndDbContainer(podName, pvc, podIdx);
        } else {
            // No DB / H2
            return buildPodRequestWithOnlyWorkerNode(podName, pvc, podIdx);
        }
    }

    private Pod buildPodRequestWithOnlyWorkerNode(String podName, PersistentVolumeClaim pvc, int podIdx) {
        return getBasePodDefinition(podName, pvc, podIdx)
                .addToRequests("cpu", new Quantity(numberOfCoresPerFork.toString()))
                .addToRequests("memory", new Quantity(memoryGbPerFork.toString()))
                .endResources()
                .addNewVolumeMount().withName("gradlecache").withMountPath("/tmp/gradle").endVolumeMount()
                .addNewVolumeMount().withName("testruns").withMountPath(TEST_RUN_DIR).endVolumeMount()
                .endContainer()
                .addNewImagePullSecret(REGISTRY_CREDENTIALS_SECRET_NAME)
                .withRestartPolicy("Never")
                .endSpec()
                .build();
    }

    private Pod buildPodRequestWithWorkerNodeAndDbContainer(String podName, PersistentVolumeClaim pvc, int podIdx) {
        return getBasePodDefinition(podName, pvc, podIdx)
                .addToRequests("cpu", new Quantity(Integer.valueOf(numberOfCoresPerFork - 1).toString()))
                .addToRequests("memory", new Quantity(Integer.valueOf(memoryGbPerFork - 1).toString() + "Gi"))
                .endResources()
                .addNewVolumeMount().withName("gradlecache").withMountPath("/tmp/gradle").endVolumeMount()
                .addNewVolumeMount().withName("testruns").withMountPath(TEST_RUN_DIR).endVolumeMount()
                .endContainer()
                .addNewContainer()
                .withImage(sidecarImage)
                .addNewEnv()
                .withName("DRIVER_NODE_MEMORY")
                .withValue("1024m")
                .withName("DRIVER_WEB_MEMORY")
                .withValue("1024m")
                .endEnv()
                .withName(podName + "-pg")
                .withNewResources()
                .addToRequests("cpu", new Quantity("1"))
                .addToRequests("memory", new Quantity("1Gi"))
                .endResources()
                .endContainer()

                .addNewImagePullSecret(REGISTRY_CREDENTIALS_SECRET_NAME)
                .withRestartPolicy("Never")
                .endSpec()
                .build();
    }

    private void setUpAzureSQLDbSchemaForPod(String podName) {
        try {
            Azure.configure()
                    .withLogLevel(LogLevel.NONE)
                    .authenticate(AZURE_CREDENTIALS)
                    .withDefaultSubscription().sqlServers()
                    .getByResourceGroup(RESOURCE_GROUP, ASQL_SERVER)
                    .databases().define(podName + "-db")
                    .withServiceObjective(ServiceObjectiveName.BASIC)
                    .create();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private ContainerFluent.ResourcesNested<PodSpecFluent.ContainersNested<PodFluent.SpecNested<PodBuilder>>> getBasePodDefinition(String podName, PersistentVolumeClaim pvc, int podIdx) {
        return new PodBuilder()
                .withNewMetadata().withName(podName).endMetadata()
                .withNewSpec()

                .addNewVolume()
                .withName("gradlecache")
                .withNewHostPath()
                .withType("DirectoryOrCreate")
                .withPath("/gradle/" + podIdx + "-" + taskToExecuteName)
                .endHostPath()
                .endVolume()
                .addNewVolume()
                .withName("testruns")
                .withNewPersistentVolumeClaim()
                .withClaimName(pvc.getMetadata().getName())
                .endPersistentVolumeClaim()
                .endVolume()
                .withTolerations(taints.stream().map(taint -> new TolerationBuilder().withKey("key").withValue(taint).withOperator("Equal").withEffect("NoSchedule").build()).collect(Collectors.toList()))
                .addNewContainer()
                .withImage(dockerTag)
                .withCommand("bash")
                .withArgs("-c", "sleep 3600")
                .addNewEnv()
                .withName("DRIVER_NODE_MEMORY")
                .withValue("1024m")
                .withName("DRIVER_WEB_MEMORY")
                .withValue("1024m")
                .endEnv()
                .withName(podName)
                .withNewResources();
    }

    private void startLogPumping(InputStream stdOutIs, int podIdx, File outputFile, boolean printOutput) {

        Thread loggingThread = new Thread(() -> {
            try (BufferedWriter out = new BufferedWriter(new FileWriter(outputFile, true));
                 BufferedReader br = new BufferedReader(new InputStreamReader(stdOutIs))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String toWrite = ("Container" + podIdx + ":   " + line).trim();
                    if (printOutput) {
                        getProject().getLogger().lifecycle(toWrite);
                    }
                    out.write(line);
                    out.newLine();
                    out.flush();
                }
            } catch (IOException ignored) {
            }
        });

        loggingThread.setDaemon(true);
        loggingThread.start();
    }

    private Watch attachStatusListenerToPod(Pod pod) {
        KubernetesClient client = getKubernetesClient();
        return client.pods().inNamespace(pod.getMetadata().getNamespace()).withName(pod.getMetadata().getName()).watch(new Watcher<Pod>() {
            @Override
            public void eventReceived(Action action, Pod resource) {
                getProject().getLogger().lifecycle("[StatusChange]  pod " + resource.getMetadata().getName() + " " + action.name() + " (" + resource.getStatus().getPhase() + ")");
            }

            @Override
            public void onClose(KubernetesClientException cause) {
                client.close();
            }
        });
    }

    private void waitForPodToStart(Pod pod) {
        try (KubernetesClient client = getKubernetesClient()) {
            getProject().getLogger().lifecycle("Waiting for pod " + pod.getMetadata().getName() + " to start before executing build");
            try {
                client.pods().inNamespace(pod.getMetadata().getNamespace()).withName(pod.getMetadata().getName()).waitUntilReady(DEFAULT_POD_ALLOCATION_TIMEOUT, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                throw new InvalidUserCodeException("Exception occurred", e);
            }
            getProject().getLogger().lifecycle("pod " + pod.getMetadata().getName() + " has started, executing build");
        }
    }

    private Collection<File> downloadTestXmlFromPod(String namespace, Pod cp) {
        String resultsInContainerPath = TEST_RUN_DIR + "/test-reports";
        String binaryResultsFile = "results.bin";
        String podName = cp.getMetadata().getName();
        Path tempDir = new File(new File(getProject().getBuildDir(), "test-results-xml"), podName).toPath();

        if (!tempDir.toFile().exists()) {
            tempDir.toFile().mkdirs();
        }
        getProject().getLogger().lifecycle("Saving " + podName + " results to: " + tempDir.toAbsolutePath().toFile().getAbsolutePath());
        try (KubernetesClient client = getKubernetesClient()) {
            client.pods()
                    .inNamespace(namespace)
                    .withName(podName)
                    .inContainer(podName)
                    .dir(resultsInContainerPath)
                    .copy(tempDir);
        }
        return findFolderContainingBinaryResultsFile(new File(tempDir.toFile().getAbsolutePath()), binaryResultsFile);
    }

    private String[] getBuildCommand(int numberOfPods, int podIdx, String podName) {
        final String gitBranch = " -Dgit.branch=" + Properties.getGitBranch();
        final String gitTargetBranch = " -Dgit.target.branch=" + Properties.getTargetGitBranch();
        final String artifactoryUsername = " -Dartifactory.username=" + Properties.getUsername() + " ";
        final String artifactoryPassword = " -Dartifactory.password=" + Properties.getPassword() + " ";

        String shellScript = "(let x=1 ; while [ ${x} -ne 0 ] ; do echo \"Waiting for DNS\" ; curl services.gradle.org > /dev/null 2>&1 ; x=$? ; sleep 1 ; done ) && "
                + " cd /tmp/source && " +
                "(let y=1 ; while [ ${y} -ne 0 ] ; do echo \"Preparing build directory\" ; ./gradlew --no-daemon testClasses integrationTestClasses --parallel 2>&1 ; y=$? ; sleep 1 ; done ) && " +
                "(./gradlew --no-daemon -D" + ListTests.DISTRIBUTION_PROPERTY + "=" + distribution.name() +
                gitBranch +
                gitTargetBranch +
                artifactoryUsername +
                artifactoryPassword +
                "-Dkubenetize -PdockerFork=" + podIdx + " -PdockerForks=" + numberOfPods + " " + fullTaskToExecutePath + " " + getAdditionalArgs(podName) + " " + getLoggingLevel() + " 2>&1) ; " +
                "let rs=$? ; sleep 10 ; exit ${rs}";
        return new String[]{"bash", "-c", shellScript};
    }

    private String getAdditionalArgs(String podName) {
        return this.additionalArgs.isEmpty() ? "" : String.join(" ", reworkDbNameForASQL(this.additionalArgs, podName));
    }

    private List<String> reworkDbNameForASQL(List<String> additionalArgs, String podName) {
        List<String> reworkedArgs = new ArrayList<>();
        for (String arg : additionalArgs) {
            // replace db name placeholder in jdbc connection string
            reworkedArgs.add(arg.replace("corda-db-test", podName + "-db"));
        }
        return reworkedArgs;
    }

    private String getLoggingLevel() {

        switch (podLogLevel) {
            case INFO:
                return " --info";
            case WARN:
                return " --warn";
            case QUIET:
                return " --quiet";
            case DEBUG:
                return " --debug";
            default:
                throw new IllegalArgumentException("LogLevel: " + podLogLevel + " is unknown");
        }

    }

    private List<File> findFolderContainingBinaryResultsFile(File start, String fileNameToFind) {
        Queue<File> filesToInspect = new LinkedList<>(Collections.singletonList(start));
        List<File> folders = new ArrayList<>();
        while (!filesToInspect.isEmpty()) {
            File fileToInspect = filesToInspect.poll();
            if (fileToInspect.getAbsolutePath().endsWith(fileNameToFind)) {
                folders.add(fileToInspect.getParentFile());
            }

            if (fileToInspect.isDirectory()) {
                filesToInspect.addAll(Arrays.stream(Optional.ofNullable(fileToInspect.listFiles()).orElse(new File[]{})).collect(Collectors.toList()));
            }
        }
        return folders;
    }

    private ExecListener buildExecListenerForPod(String podName, ByteArrayOutputStream errChannelStream, CompletableFuture<Integer> waitingFuture, KubernetesClient client) {

        return new ExecListener() {
            final Long start = System.currentTimeMillis();

            @Override
            public void onOpen(Response response) {
                getProject().getLogger().lifecycle("Build started on pod  " + podName);
            }

            @Override
            public void onFailure(Throwable t, Response response) {
                getProject().getLogger().lifecycle("Received error from pod  " + podName);
                waitingFuture.completeExceptionally(t);
            }

            @Override
            public void onClose(int code, String reason) {
                getProject().getLogger().lifecycle("Received onClose() from pod " + podName + " , build took: " + ((System.currentTimeMillis() - start) / 1000) + " seconds");
                try {
                    String errChannelContents = errChannelStream.toString();
                    Status status = Serialization.unmarshal(errChannelContents, Status.class);
                    Integer resultCode = Optional.ofNullable(status).map(Status::getDetails)
                            .map(StatusDetails::getCauses)
                            .flatMap(c -> c.stream().findFirst())
                            .map(StatusCause::getMessage)
                            .map(Integer::parseInt).orElse(0);
                    waitingFuture.complete(resultCode);
                } catch (Exception e) {
                    waitingFuture.completeExceptionally(e);
                } finally {
                    client.close();
                }
            }
        };
    }

    private class RetryablePodOperations {
        private PodResource<Pod, DoneablePod> backingResource;

        public RetryablePodOperations(PodResource<Pod, DoneablePod> backingResource) {
            this.backingResource = backingResource;
        }

        public Pod get() {
            return Retry.fixedWithDelay(10, 1000, getProject().getLogger()).call(() -> backingResource.get());
        }

        public void delete() {
            Retry.fixedWithDelay(10, 1000, getProject().getLogger()).call(() -> backingResource.delete());
        }
    }
}