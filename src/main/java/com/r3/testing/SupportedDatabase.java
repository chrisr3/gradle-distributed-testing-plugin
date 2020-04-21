package com.r3.testing;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public enum SupportedDatabase {

    POSTGRES {
        @Override
        public String asLowerCase() {
            return POSTGRES.toString().toLowerCase();
        }
        
        @Override
        public String getDBContainerSuffix() {
            return "-pg";
        }

        @Override
        public Map<String, String> getEnvVars(List<String> additionalArgs) {
            return new HashMap<String, String>() {
                {
                    put("POSTGRES_HOST_AUTH_METHOD", "trust");
                }
            };
        }
    }, MSSQL {
        @Override
        public String asLowerCase() {
            return MSSQL.toString().toLowerCase();
        }
        
        @Override
        public String getDBContainerSuffix() {
            return "-mssql";
        }

        @Override
        public Map<String, String> getEnvVars(List<String> additionalArgs) {
            return new HashMap<String, String>() {
                {
                    put("ACCEPT_EULA", "Y");
                    put("SA_PASSWORD", getMSSQLDBPassword(additionalArgs));
                }
            };
        }
    }, AZURE {
        @Override
        public String asLowerCase() {
            return AZURE.toString().toLowerCase();
        }

        @Override
        public String getDBContainerSuffix() {
            throw new IllegalStateException("DB container not required for AzureSQL");
        }

        @Override
        public Map<String, String> getEnvVars(List<String> additionalArgs) {
            return Collections.emptyMap();
        }
    };

    public abstract String asLowerCase();
    public abstract String getDBContainerSuffix();
    public abstract Map<String, String> getEnvVars(List<String> additionalArgs);

    public static String getMSSQLDBPassword(List<String> additionalArgs) {
        if (additionalArgs.stream().noneMatch(arg -> arg.contains("test.db.admin.password"))) {
            throw new IllegalArgumentException("Couldn't find `test.db.admin.password` in MSSQL parallel database" +
                    "integration test task additional arguments list. Check host project build.gradle configuration.");
        }
        return additionalArgs.stream().filter(arg -> arg.contains("test.db.admin.password"))
                .map(arg -> arg.replaceAll(".*=", "")).collect(Collectors.joining());

    }
}
