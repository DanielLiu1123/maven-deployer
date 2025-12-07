package deployer;

import static org.assertj.core.api.Assertions.assertThat;

import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

/**
 *
 *
 * @author Freeman
 */
class DeployerPluginTest {

    @Test
    void pluginRegistersDeployTask() {
        var project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.danielliu1123.deployer");

        assertThat(project.getTasks().findByName("deploy")).isNotNull();
    }
}
