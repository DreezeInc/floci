package io.github.hectorvent.floci.services.eks.csi;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** Makes the IP address a CSI call came from available to the service handling it. */
final class CsiCallerAddress implements ServerInterceptor {

    static final Context.Key<String> ADDRESS = Context.key("floci-ebs-csi-caller-address");

    static String current() {
        return ADDRESS.get();
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                         ServerCallHandler<Q, R> next) {
        SocketAddress remote = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        String address = remote instanceof InetSocketAddress inet && inet.getAddress() != null
                ? inet.getAddress().getHostAddress()
                : null;
        return Contexts.interceptCall(Context.current().withValue(ADDRESS, address), call, headers, next);
    }
}
