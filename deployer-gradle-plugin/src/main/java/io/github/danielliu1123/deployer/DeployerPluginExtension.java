package io.github.danielliu1123.deployer;

import java.io.File;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 *
 * @author Freeman
 */
public abstract class DeployerPluginExtension {

    private final ListProperty<File> dirs;
    private final Property<String> username;
    private final Property<String> password;
    private final Property<PublishingType> publishingType;

    public DeployerPluginExtension(Project project) {
        ObjectFactory objects = project.getObjects();
        this.dirs = objects.listProperty(File.class);
        this.username = objects.property(String.class);
        this.password = objects.property(String.class);
        this.publishingType = objects.property(PublishingType.class).convention(PublishingType.USER_MANAGED);
    }

    public ListProperty<File> getDirs() {
        return dirs;
    }

    public Property<String> getUsername() {
        return username;
    }

    public Property<String> getPassword() {
        return password;
    }

    public Property<PublishingType> getPublishingType() {
        return publishingType;
    }
}
