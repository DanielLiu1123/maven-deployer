package io.github.danielliu1123.deployer;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 *
 * @author Freeman
 */
public abstract class DeployerPluginExtension {

    private final ListProperty<DirectoryProperty> dirs;
    private final Property<String> username;
    private final Property<String> password;
    private final Property<PublishingType> publishingType;
    private final Sign sign;

    public DeployerPluginExtension(Project project) {
        ObjectFactory objects = project.getObjects();
        this.dirs = objects.listProperty(DirectoryProperty.class);
        this.username = objects.property(String.class);
        this.password = objects.property(String.class);
        this.publishingType = objects.property(PublishingType.class).convention(PublishingType.USER_MANAGED);
        this.sign = objects.newInstance(Sign.class);
    }

    public ListProperty<DirectoryProperty> getDirs() {
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

    public Sign getSign() {
        return sign;
    }

    public void sign(Action<? super Sign> action) {
        action.execute(sign);
    }

    /**
     * sign { ... }
     */
    public abstract static class Sign {
        public abstract Property<String> getSecretKey();

        public abstract Property<String> getPassphrase();
    }
}
