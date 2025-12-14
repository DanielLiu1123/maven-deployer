package io.github.danielliu1123.deployer;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

public class DeployConfigTask extends DefaultTask {

    @TaskAction
    public void print() {
        DeployerPluginExtension extension = getProject().getExtensions().findByType(DeployerPluginExtension.class);
        if (extension == null) {
            getLogger().warn("Deployer plugin extension not found.");
            return;
        }

        getLogger().lifecycle("Deployer Plugin Config:");
        getLogger().lifecycle(extension.toString());
    }
}
