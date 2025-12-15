package io.github.danielliu1123.deployer;

import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.TaskAction;

public class DeployConfigTask extends DefaultTask {

    private final DeployerPluginExtension extension;
    private final Logger logger;

    @Inject
    public DeployConfigTask(DeployerPluginExtension extension) {
        this.extension = extension;
        this.logger = getLogger();
    }

    @TaskAction
    public void print() {
        if (extension == null) {
            logger.warn("Deployer plugin extension not found.");
            return;
        }

        logger.lifecycle("Deployer Plugin Config:");
        logger.lifecycle(extension.toString());
    }
}
