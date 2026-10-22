package io.github.hectorvent.floci.services.eks.csi;

import io.github.hectorvent.floci.services.eks.csi.registration.InfoRequest;
import io.github.hectorvent.floci.services.eks.csi.registration.PluginInfo;
import io.github.hectorvent.floci.services.eks.csi.registration.RegistrationGrpc;
import io.github.hectorvent.floci.services.eks.csi.registration.RegistrationStatus;
import io.github.hectorvent.floci.services.eks.csi.registration.RegistrationStatusResponse;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

/**
 * Registers the driver with each node's kubelet, the job the node-driver-registrar sidecar does
 * beside the real driver.
 */
final class KubeletRegistrationService extends RegistrationGrpc.RegistrationImplBase {

    private static final Logger LOG = Logger.getLogger(KubeletRegistrationService.class);

    static final String CSI_PLUGIN_TYPE = "CSIPlugin";
    static final String CSI_SOCKET = "/var/lib/kubelet/plugins/" + EbsCsiIdentityService.DRIVER_NAME + "/csi.sock";
    static final String CSI_VERSION = "1.0.0";

    @Override
    public void getInfo(InfoRequest request, StreamObserver<PluginInfo> observer) {
        CsiCalls.answer("GetInfo", observer, () -> PluginInfo.newBuilder()
                .setType(CSI_PLUGIN_TYPE)
                .setName(EbsCsiIdentityService.DRIVER_NAME)
                .setEndpoint(CSI_SOCKET)
                .addSupportedVersions(CSI_VERSION)
                .build());
    }

    @Override
    public void notifyRegistrationStatus(RegistrationStatus request,
                                         StreamObserver<RegistrationStatusResponse> observer) {
        CsiCalls.answer("NotifyRegistrationStatus", observer, () -> {
            String address = CsiCallerAddress.current();
            if (request.getPluginRegistered()) {
                LOG.infov("The kubelet at {0} registered the {1} CSI driver", address,
                        EbsCsiIdentityService.DRIVER_NAME);
            } else {
                LOG.warnv("The kubelet at {0} refused the {1} CSI driver: {2}", address,
                        EbsCsiIdentityService.DRIVER_NAME, request.getError());
            }
            return RegistrationStatusResponse.getDefaultInstance();
        });
    }
}
