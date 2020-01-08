package com.r3.testing.actions.azuresql;

import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;

public class AzureSQLAware {

    protected final String RESOURCE_GROUP = "build-k8s-infrastructure";
    protected final String CLIENT = System.getProperty("azure.client");
    protected final String TENANT = System.getProperty("azure.tenant");
    protected final String KEY = System.getProperty("azure.key");
    protected final String ASQL_SERVER = "eng-infra-test-db-server-01";
    protected final ApplicationTokenCredentials AZURE_CREDENTIALS = new ApplicationTokenCredentials(
            CLIENT, TENANT, KEY, AzureEnvironment.AZURE
    );

}
