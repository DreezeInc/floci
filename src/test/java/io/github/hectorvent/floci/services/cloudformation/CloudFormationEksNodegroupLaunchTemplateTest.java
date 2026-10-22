package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.UserDataPipeline;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.ec2.AmiImageResolver;
import io.github.hectorvent.floci.services.ec2.Ec2ContainerManager;
import io.github.hectorvent.floci.services.ec2.Ec2ImageCatalog;
import io.github.hectorvent.floci.services.ec2.Ec2InstanceTypeCatalog;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.services.eks.EksAccessEntryService;
import io.github.hectorvent.floci.services.eks.EksClusterManager;
import io.github.hectorvent.floci.services.eks.EksOidcService;
import io.github.hectorvent.floci.services.eks.EksPodIdentityAssociationService;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import io.github.hectorvent.floci.services.eks.model.NodegroupStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A launch template with nodeadm-only user data, provisioned onto a running (non-mock) cluster. */
class CloudFormationEksNodegroupLaunchTemplateTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String STACK = "demo-stack";
    private static final String CLUSTER = "demo-cluster";
    private static final String NODEGROUP = "demo-nodes";
    private static final String NODEADM_ONLY_USER_DATA = """
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="BOUNDARY"

            --BOUNDARY
            Content-Type: application/node.eks.aws

            ---
            apiVersion: node.eks.aws/v1alpha1
            kind: NodeConfig
            spec:
              kubelet:
                config:
                  maxPods: 110

            --BOUNDARY--
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final DockerClient docker = mock(DockerClient.class);
    private final List<String> restartedFrom = new ArrayList<>();
    private final List<String> userDataApplied = new ArrayList<>();
    private final EksClusterManager clusterManager = mock(EksClusterManager.class, this::clusterManagerCall);
    private final Map<String, String> physicalIds = new HashMap<>();
    private final Map<String, Map<String, String>> attributes = new HashMap<>();
    private EksService eksService;
    private CfnResourceDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        Ec2Service ec2Service = realEc2Service();
        eksService = new EksService(inMemoryStorage(), realModeConfig(), new RegionResolver(REGION, ACCOUNT),
                clusterManager, ec2Service, new EksOidcService(inMemoryStorage(), mapper),
                mock(EksAccessEntryService.class), mock(EksPodIdentityAssociationService.class));
        dispatcher = CfnProvisionerFixture.builder()
                .objectMapper(mapper)
                .ec2(ec2Service)
                .eks(eksService)
                .build();
    }

    /** Package-private members cannot be stubbed from this package, so the mock answers by name. */
    private Object clusterManagerCall(InvocationOnMock inv) throws Throwable {
        return switch (inv.getMethod().getName()) {
            case "tryStartCluster" -> true;
            case "selectNodeInstanceType" -> inv.getArgument(0);
            case "restartForNodeCapacity" -> {
                restartedFrom.add(inv.getArgument(1));
                // The readiness poller's part: the restarted node is up again.
                inv.<Cluster>getArgument(0).setStatus(ClusterStatus.ACTIVE);
                yield true;
            }
            case "executeUserData" -> {
                userDataApplied.add(inv.getArgument(2));
                yield UserDataPipeline.executeUserData(docker, "k3s-demo", "test", inv.getArgument(2),
                        null, null, null);
            }
            default -> RETURNS_DEFAULTS.answer(inv);
        };
    }

    @Test
    void nodeadmOnlyUserDataLeavesNodegroupAndClusterActiveWithoutRunningAScriptOrRestartingTheNode()
            throws Exception {
        runningCluster("t3.medium");

        StackResource template = provisionLaunchTemplate();
        StackResource nodes = provisionNodegroup();

        assertEquals("CREATE_COMPLETE", nodes.getStatus(), nodes.getStatusReason());
        Nodegroup described = eksService.describeNodeGroup(CLUSTER, NODEGROUP);
        assertEquals(NodegroupStatus.ACTIVE, described.getStatus());
        assertEquals(Map.of("id", template.getPhysicalId(), "version", "1"), described.getLaunchTemplate());
        assertEquals(ClusterStatus.ACTIVE, eksService.describeCluster(CLUSTER).getStatus());
        assertEquals(1, userDataApplied.size());
        assertTrue(userDataApplied.getFirst().contains("Content-Type: application/node.eks.aws"),
                userDataApplied.getFirst());
        verifyNoInteractions(docker);
        assertEquals(List.of(), restartedFrom);
    }

    @Test
    void firstNodegroupCapacityChangeStillRestartsTheNodeOnceAndUserDataAddsNoSecondRestart() throws Exception {
        runningCluster(null);

        provisionLaunchTemplate();
        StackResource nodes = provisionNodegroup();

        assertEquals("CREATE_COMPLETE", nodes.getStatus(), nodes.getStatusReason());
        assertEquals(NodegroupStatus.ACTIVE, eksService.describeNodeGroup(CLUSTER, NODEGROUP).getStatus());
        assertEquals(ClusterStatus.ACTIVE, eksService.describeCluster(CLUSTER).getStatus());
        assertEquals(List.of("m5.large"), restartedFrom);
        assertEquals(1, userDataApplied.size());
        verifyNoInteractions(docker);
    }

    /** The cluster as the readiness poller leaves it once its k3s node is up. */
    private Cluster runningCluster(String nodeInstanceType) {
        CreateClusterRequest request = new CreateClusterRequest();
        request.setName(CLUSTER);
        request.setRoleArn("arn:aws:iam::" + ACCOUNT + ":role/eks-cluster-role");
        Cluster cluster = eksService.createCluster(request);
        cluster.setContainerId("k3s-demo");
        cluster.setNodeInstanceType(nodeInstanceType);
        cluster.setStatus(ClusterStatus.ACTIVE);
        return cluster;
    }

    private StackResource provisionLaunchTemplate() throws Exception {
        String userData = Base64.getEncoder().encodeToString(NODEADM_ONLY_USER_DATA.getBytes(StandardCharsets.UTF_8));
        StackResource template = dispatcher.provision("NodeLaunchTemplate", "AWS::EC2::LaunchTemplate", props("""
                {"LaunchTemplateData": {
                   "UserData": "%s",
                   "SecurityGroupIds": ["sg-0123456789abcdef0"],
                   "BlockDeviceMappings": [{"DeviceName": "/dev/xvda", "Ebs": {"VolumeSize": 40, "VolumeType": "gp3"}}],
                   "MetadataOptions": {"HttpTokens": "required", "HttpPutResponseHopLimit": 2}}}
                """.formatted(userData)), engine(), REGION, ACCOUNT, STACK);
        assertEquals("CREATE_COMPLETE", template.getStatus(), template.getStatusReason());
        physicalIds.put("NodeLaunchTemplate", template.getPhysicalId());
        attributes.put("NodeLaunchTemplate", template.getAttributes());
        return template;
    }

    private StackResource provisionNodegroup() throws Exception {
        return dispatcher.provision("Nodes", "AWS::EKS::Nodegroup", props("""
                {"ClusterName": "%s", "NodegroupName": "%s",
                 "NodeRole": "arn:aws:iam::%s:role/eks-node-role", "Subnets": ["subnet-0123456789abcdef0"],
                 "LaunchTemplate": {"Id": {"Ref": "NodeLaunchTemplate"},
                                    "Version": {"Fn::GetAtt": ["NodeLaunchTemplate", "LatestVersionNumber"]}},
                 "InstanceTypes": ["t3.medium"], "AmiType": "AL2023_x86_64_STANDARD", "CapacityType": "ON_DEMAND",
                 "ScalingConfig": {"MinSize": 1, "MaxSize": 3, "DesiredSize": 3},
                 "Labels": {"role": "worker"}, "Tags": {"team": "platform"}}
                """.formatted(CLUSTER, NODEGROUP, ACCOUNT)), engine(), REGION, ACCOUNT, STACK);
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine(ACCOUNT, REGION, STACK, "stack/" + STACK, Map.of(), physicalIds,
                attributes, Map.of(), Map.of(), mapper, (Function<String, String>) name -> null);
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static StorageFactory inMemoryStorage() {
        return new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory(ACCOUNT);
            }
        };
    }

    private static Ec2Service realEc2Service() {
        EmulatorConfig ec2Config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2ServiceConfig = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(ec2Config.defaultAccountId()).thenReturn(ACCOUNT);
        when(ec2Config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2ServiceConfig);
        when(ec2ServiceConfig.mock()).thenReturn(true);
        return new Ec2Service(ec2Config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                inMemoryStorage());
    }

    private static EmulatorConfig realModeConfig() {
        EmulatorConfig.EksServiceConfig eksConfig = proxy(EmulatorConfig.EksServiceConfig.class,
                (p, method, args) -> switch (method.getName()) {
                    case "enabled" -> true;
                    case "mock" -> false;
                    case "apiServerBasePort" -> 6500;
                    default -> defaultValue(method);
                });
        EmulatorConfig.ServicesConfig services = proxy(EmulatorConfig.ServicesConfig.class,
                (p, method, args) -> "eks".equals(method.getName()) ? eksConfig : defaultValue(method));
        return proxy(EmulatorConfig.class, (p, method, args) -> switch (method.getName()) {
            case "defaultRegion" -> REGION;
            case "defaultAccountId" -> ACCOUNT;
            case "services" -> services;
            default -> defaultValue(method);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == Optional.class) {
            return Optional.empty();
        }
        if (returnType == List.class) {
            return List.of();
        }
        return null;
    }
}
