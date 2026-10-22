package io.github.hectorvent.floci.services.eks.csi;

import com.google.protobuf.BoolValue;
import io.github.hectorvent.floci.services.eks.csi.v1.GetPluginCapabilitiesRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.GetPluginCapabilitiesResponse;
import io.github.hectorvent.floci.services.eks.csi.v1.GetPluginInfoRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.GetPluginInfoResponse;
import io.github.hectorvent.floci.services.eks.csi.v1.IdentityGrpc;
import io.github.hectorvent.floci.services.eks.csi.v1.PluginCapability;
import io.github.hectorvent.floci.services.eks.csi.v1.ProbeRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.ProbeResponse;
import io.grpc.stub.StreamObserver;

/** Names the plugin and reports a controller service and no volume topology. */
final class EbsCsiIdentityService extends IdentityGrpc.IdentityImplBase {

    static final String DRIVER_NAME = "ebs.csi.aws.com";
    static final String VENDOR_VERSION = "floci";

    @Override
    public void getPluginInfo(GetPluginInfoRequest request, StreamObserver<GetPluginInfoResponse> observer) {
        CsiCalls.answer("GetPluginInfo", observer, () -> GetPluginInfoResponse.newBuilder()
                .setName(DRIVER_NAME)
                .setVendorVersion(VENDOR_VERSION)
                .build());
    }

    @Override
    public void getPluginCapabilities(GetPluginCapabilitiesRequest request,
                                      StreamObserver<GetPluginCapabilitiesResponse> observer) {
        CsiCalls.answer("GetPluginCapabilities", observer, () -> GetPluginCapabilitiesResponse.newBuilder()
                .addCapabilities(PluginCapability.newBuilder()
                        .setService(PluginCapability.Service.newBuilder()
                                .setType(PluginCapability.Service.Type.CONTROLLER_SERVICE)))
                .build());
    }

    @Override
    public void probe(ProbeRequest request, StreamObserver<ProbeResponse> observer) {
        CsiCalls.answer("Probe", observer, () -> ProbeResponse.newBuilder()
                .setReady(BoolValue.of(true))
                .build());
    }
}
