package io.github.danielliu1123.deployer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 *
 *
 * @author Freeman
 */
@DisableCachingByDefault(
        because = "Uploading artifacts to Maven Central is a network side effect, not a cacheable operation")
public class DeployTask extends DefaultTask {

    private final DeployerPluginExtension extension;
    private final File projectDir;
    private final Logger logger;
    private URI publisherApi = URI.create("https://central.sonatype.com/api/v1/publisher/");
    private int pollIntervalSeconds = 10;
    private int maxPollAttempts = 1080;

    @Inject
    public DeployTask(Project project, DeployerPluginExtension extension) {
        this.extension = extension;
        this.projectDir = project.getProjectDir();
        this.logger = getLogger();
    }

    @TaskAction
    public void deploy() throws Exception {
        List<Path> dirPaths =
                extension.getDirs().get().stream().map(File::toPath).toList();
        if (dirPaths.isEmpty()) {
            throw new GradleException(
                    "No artifact directories configured. Set deploy.dirs to the staged Maven repositories.");
        }

        logger.lifecycle("Configured dirs:");
        for (Path dirPath : dirPaths) {
            logger.lifecycle("  - " + dirPath);
        }

        // package artifacts into a zip file
        var bundleName = "%s-bundle.zip".formatted(projectDir.getName());
        var bundlePath = Path.of(projectDir.getAbsolutePath(), bundleName);
        File zipFile =
                createBundle(dirPaths, bundlePath, extension.getReleaseVersion().getOrNull());

        logger.lifecycle("Deploy bundle: " + zipFile.getAbsolutePath());

        doDeploy(zipFile);
    }

    private void doDeploy(File zipFile) throws Exception {
        var mode = extension.getPublishingType().get();
        var publishingType = mode == PublishingType.WAIT_FOR_PUBLISHED ? PublishingType.AUTOMATIC : mode;
        var deploymentId = uploadBundle(zipFile, publishingType);
        logger.lifecycle(
                "Central Portal accepted the bundle (deployment ID: {}, publishing type: {}).",
                deploymentId,
                publishingType);
        if (mode == PublishingType.WAIT_FOR_PUBLISHED) {
            waitForPublished(deploymentId);
        }
    }

    void configurePublisher(URI publisherApi, int pollIntervalSeconds, int maxPollAttempts) {
        this.publisherApi = publisherApi;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.maxPollAttempts = maxPollAttempts;
    }

