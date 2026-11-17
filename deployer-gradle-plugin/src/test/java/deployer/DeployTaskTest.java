package deployer;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipInputStream;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeployTaskTest {

    private static final String DEPLOYMENT_ID = "28570f16-da32-4c14-bd2e-c1acc0782365";

    @TempDir
    Path project;

    private HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private int uploadStatus = 201;
    private String uploadBody = DEPLOYMENT_ID;
    private String state = "PUBLISHED";

    @BeforeEach
    void startPortal() throws IOException {
        var portal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server = portal;
        portal.createContext("/upload", exchange -> respond(exchange, uploadStatus, uploadBody));
        portal.createContext("/status", exchange -> respond(exchange, 200, """
                {"deploymentState":"%s"}
                """.formatted(state)));
        portal.start();
    }

    @AfterEach
    void stopPortal() {
        server.stop(0);
    }

    @Test
    void uploadsOnlyTheStagedReleaseFromEachRepository() throws IOException {
        stage("module-a", "alpha", "1.0");
        stage("module-a", "alpha", "2.0");
        stage("stage-inputs", "beta", "2.0");
        write("module-a/com/example/alpha/maven-metadata.xml", "metadata");
        configure(
                "AUTOMATIC",
                "dirs = [file('module-a'), file('module-b')]",
                "releaseVersion = providers.gradleProperty('releaseVersion')");
        Files.writeString(project.resolve("build.gradle"), """
                def stageModuleB = tasks.register('stageModuleB', Copy) {
                    from 'stage-inputs'
                    into 'module-b'
                }
                tasks.named('deploy') {
                    dependsOn stageModuleB
                }
                """, StandardOpenOption.APPEND);

        var result = runner("-PreleaseVersion=2.0").build();

        assertThat(result.getOutput())
                .contains("Central Portal accepted the bundle", DEPLOYMENT_ID)
                .doesNotContain("Deployment successfully PUBLISHED");
        assertThat(requests).hasSize(1);
        var upload = requests.get(0);
        assertThat(upload.method()).isEqualTo("POST");
        assertThat(upload.query()).isEqualTo("publishingType=AUTOMATIC");
        assertThat(upload.authorization()).isEqualTo("Bearer dXNlcjp0b2tlbg==");
        assertThat(upload.contentType()).startsWith("multipart/form-data; boundary=");
        assertThat(zipEntries(upload))
                .containsExactlyInAnyOrder(
                        "com/example/alpha/2.0/alpha-2.0.pom",
                        "com/example/alpha/2.0/alpha-2.0.jar",
                        "com/example/beta/2.0/beta-2.0.pom",
                        "com/example/beta/2.0/beta-2.0.jar");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTOMATIC", "USER_MANAGED", "WAIT_FOR_PUBLISHED"})
    void failsWhenThePortalRejectsTheUpload(String mode) throws IOException {
        stage("repo", "alpha", "2.0");
        configure(mode, "dirs = [file('repo')]", "");
        uploadStatus = 401;
        uploadBody = "Invalid credentials";

        assertThat(runner().buildAndFail().getOutput())
                .contains("Central Portal rejected the bundle (HTTP 401)", "Invalid credentials");
        assertThat(requests).hasSize(1);
    }

    @Test
    void failsWhenThePortalOmitsTheDeploymentId() throws IOException {
        stage("repo", "alpha", "2.0");
        configure("WAIT_FOR_PUBLISHED", "dirs = [file('repo')]", "");
        uploadBody = " ";

        assertThat(runner().buildAndFail().getOutput()).contains("did not return a valid deployment ID");
        assertThat(requests).hasSize(1);
    }

    @Test
    void waitsForPublishedUsingTheDeploymentId() throws IOException {
        stage("repo", "alpha", "2.0");
        configure("WAIT_FOR_PUBLISHED", "dirs = [file('repo')]", "");

        assertThat(runner().build().getOutput()).contains("Deployment successfully PUBLISHED");
        assertThat(requests)
                .extracting(Request::query)
                .containsExactly("publishingType=AUTOMATIC", "id=" + DEPLOYMENT_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "PENDING"})
    void failsOnTheTerminalErrorOrWhenPollingRunsOut(String deploymentState) throws IOException {
        stage("repo", "alpha", "2.0");
        configure("WAIT_FOR_PUBLISHED", "dirs = [file('repo')]", "");
        state = deploymentState;

        var output = runner().buildAndFail().getOutput();
        if (state.equals("FAILED")) {
            assertThat(output).contains("Deployment failed with state: FAILED");
            assertThat(requests).hasSize(2);
        } else {
            assertThat(output).contains("Timeout waiting for deployment " + DEPLOYMENT_ID);
            assertThat(requests).hasSize(3);
        }
    }

    @Test
    void failsWhenNoDirectoriesAreConfigured() throws IOException {
        configure("AUTOMATIC", "dirs = []", "");

        assertThat(runner().buildAndFail().getOutput()).contains("No artifact directories configured");
        assertThat(requests).isEmpty();
    }

    @Test
    void failsWhenTheRepositoryIsEmpty() throws IOException {
        Files.createDirectories(project.resolve("repo"));
        configure("AUTOMATIC", "dirs = [file('repo')]", "");

        assertThat(runner().buildAndFail().getOutput()).contains("No staged artifacts found");
        assertThat(requests).isEmpty();
    }

    @Test
    void failsWhenAnExpectedModuleHasOnlyAnOlderVersion() throws IOException {
        stage("module-a", "alpha", "2.0");
        stage("module-b", "beta", "1.0");
        configure("AUTOMATIC", "dirs = [file('module-a'), file('module-b')]", "releaseVersion = '2.0'");

        assertThat(runner().buildAndFail().getOutput())
                .contains("No staged Maven publication found for release 2.0", "module-b");
        assertThat(requests).isEmpty();
    }

    private void configure(String mode, String dirs, String version) throws IOException {
        write("settings.gradle", "rootProject.name = 'deploy-test'");
        write("build.gradle", """
                import io.github.danielliu1123.deployer.DeployTask
                import io.github.danielliu1123.deployer.PublishingType

                plugins {
                    id 'io.github.danielliu1123.deployer'
                }

                deploy {
                    %s
                    %s
                    username = 'user'
                    password = 'token'
                    publishingType = PublishingType.%s
                }

                tasks.named('deploy', DeployTask) {
                    configurePublisher(URI.create('http://127.0.0.1:%d/'), 0, 2)
                }
                """.formatted(
                        dirs, version, mode, server.getAddress().getPort()));
    }

    private GradleRunner runner(String... extraArguments) {
        var arguments = new ArrayList<>(List.of("deploy", "--configuration-cache"));
        arguments.addAll(Arrays.asList(extraArguments));
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }

    private void stage(String repo, String artifact, String version) throws IOException {
        var base = repo + "/com/example/" + artifact + "/" + version + "/" + artifact + "-" + version;
        write(base + ".pom", "<project/>");
        write(base + ".jar", "test jar");
    }

    private void write(String relativePath, String contents) throws IOException {
        var path = project.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }

    private void respond(HttpExchange exchange, int status, String body) {
        try (exchange) {
            var headers = exchange.getRequestHeaders();
            requests.add(new Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getQuery(),
                    headers.getFirst("Authorization"),
                    headers.getFirst("Content-Type"),
                    exchange.getRequestBody().readAllBytes()));
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> zipEntries(Request upload) throws IOException {
        var multipart = upload.body();
        var boundary = upload.contentType().substring("multipart/form-data; boundary=".length());
        var payload = new String(multipart, StandardCharsets.ISO_8859_1);
        assertThat(payload)
                .startsWith("--" + boundary + "\r\nContent-Disposition: form-data; name=bundle; filename=")
                .endsWith("\r\n--" + boundary + "--\r\n");
        int zipStart = payload.indexOf("\r\n\r\n") + 4;
        int zipEnd = payload.lastIndexOf("\r\n--" + boundary);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(multipart, zipStart, zipEnd - zipStart))) {
            var entries = new ArrayList<String>();
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.add(entry.getName());
                assertThat(zip.readAllBytes()).isNotEmpty();
            }
            return entries;
        }
    }

    private record Request(String method, String query, String authorization, String contentType, byte[] body) {}
}
