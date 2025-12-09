package deployer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *
 *
 * @author Freeman
 */
class DeployerPluginTest {

    @TempDir
    File rootDir;

    File settingsFile;
    File buildFile;

    @BeforeEach
    void setup() {
        settingsFile = new File(rootDir, "settings.gradle");
        buildFile = new File(rootDir, "build.gradle");
    }

    @Test
    void testDeployExtension() throws Exception {
        String settingsGradleContent = """
                rootProject.name = "deployer-test-project"
                """;
        String buildGradleContent = """
                plugins {
                    id 'io.github.danielliu1123.deployer'
                }

                deploy {
                    dirs = [ file("artifacts") ]
                    username = "testuser"
                    password = "testpassword"
                    publishingType = io.github.danielliu1123.deployer.PublishingType.AUTOMATIC
                }
                """;

        Files.writeString(settingsFile.toPath(), settingsGradleContent);
        Files.writeString(buildFile.toPath(), buildGradleContent);

        BuildResult result = GradleRunner.create()
                .withProjectDir(rootDir)
                .withPluginClasspath()
                .forwardOutput()
                .withDebug(true) // https://docs.gradle.org/current/userguide/test_kit.html#sub:test-kit-debug
                .withArguments("deploy", "--dry-run")
                .build();

        assertThat(result.getOutput()).contains("BUILD SUCCESSFUL");
    }
}
