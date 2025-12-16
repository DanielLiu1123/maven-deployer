package io.github.danielliu1123.deployer;

/**
 * Publishing type for uploading deployment bundles to Maven Central.
 *
 * <p> Default is {@link #USER_MANAGED}.
 *
 * @author Freeman
 * @see <a href="https://central.sonatype.org/publish/publish-portal-api/#uploading-a-deployment-bundle>">Upload Bundle</a>
 */
public enum PublishingType {
    AUTOMATIC,
    USER_MANAGED,
    /**
     * Wait for the deployment to be PUBLISHED (fully available on Maven Central).
     * This option uploads the bundle with USER_MANAGED publishing type and then polls
     * the deployment status until it reaches PUBLISHED state.
     */
    WAIT_FOR_PUBLISHED
}
