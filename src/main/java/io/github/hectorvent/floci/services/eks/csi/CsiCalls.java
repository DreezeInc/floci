package io.github.hectorvent.floci.services.eks.csi;

import io.github.hectorvent.floci.core.common.AwsException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

import java.util.function.Supplier;

/** Answers a unary CSI call and turns each failure into the gRPC status the CSI spec expects. */
final class CsiCalls {

    private static final Logger LOG = Logger.getLogger(CsiCalls.class);

    private CsiCalls() {
    }

    static <T> void answer(String method, StreamObserver<T> observer, Supplier<T> body) {
        T response;
        try {
            response = body.get();
        } catch (StatusRuntimeException e) {
            LOG.debugv("CSI {0} refused: {1}", method, e.getStatus());
            observer.onError(e);
            return;
        } catch (AwsException e) {
            LOG.warnv("CSI {0} failed: {1}: {2}", method, e.getErrorCode(), e.getMessage());
            observer.onError(statusOf(e).withDescription(e.getErrorCode() + ": " + e.getMessage()).asRuntimeException());
            return;
        } catch (RuntimeException e) {
            LOG.warnv(e, "CSI {0} failed", method);
            observer.onError(Status.INTERNAL.withDescription(e.getMessage()).withCause(e).asRuntimeException());
            return;
        }
        observer.onNext(response);
        observer.onCompleted();
    }

    static StatusRuntimeException invalid(String message) {
        return Status.INVALID_ARGUMENT.withDescription(message).asRuntimeException();
    }

    static EbsCsiNode callingNode(EbsCsiNodes nodes) {
        String address = CsiCallerAddress.current();
        return nodes.nodeAt(address).orElseThrow(() -> Status.FAILED_PRECONDITION
                .withDescription("no EKS cluster node answers on " + address)
                .asRuntimeException());
    }

    private static Status statusOf(AwsException e) {
        String code = e.getErrorCode() == null ? "" : e.getErrorCode();
        if (code.endsWith(".NotFound")) {
            return Status.NOT_FOUND;
        }
        if (e.getHttpStatus() >= 400 && e.getHttpStatus() < 500) {
            return Status.INVALID_ARGUMENT;
        }
        return Status.INTERNAL;
    }
}
