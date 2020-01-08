package com.r3.testing.actions.azuresql;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.sql.SqlDatabase;
import com.microsoft.rest.LogLevel;
import com.r3.testing.actions.PostTestAction;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.io.IOException;

public class AzureSQLPostTestAction extends AzureSQLAware implements PostTestAction {

    @Override
    public boolean execute(String podName, KubernetesClient client) {
        return tearDownAzureSQLDBForPod(podName);
    }

    private boolean tearDownAzureSQLDBForPod(String podName) {
        try {
            Azure.configure()
                    .withLogLevel(LogLevel.NONE)
                    .authenticate(AZURE_CREDENTIALS)
                    .withDefaultSubscription().sqlServers()
                    .getByResourceGroup(RESOURCE_GROUP, ASQL_SERVER)
                    .databases().list().stream()
                    .filter(db -> db.name().contains(podName)).forEach(SqlDatabase::delete);
        } catch (IOException ignored) {
            //it's possible that a db is being deleted by another build, this can lead to racey conditions
        }
        return true;
    }
}
