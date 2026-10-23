package io.github.hectorvent.floci.services.eks;

import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerPresence;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One pull-through cache container per public registry the clusters pull from. Each is the ECR
 * backing registry's image in proxy mode on the cluster network; a node's containerd takes it as
 * the registry's mirror, so the second pull of an image is served from the cache's own storage.
 * The containers start with the first cluster, are adopted by name afterwards, and keep their
 * blobs in a named volume each.
 */
@ApplicationScoped
public class EksImageCache {

    private static final Logger LOG = Logger.getLogger(EksImageCache.class);
    static final int REGISTRY_PORT = 5000;
    static final String DOCKER_HUB = "docker.io";
    static final String DOCKER_HUB_REMOTE_URL = "https://registry-1.docker.io";
    static final String SERVICE = "eks-image-cache";
    private static final String REGISTRY_DATA_PATH = "/var/lib/registry";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final Map<String, String> containerIds = new LinkedHashMap<>();
    private final Map<String, String> endpoints = new LinkedHashMap<>();

    @Inject
    public EksImageCache(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                         EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
    }

    /**
     * The mirror endpoint of each cached registry, by registry host, in the configured order. Starts
     * or adopts the cache containers; a registry whose cache cannot start is left out with a warning,
     * so its pulls go to the registry itself. Empty when the cache is off.
     */
    public synchronized Map<String, String> endpoints() {
        if (!config.services().eks().imageCache()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String host : config.services().eks().imageCacheRegistries()) {
            try {
                result.put(host, endpoint(host));
            } catch (RuntimeException e) {
                LOG.warnv("Images from {0} are not cached: the cache container could not start: {1}", host,
                        e.getMessage());
            }
        }
        return result;
    }

    private String endpoint(String host) {
        String containerId = containerIds.get(host);
        if (containerId != null) {
            ContainerPresence presence = lifecycleManager.presenceOf(containerId);
            if (presence == ContainerPresence.RUNNING || presence == ContainerPresence.UNKNOWN) {
                return endpoints.get(host);
            }
            containerIds.remove(host);
            endpoints.remove(host);
        }
        String name = containerName(host);
        Optional<Container> existing = lifecycleManager.findByName(name);
        if (existing.isPresent() && "running".equalsIgnoreCase(existing.get().getState())) {
            containerId = existing.get().getId();
            LOG.infov("Adopted the running image cache {0} for {1}", name, host);
        } else {
            ContainerStorageHelper.removeStaleContainer(config, lifecycleManager, name);
            String volumeName = ContainerStorageHelper.dockerName(config, baseName(host));
            lifecycleManager.ensureVolume(volumeName);
            containerId = lifecycleManager.createAndStart(spec(name, volumeName, host)).containerId();
            LOG.infov("Started the image cache {0} for {1}", name, host);
        }
        String ip = lifecycleManager.resolveContainerNetworkIp(containerId,
                config.services().eks().dockerNetwork().orElse(null));
        if (ip == null || ip.isBlank()) {
            throw new IllegalStateException("container " + name + " has no address on the cluster network");
        }
        String endpoint = "http://" + ip + ":" + REGISTRY_PORT;
        containerIds.put(host, containerId);
        endpoints.put(host, endpoint);
        return endpoint;
    }

    private ContainerSpec spec(String name, String volumeName, String host) {
        ContainerBuilder.Builder builder = containerBuilder.newContainer(config.services().ecr().registryImage())
                .withName(name)
                .withEnv(List.of(
                        "REGISTRY_PROXY_REMOTEURL=" + remoteUrl(host),
                        "REGISTRY_HTTP_ADDR=0.0.0.0:" + REGISTRY_PORT))
                .withDockerNetwork(config.services().eks().dockerNetwork())
                .withNamedVolume(volumeName, REGISTRY_DATA_PATH)
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(SERVICE, host, null, null));
        // The cache resolves the registry's name exactly as the nodes it serves do.
        if (config.services().eks().embeddedDns()) {
            builder.withEmbeddedDns();
        }
        return builder.build();
    }

    String containerName(String host) {
        return ContainerStorageHelper.dockerName(config, baseName(host));
    }

    private static String baseName(String host) {
        return SERVICE + "-" + host.replace('.', '-');
    }

    /** The registry's API origin; Docker Hub's lives on another host than its image names carry. */
    static String remoteUrl(String host) {
        return DOCKER_HUB.equals(host) ? DOCKER_HUB_REMOTE_URL : "https://" + host;
    }
}
