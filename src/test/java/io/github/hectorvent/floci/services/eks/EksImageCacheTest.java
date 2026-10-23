package io.github.hectorvent.floci.services.eks;

import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerPresence;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EksImageCacheTest {

    private ContainerBuilder containerBuilder;
    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private EmulatorConfig.EksServiceConfig eks;
    private EksImageCache cache;

    @BeforeEach
    void setUp() {
        containerBuilder = Mockito.mock(ContainerBuilder.class);
        builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("registry:2"));

        lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.findByName(anyString())).thenReturn(Optional.empty());
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("cache-1", Map.of()));
        when(lifecycleManager.resolveContainerNetworkIp("cache-1", "bridge")).thenReturn("172.17.0.9");
        when(lifecycleManager.presenceOf("cache-1")).thenReturn(ContainerPresence.RUNNING);

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        eks = Mockito.mock(EmulatorConfig.EksServiceConfig.class);
        EmulatorConfig.EcrServiceConfig ecr = Mockito.mock(EmulatorConfig.EcrServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.eks()).thenReturn(eks);
        when(services.ecr()).thenReturn(ecr);
        when(eks.imageCache()).thenReturn(true);
        when(eks.imageCacheRegistries()).thenReturn(List.of("docker.io"));
        when(eks.dockerNetwork()).thenReturn(Optional.of("bridge"));
        when(eks.embeddedDns()).thenReturn(true);
        when(ecr.registryImage()).thenReturn("registry:2");
        cache = new EksImageCache(containerBuilder, lifecycleManager, config);
    }

    @Test
    void startsAProxyRegistryOnTheClusterNetworkAndMirrorsToItsAddress() {
        Map<String, String> endpoints = cache.endpoints();

        assertEquals(Map.of("docker.io", "http://172.17.0.9:5000"), endpoints);
        verify(containerBuilder).newContainer("registry:2");
        verify(builder).withName("floci-aws-eks-image-cache-docker-io");
        verify(builder).withEnv(List.of("REGISTRY_PROXY_REMOTEURL=https://registry-1.docker.io",
                "REGISTRY_HTTP_ADDR=0.0.0.0:5000"));
        verify(builder).withDockerNetwork(Optional.of("bridge"));
        verify(builder).withNamedVolume("floci-aws-eks-image-cache-docker-io", "/var/lib/registry");
        verify(builder).withEmbeddedDns();
        verify(lifecycleManager).ensureVolume("floci-aws-eks-image-cache-docker-io");
        verify(lifecycleManager).removeIfExists("floci-aws-eks-image-cache-docker-io");
    }

    @Test
    void reusesTheRunningCacheOnTheNextCluster() {
        cache.endpoints();
        Map<String, String> again = cache.endpoints();

        assertEquals("http://172.17.0.9:5000", again.get("docker.io"));
        verify(lifecycleManager, times(1)).createAndStart(any());
        verify(lifecycleManager, times(1)).resolveContainerNetworkIp(anyString(), anyString());
    }

    @Test
    void replacesACacheThatIsGone() {
        cache.endpoints();
        when(lifecycleManager.presenceOf("cache-1")).thenReturn(ContainerPresence.ABSENT);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("cache-2", Map.of()));
        when(lifecycleManager.resolveContainerNetworkIp("cache-2", "bridge")).thenReturn("172.17.0.12");

        Map<String, String> endpoints = cache.endpoints();

        assertEquals("http://172.17.0.12:5000", endpoints.get("docker.io"));
        verify(lifecycleManager, times(2)).createAndStart(any());
    }

    @Test
    void adoptsARunningCacheByName() {
        Container running = Mockito.mock(Container.class);
        when(running.getState()).thenReturn("running");
        when(running.getId()).thenReturn("cache-9");
        when(lifecycleManager.findByName("floci-aws-eks-image-cache-docker-io")).thenReturn(Optional.of(running));
        when(lifecycleManager.resolveContainerNetworkIp("cache-9", "bridge")).thenReturn("172.17.0.4");

        assertEquals("http://172.17.0.4:5000", cache.endpoints().get("docker.io"));
        verify(lifecycleManager, never()).createAndStart(any());
        verify(lifecycleManager, never()).removeIfExists(anyString());
    }

    @Test
    void leavesOutARegistryWhoseCacheCannotStartAndKeepsTheOthers() {
        when(eks.imageCacheRegistries()).thenReturn(List.of("ghcr.io", "docker.io"));
        when(lifecycleManager.createAndStart(any()))
                .thenThrow(new IllegalStateException("no docker"))
                .thenReturn(new ContainerInfo("cache-1", Map.of()));

        Map<String, String> endpoints = cache.endpoints();

        assertEquals(Map.of("docker.io", "http://172.17.0.9:5000"), endpoints);
        ArgumentCaptor<List<String>> env = ArgumentCaptor.captor();
        verify(builder, times(2)).withEnv(env.capture());
        assertTrue(env.getAllValues().get(0).contains("REGISTRY_PROXY_REMOTEURL=https://ghcr.io"));
    }

    @Test
    void isEmptyWhenOff() {
        when(eks.imageCache()).thenReturn(false);

        assertEquals(Map.of(), cache.endpoints());
        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void mirrorsEachRegistryToItsOwnApiOrigin() {
        assertEquals("https://registry-1.docker.io", EksImageCache.remoteUrl("docker.io"));
        assertEquals("https://ghcr.io", EksImageCache.remoteUrl("ghcr.io"));
        assertEquals("https://registry.k8s.io", EksImageCache.remoteUrl("registry.k8s.io"));
    }
}
