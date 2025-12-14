package io.github.danielliu1123.deployer;

import java.io.File;
import java.util.Collections;
import java.util.List;
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

    @Override
    public String toString() {
        // json pretty print
        StringBuilder result = new StringBuilder();
        result.append("{\n");
        result.append("  \"dirs\": ");
        List<File> dirList = dirs.isPresent() ? dirs.get() : Collections.emptyList();
        if (dirList.isEmpty()) {
            result.append("[]");
        } else {
            result.append("[\n");
            for (int i = 0; i < dirList.size(); i++) {
                result.append("    \"").append(dirList.get(i).getAbsolutePath()).append("\"");
                if (i < dirList.size() - 1) {
                    result.append(",");
                }
                result.append("\n");
            }
            result.append("  ],\n");
        }
        result.append("  \"username\": ")
                .append(username.isPresent() ? "\"****\"" : "<not set>")
                .append(",\n");
        result.append("  \"password\": ")
                .append(password.isPresent() ? "\"****\"" : "<not set>")
                .append(",\n");
        result.append("  \"publishingType\": ")
                .append(publishingType.isPresent() ? "\"" + publishingType.get().name() + "\"" : "<not set>")
                .append("\n");
        result.append("}");
        return result.toString();
    }
}