    private String uploadBundle(File zipFile, PublishingType publishingType) throws Exception {
        // random boundary
        String boundary = "----JavaBoundary" + UUID.randomUUID();

        // multipart body: headers + file bytes + end boundary
        String partHeaders = "--" + boundary + "\r\n" + "Content-Disposition: form-data; name=bundle; filename="
                + zipFile.getName() + "\r\n" + "Content-Type: application/octet-stream\r\n\r\n";
        String endBoundary = "\r\n--" + boundary + "--\r\n";

        var url = publisherApi.resolve("upload?publishingType=" + publishingType.name());

        logger.lifecycle("Deploying to URL: " + url);

        var request = HttpRequest.newBuilder()
                .uri(url)
                .header("Authorization", "Bearer " + getAuth())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(createMultipartBody(partHeaders, zipFile.toPath(), endBoundary))
                .build();

        var httpClient = HttpClient.newHttpClient();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (!is2xx(response.statusCode())) {
            throw new GradleException(
                    "Central Portal rejected the bundle (HTTP " + response.statusCode() + "): " + response.body());
        }
        var deploymentId = response.body().strip();
        try {
            if (!UUID.fromString(deploymentId).toString().equalsIgnoreCase(deploymentId)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
        } catch (IllegalArgumentException e) {
            throw new GradleException("Central Portal did not return a valid deployment ID (HTTP "
                    + response.statusCode() + "). Check the Portal before uploading again.");
        }
        return deploymentId;
    }

    private static boolean is2xx(int i) {
        return i >= 200 && i < 300;
    }

    private String getAuth() {
        var username = extension.getUsername().get();
        var password = extension.getPassword().get();
        var credentials = "%s:%s".formatted(username, password);
        return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static File createBundle(List<Path> paths, Path zipFilePath, String releaseVersion) throws IOException {
        if (releaseVersion != null
                && (releaseVersion.isBlank()
                        || releaseVersion.endsWith("-SNAPSHOT")
                        || releaseVersion.contains("/")
                        || releaseVersion.contains("\\"))) {
            throw new GradleException("deploy.releaseVersion must be a Maven release version.");
        }

        // Resolve and validate every configured repository before writing the bundle.
        List<List<Path>> artifacts = new ArrayList<>();
        for (Path path : paths) {
            artifacts.add(collectArtifacts(path, releaseVersion));
        }

        Files.deleteIfExists(zipFilePath);
        try (var zip = new ZipOutputStream(Files.newOutputStream(zipFilePath, StandardOpenOption.CREATE_NEW))) {
            for (int i = 0; i < paths.size(); i++) {
                var root = paths.get(i);
                for (var file : artifacts.get(i)) {
                    var entryName = root.relativize(file).toString().replace(File.separatorChar, '/');
                    zip.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
        }
        return zipFilePath.toFile();
    }

    private static List<Path> collectArtifacts(Path root, String releaseVersion) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new GradleException("Staged Maven repository does not exist or is not a directory: " + root);
        }
        List<Path> files = new ArrayList<>();
        Set<Path> releaseDirectories = new HashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    files.add(file);
                    var relativePath = root.relativize(file);
                    int depth = relativePath.getNameCount();
                    if (releaseVersion != null && depth >= 4) {
                        var artifact = relativePath.getName(depth - 3);
                        var version = relativePath.getName(depth - 2).toString();
                        var filename = relativePath.getName(depth - 1).toString();
                        if (version.equals(releaseVersion) && filename.equals(artifact + "-" + version + ".pom")) {
                            releaseDirectories.add(file.getParent());
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        var selected = files.stream()
                .filter(file -> releaseVersion == null || releaseDirectories.contains(file.getParent()))
                .sorted()
                .toList();
        if (selected.isEmpty()) {
            throw new GradleException(
                    releaseVersion == null
                            ? "No staged artifacts found in " + root
                            : "No staged Maven publication found for release " + releaseVersion + " in " + root);
        }
        return selected;
    }

    private static HttpRequest.BodyPublisher createMultipartBody(String headers, Path file, String endBoundary) {
        var headerBuffer = headers.getBytes(StandardCharsets.UTF_8);
        var endBuffer = endBoundary.getBytes(StandardCharsets.UTF_8);
        return HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                var headerBytes = new ByteArrayInputStream(headerBuffer);
                var endBytes = new ByteArrayInputStream(endBuffer);
                var fileBytes = Files.newInputStream(file);
                return new SequenceInputStream(
                        Collections.enumeration(Arrays.asList(headerBytes, fileBytes, endBytes)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void waitForPublished(String deploymentId) throws Exception {
        logger.lifecycle("\nWaiting for deployment to be PUBLISHED (deploymentId: " + deploymentId + ")...");

        var statusUrl = publisherApi.resolve("status?id=" + deploymentId);
        int attempts = 0;
        var startTime = System.currentTimeMillis();
        var httpClient = HttpClient.newHttpClient();

        while (attempts < maxPollAttempts) {
            attempts++;

            var request = HttpRequest.newBuilder()
                    .uri(statusUrl)
                    .header("Authorization", "Bearer " + getAuth())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (!is2xx(response.statusCode())) {
                logger.warn("Status check failed with code {}: {}", response.statusCode(), response.body());
                Thread.sleep(pollIntervalSeconds * 1000L);
                continue;
            }

            var endTime = System.currentTimeMillis();
            var state = extractDeploymentState(response.body());
            logger.lifecycle("  [" + attempts + "] Current state: " + state);

            switch (state) {
                case PENDING, VALIDATING, VALIDATED, PUBLISHING -> Thread.sleep(pollIntervalSeconds * 1000L);
                case PUBLISHED -> {
                    var minutes = (endTime - startTime) / 1000 / 60;
                    var seconds = (endTime - startTime) / 1000 % 60;
                    logger.lifecycle("%n✓ [ %dm%ds ] Deployment successfully PUBLISHED and available on Maven Central!"
                            .formatted(minutes, seconds));
                    return;
                }
                case FAILED -> {
                    logger.error("\n✗ Deployment FAILED. Response: {}", response.body());
                    throw new IllegalStateException(
                            "Deployment failed with state: FAILED, response: " + response.body());
                }
                case UNRECOGNIZED ->
                    throw new IllegalStateException("Unrecognized deployment state. Response: " + response.body());
            }
        }

        throw new GradleException("Timeout waiting for deployment " + deploymentId + " to be PUBLISHED after "
                + maxPollAttempts + " status checks.");
    }

    /**
     * @see <a href="https://central.sonatype.org/publish/publish-portal-api/#verify-status-of-the-deployment">Verify Status of the Deployment</a>
     */
    private DeploymentState extractDeploymentState(String responseBody) {
        Pattern pattern = Pattern.compile("\"deploymentState\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(responseBody);
        if (matcher.find()) {
            var state = matcher.group(1);
            try {
                return DeploymentState.valueOf(state);
            } catch (IllegalArgumentException e) {
                logger.warn("Unrecognized deployment state: {}", state);
                return DeploymentState.UNRECOGNIZED;
            }
        }
        logger.warn("Could not extract deploymentState from response body: {}", responseBody);
        return DeploymentState.UNRECOGNIZED;
    }

    enum DeploymentState {
        PENDING,
        VALIDATING,
        VALIDATED,
        PUBLISHING,
        PUBLISHED,
        FAILED,
        UNRECOGNIZED
    }
}
