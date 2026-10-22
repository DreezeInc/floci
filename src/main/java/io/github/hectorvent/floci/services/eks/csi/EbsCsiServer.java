package io.github.hectorvent.floci.services.eks.csi;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Serves the {@code ebs.csi.aws.com} driver's Identity, Controller and Node services, and the
 * kubelet's plugin Registration service, on one port. Each cluster's nodes bridge the driver's Unix
 * sockets to it.
 */
@ApplicationScoped
public class EbsCsiServer {

    private static final Logger LOG = Logger.getLogger(EbsCsiServer.class);

    private final EmulatorConfig config;
    private final EbsCsiNodes nodes;
    private final EbsCsiNodeDisk disk;
    private final Ec2Service ec2;
    private Server server;

    @Inject
    public EbsCsiServer(EmulatorConfig config, EbsCsiNodes nodes, EbsCsiNodeDisk disk, Ec2Service ec2) {
        this.config = config;
        this.nodes = nodes;
        this.disk = disk;
        this.ec2 = ec2;
    }

    void onStart(@Observes StartupEvent ignored) {
        EmulatorConfig.EksServiceConfig eks = config.services().eks();
        if (eks.enabled() && !eks.mock() && eks.emulateEbsStorage()) {
            start(eks.ebsCsiPort());
        }
    }

    void onStop(@Observes ShutdownEvent ignored) {
        stop();
    }

    synchronized void start(int port) {
        EbsCsiControllerService controller = new EbsCsiControllerService(nodes, disk, ec2);
        try {
            server = NettyServerBuilder.forAddress(new InetSocketAddress(port))
                    .intercept(new CsiCallerAddress())
                    .addService(new EbsCsiIdentityService())
                    .addService(controller)
                    .addService(new EbsCsiNodeService(nodes, disk, controller, EbsCsiServer::seedOf))
                    .addService(new KubeletRegistrationService())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("could not serve the EBS CSI driver on port " + port, e);
        }
        LOG.infov("Serving the {0} CSI driver on port {1}", EbsCsiIdentityService.DRIVER_NAME, String.valueOf(port()));
    }

    synchronized int port() {
        return server == null ? -1 : server.getPort();
    }

    synchronized void stop() {
        if (server == null) {
            return;
        }
        server.shutdown();
        try {
            server.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        server = null;
    }

    static Path seedOf(Volume volume) {
        return null;
    }
}
