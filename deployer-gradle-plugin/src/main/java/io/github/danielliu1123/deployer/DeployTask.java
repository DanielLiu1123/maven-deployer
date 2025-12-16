package io.github.danielliu1123.deployer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
import java.util.Base64;
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
    public void run() throws Exception {
        deploy();
    }

    private void deploy() throws Exception {
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
        // random boundary
        String boundary = "----JavaBoundary" + UUID.randomUUID();

        // multipart body: headers + file bytes + end boundary
        String partHeaders = "--" + boundary + "\r\n" + "Content-Disposition: form-data; name=bundle; filename="
                + zipFile.getName() + "\r\n" + "Content-Type: application/octet-stream\r\n\r\n";

        byte[] fileBytes = Files.readAllBytes(zipFile.toPath());

        String endBoundary = "\r\n--" + boundary + "--\r\n";

        byte[] bodyBytes = createMultipartBody(partHeaders, fileBytes, endBoundary);

        // For WAIT_FOR_PUBLISHED, upload with USER_MANAGED and then poll status
        PublishingType publishingType = getPublishingType();
        String uploadPublishingType = publishingType == PublishingType.WAIT_FOR_PUBLISHED
                ? PublishingType.USER_MANAGED.name()
                : publishingType.name();

        var url = "https://central.sonatype.com/api/v1/publisher/upload?publishingType=%s"
                .formatted(uploadPublishingType);

        logger.lifecycle("Deploying to URL: " + url);

        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + getAuth())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();

        var httpClient = HttpClient.newHttpClient();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        logger.lifecycle("Response: ");
        logger.lifecycle("  status: " + response.statusCode());
        logger.lifecycle("  body: " + response.body());
        logger.lifecycle("  headers: " + response.headers().map());

        // If WAIT_FOR_PUBLISHED, extract deploymentId and poll until PUBLISHED
        if (publishingType == PublishingType.WAIT_FOR_PUBLISHED) {
            if (response.statusCode() == 201) {
                String deploymentId = extractDeploymentId(response.body());
                if (deploymentId != null) {
                    waitForPublished(httpClient, deploymentId);
                } else {
                    logger.lifecycle(
                            "Warning: Could not extract deploymentId from response. Cannot wait for PUBLISHED status.");
                }
            } else {
                logger.lifecycle("Warning: Upload failed with status " + response.statusCode()
                        + ". Cannot wait for PUBLISHED status.");
            }
        }
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

    private static byte[] createMultipartBody(String headers, byte[] fileBytes, String endBoundary) {
        byte[] headerBytes = headers.getBytes(StandardCharsets.UTF_8);
        byte[] endBytes = endBoundary.getBytes(StandardCharsets.UTF_8);

        byte[] result = new byte[headerBytes.length + fileBytes.length + endBytes.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, result, headerBytes.length, fileBytes.length);
        System.arraycopy(endBytes, 0, result, headerBytes.length + fileBytes.length, endBytes.length);

        return result;
    }

    /**
     * Extracts the deploymentId from the upload response body.
     * Expected format: {"deploymentId":"uuid","deploymentName":"filename",...}
     */
    private String extractDeploymentId(String responseBody) {
        Pattern pattern = Pattern.compile("\"deploymentId\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(responseBody);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Polls the deployment status until it reaches PUBLISHED state.
     * According to API docs, possible states are:
     * PENDING, VALIDATING, VALIDATED, PUBLISHING, PUBLISHED, FAILED
     */
    private void waitForPublished(HttpClient httpClient, String deploymentId) throws Exception {
        logger.lifecycle("\nWaiting for deployment to be PUBLISHED (deploymentId: " + deploymentId + ")...");

        String statusUrl = "https://central.sonatype.com/api/v1/publisher/status?id=" + deploymentId;
        int pollIntervalSeconds = 10;
        int maxAttempts = 1080; // 3 hours max (1080 * 10 seconds)
        int attempts = 0;

        while (attempts < maxAttempts) {
            attempts++;

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .header("Authorization", "Bearer " + getAuth())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warn("Status check failed with code " + response.statusCode() + ": " + response.body());
                Thread.sleep(pollIntervalSeconds * 1000L);
                continue;
            }

            String state = extractDeploymentState(response.body());
            logger.lifecycle("  [" + attempts + "] Current state: " + state);

            if ("PUBLISHED".equals(state)) {
                logger.lifecycle("\n✓ Deployment successfully PUBLISHED and available on Maven Central!");
                return;
            } else if ("FAILED".equals(state)) {
                logger.error("\n✗ Deployment FAILED. Response: {}", response.body());
                throw new RuntimeException("Deployment failed with state: FAILED");
            }

            // Continue polling for other states: PENDING, VALIDATING, VALIDATED, PUBLISHING
            Thread.sleep(pollIntervalSeconds * 1000L);
        }

        throw new RuntimeException("Timeout waiting for deployment to be PUBLISHED after "
                + (maxAttempts * pollIntervalSeconds / 60) + " minutes");
    }

    /**
     * Extracts the deploymentState from the status response body.
     * Expected format: {"deploymentId":"uuid","deploymentState":"STATE",...}
     */
    private String extractDeploymentState(String responseBody) {
        Pattern pattern = Pattern.compile("\"deploymentState\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(responseBody);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "UNKNOWN";
    }
}
