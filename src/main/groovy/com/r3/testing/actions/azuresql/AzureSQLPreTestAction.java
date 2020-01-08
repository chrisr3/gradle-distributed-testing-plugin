package com.r3.testing.actions.azuresql;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.sql.ServiceObjectiveName;
import com.microsoft.azure.management.sql.SqlDatabase;
import com.microsoft.rest.LogLevel;
import com.r3.testing.actions.PreTestAction;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static com.r3.testing.KubesTest.NAMESPACE;

public class AzureSQLPreTestAction extends AzureSQLAware implements PreTestAction {

    private void tearDownOrphanedAzureSQLDbs(KubernetesClient client) {
        // get all of the live pod names
        List<String> podNames = client.pods().inNamespace(NAMESPACE).list().getItems().stream().map(pod -> pod.getMetadata().getName()).collect(Collectors.toList());
        try {
            Azure azure = Azure.configure()
                    .withLogLevel(LogLevel.NONE)
                    .authenticate(AZURE_CREDENTIALS)
                    .withDefaultSubscription();
            List<SqlDatabase> databases = azure.sqlServers()
                    .getByResourceGroup(RESOURCE_GROUP, ASQL_SERVER)
                    .databases().list().stream()
                    .filter(db -> !db.name().contains("master")).collect(Collectors.toList());
            for (SqlDatabase db : databases) {
                if (podNames.stream().noneMatch(podName -> db.name().contains(podName))) {
                    azure.sqlServers().getByResourceGroup(RESOURCE_GROUP, ASQL_SERVER).databases().delete(db.name());
                }
            }
        } catch (IOException ignored) {
            // it's possible that a db is being deleted by another build, this can lead to racey conditions
        }
    }

    private boolean setUpAzureSQLDbSchemaForPod(String podName) {
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
            return false;
        }
        return true;
    }

    @Override
    public boolean execute(String podName, KubernetesClient client) {
        tearDownOrphanedAzureSQLDbs(client);
        return setUpAzureSQLDbSchemaForPod(podName);
    }


}
