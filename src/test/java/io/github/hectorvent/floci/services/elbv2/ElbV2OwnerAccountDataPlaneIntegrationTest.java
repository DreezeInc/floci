package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

/**
 * A twelve-digit access key id selects that account, so everything below belongs to an account
 * other than the default one. The data plane answers requests outside any AWS request, where
 * account-aware state resolves to the default account unless it acts as the listener's owner.
 */
@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
class ElbV2OwnerAccountDataPlaneIntegrationTest {

    private static final String OWNER_ACCOUNT = "000000000002";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=" + OWNER_ACCOUNT
            + "/20260803/us-east-1/elasticloadbalancing/aws4_request";
    private static final int LISTENER_PORT = 7796;
    private static final String CLIENT_HOST = "client.example.test";

    @Inject
    Vertx vertx;

    @Test
    void forwardsForAnAccountOtherThanTheDefaultOne() throws Exception {
        HttpServer backend = vertx.createHttpServer()
                .requestHandler(request -> request.response().end(request.host()))
                .listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        String loadBalancerArn = null;
        String targetGroupArn = null;
        String listenerArn = null;
        try {
            loadBalancerArn = createLoadBalancer();
            targetGroupArn = createTargetGroup(backend.actualPort());
            registerTarget(targetGroupArn, backend.actualPort());
            listenerArn = createListener(loadBalancerArn, targetGroupArn);
            enableHostHeaderPreservation(loadBalancerArn);

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> given()
                    .baseUri("http://127.0.0.1")
                    .port(LISTENER_PORT)
                    .header("Host", CLIENT_HOST)
                .when()
                    .get("/")
                .then()
                    .statusCode(200)
                    .body(equalTo(CLIENT_HOST)));
        } finally {
            deleteListener(listenerArn);
            deleteTargetGroup(targetGroupArn);
            deleteLoadBalancer(loadBalancerArn);
            backend.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    private static String createLoadBalancer() {
        return given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "owner-account-lb")
                .formParam("Type", "application")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }

    private static String createTargetGroup(int backendPort) {
        return given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "owner-account-tg")
                .formParam("Protocol", "HTTP")
                .formParam("Port", backendPort)
                .formParam("TargetType", "ip")
                .formParam("HealthCheckEnabled", "false")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    private static void registerTarget(String targetGroupArn, int backendPort) {
        given()
                .formParam("Action", "RegisterTargets")
                .formParam("TargetGroupArn", targetGroupArn)
                .formParam("Targets.member.1.Id", "127.0.0.1")
                .formParam("Targets.member.1.Port", backendPort)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    private static String createListener(String loadBalancerArn, String targetGroupArn) {
        return given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", loadBalancerArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", LISTENER_PORT)
                .formParam("DefaultActions.member.1.Type", "forward")
                .formParam("DefaultActions.member.1.ForwardConfig.TargetGroups.member.1.TargetGroupArn", targetGroupArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
    }

    private static void enableHostHeaderPreservation(String loadBalancerArn) {
        given()
                .formParam("Action", "ModifyLoadBalancerAttributes")
                .formParam("LoadBalancerArn", loadBalancerArn)
                .formParam("Attributes.member.1.Key", "routing.http.preserve_host_header.enabled")
                .formParam("Attributes.member.1.Value", "true")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    private static void deleteListener(String listenerArn) {
        if (listenerArn != null) {
            given()
                    .formParam("Action", "DeleteListener")
                    .formParam("ListenerArn", listenerArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }

    private static void deleteTargetGroup(String targetGroupArn) {
        if (targetGroupArn != null) {
            given()
                    .formParam("Action", "DeleteTargetGroup")
                    .formParam("TargetGroupArn", targetGroupArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }

    private static void deleteLoadBalancer(String loadBalancerArn) {
        if (loadBalancerArn != null) {
            given()
                    .formParam("Action", "DeleteLoadBalancer")
                    .formParam("LoadBalancerArn", loadBalancerArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }
}
