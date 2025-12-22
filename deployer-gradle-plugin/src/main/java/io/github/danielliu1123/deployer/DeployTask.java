package io.github.danielliu1123.deployer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.TaskAction;

/**
 *
 *
 * @author Freeman
 */
public class DeployTask extends DefaultTask {

    private final DeployerPluginExtension extension;
    private final File projectDir;
    private final Logger logger;

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
            logger.lifecycle("No dirs configured for deploying. Skipping.");
            return;
        }

        logger.lifecycle("Configured dirs:");
        for (Path dirPath : dirPaths) {
            logger.lifecycle("  - " + dirPath);
        }

        // package artifacts into a zip file
        var bundleName = "%s-bundle.zip".formatted(projectDir.getName());
        var bundlePath = Path.of(projectDir.getAbsolutePath(), bundleName);
        File zipFile = createBundle(dirPaths, bundlePath);

        logger.lifecycle("Deploy bundle: " + zipFile.getAbsolutePath());

        doDeploy(zipFile);
    }

    private void doDeploy(File zipFile) throws Exception {
        switch (getPublishingType()) {
            case USER_MANAGED -> uploadBundle(zipFile, PublishingType.USER_MANAGED);
            case AUTOMATIC -> uploadBundle(zipFile, PublishingType.AUTOMATIC);
            case WAIT_FOR_PUBLISHED -> {
                var resp = uploadBundle(zipFile, PublishingType.AUTOMATIC);
                if (is2xx(resp.statusCode())) {
                    String deploymentId = extractDeploymentId(resp.body());
                    if (deploymentId != null && !deploymentId.isBlank()) {
                        waitForPublished(deploymentId);
                    } else {
                        logger.lifecycle(
                                "Warning: Could not extract deploymentId from response. Cannot wait for PUBLISHED status.");
                    }
                } else {
                    logger.lifecycle("Warning: Upload failed with status " + resp.statusCode()
                            + ". Cannot wait for PUBLISHED status.");
                }
            }
        }
    }

    private HttpResponse<String> uploadBundle(File zipFile, PublishingType publishingType) throws Exception {
        // random boundary
        String boundary = "----JavaBoundary" + UUID.randomUUID();

        // multipart body: headers + file bytes + end boundary
        String partHeaders = "--" + boundary + "\r\n" + "Content-Disposition: form-data; name=bundle; filename="
                + zipFile.getName() + "\r\n" + "Content-Type: application/octet-stream\r\n\r\n";
        String endBoundary = "\r\n--" + boundary + "--\r\n";

        var url = "https://central.sonatype.com/api/v1/publisher/upload?publishingType=%s"
                .formatted(publishingType.name());

        logger.lifecycle("Deploying to URL: " + url);

        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + getAuth())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(createMultipartBody(partHeaders, zipFile.toPath(), endBoundary))
                .build();

        var httpClient = HttpClient.newHttpClient();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        logger.lifecycle("Response: ");
        logger.lifecycle("  status: " + response.statusCode());
        logger.lifecycle("  body: " + response.body());
        logger.lifecycle("  headers: " + response.headers().map());
        return response;
    }

    private static boolean is2xx(int i) {
        return i >= 200 && i < 300;
    }

    private PublishingType getPublishingType() {
        return extension.getPublishingType().get();
    }

    private String getAuth() {
        var username = extension.getUsername().get();
        var password = extension.getPassword().get();
        var credentials = "%s:%s".formatted(username, password);
        return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static File createBundle(List<Path> paths, Path zipFilePath) {
        for (Path path : paths) {
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException("The provided path is not a directory: " + path);
            }
        }

        // package all files from all directories into a single zip file
        File zipFile = zipFilePath.toFile();
        if (zipFile.exists() && !zipFile.delete()) {
            throw new IllegalStateException("Failed to delete existing zip file: " + zipFile);
        }

        try (var zipOutputStream = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (Path path : paths) {
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        var zipEntry = new ZipEntry(path.relativize(file).toString());
                        zipOutputStream.putNextEntry(zipEntry);
                        Files.copy(file, zipOutputStream);
                        zipOutputStream.closeEntry();
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to zip artifacts from directories", e);
        }
        return zipFile;
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

    /**
     * see <a href="https://central.sonatype.org/publish/publish-portal-api/#uploading-a-deployment-bundle">Upload Bundle</a>
     */
    private static String extractDeploymentId(String responseBody) {
        return responseBody;
    }

    private void waitForPublished(String deploymentId) throws Exception {
        logger.lifecycle("\nWaiting for deployment to be PUBLISHED (deploymentId: " + deploymentId + ")...");

        String statusUrl = "https://central.sonatype.com/api/v1/publisher/status?id=" + deploymentId;
        int pollIntervalSeconds = 10;
        int maxAttempts = 1080; // 3 hours max (1080 * 10 seconds)
        int attempts = 0;
        var startTime = System.currentTimeMillis();
        var httpClient = HttpClient.newHttpClient();

        while (attempts < maxAttempts) {
            attempts++;

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
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

        throw new RuntimeException("Timeout waiting for deployment to be PUBLISHED after "
                + (maxAttempts * pollIntervalSeconds / 60) + " minutes");
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
