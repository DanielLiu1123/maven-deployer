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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskAction;

/**
 *
 *
 * @author Freeman
 */
public class DeployTask extends DefaultTask {

    private final DeployerPluginExtension extension;
    private final String version;
    private final boolean isSnapshot;
    private final File projectDir;

    @Inject
    public DeployTask(Project project, DeployerPluginExtension extension) {
        this.extension = extension;
        this.version = project.getVersion().toString();
        this.isSnapshot = version.endsWith("-SNAPSHOT");
        this.projectDir = project.getProjectDir();
    }

    @TaskAction
    public void run() throws Exception {
        System.out.println("Deploying version: " + version + " (isSnapshot=" + isSnapshot + ")");

        if (isSnapshot) {
            System.out.println("Please use maven-publish's 'publish' task for snapshot deployment. Skipping.");
            return;
        }

        deploy();
    }

    private void deploy() throws Exception {
        List<Path> dirPaths =
                extension.getDirs().get().stream().map(File::toPath).toList();
        if (dirPaths.isEmpty()) {
            System.out.println("No dirs configured for deploying. Skipping.");
            return;
        }

        System.out.println("Configured dirs:");
        for (Path dirPath : dirPaths) {
            System.out.println("  - " + dirPath);
        }

        // Use gpg to sign all files in each directory
        for (Path dirPath : dirPaths) {
            try {
                GpgSigner.signDirectory(
                        dirPath,
                        extension.getSign().getSecretKey().get(),
                        extension.getSign().getPassphrase().get());
            } catch (Exception e) {
                throw new RuntimeException("Failed to sign artifacts in directory: " + dirPath, e);
            }
        }

        // package artifacts into a zip file
        var bundleName = "%s-%s-bundle.zip".formatted(projectDir.getName(), version);
        var bundlePath = Path.of(projectDir.getAbsolutePath(), bundleName);
        File zipFile = createBundle(dirPaths, bundlePath);

        System.out.println("Deploy bundle: " + zipFile.getName());

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

        var url = isSnapshot
                ? "https://central.sonatype.com/repository/maven-snapshots" // Never happen for now
                : "https://central.sonatype.com/api/v1/publisher/upload?publishingType=%s"
                        .formatted(getPublishingType().name());

        System.out.println("Deploying to URL: " + url);

        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + getAuth())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();

        var httpClient = HttpClient.newHttpClient();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("Response: ");
        System.out.println("  status: " + response.statusCode());
        System.out.println("  body: " + response.body());
        System.out.println("  headers: " + response.headers().map());
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
}
