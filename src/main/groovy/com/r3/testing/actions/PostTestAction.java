package com.r3.testing.actions;

import io.fabric8.kubernetes.client.KubernetesClient;

public interface PostTestAction {


    boolean execute(String podName, KubernetesClient client);

}
