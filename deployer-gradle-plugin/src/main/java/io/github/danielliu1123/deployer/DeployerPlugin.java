package io.github.danielliu1123.deployer;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * A Gradle plugin that simplifies publishing artifacts to Maven repositories.
 *
 * @author Freeman
 */
public class DeployerPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        var extension = project.getExtensions().create("deploy", DeployerPluginExtension.class, project);

        project.getTasks()
                .register("deploy", DeployTask.class, project, extension)
                .configure(task -> {
                    task.setGroup("publishing");
                    task.setDescription("Deploys specified dirs to Maven central.");
                });
    }
}
