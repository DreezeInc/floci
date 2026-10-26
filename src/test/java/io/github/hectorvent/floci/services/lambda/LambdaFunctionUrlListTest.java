package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.LambdaUrlConfig;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ListFunctionUrlConfigs returns every URL config a function carries, on the function itself and on
 * its aliases, and an empty list for a function without one, as a scanner walking all functions expects.
 */
class LambdaFunctionUrlListTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private LambdaService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        service = new LambdaService(new LambdaFunctionStore(new InMemoryStorage<String, LambdaFunction>()),
                new WarmPool(), new CodeStore(Path.of("target/test-data/lambda-code")), new ZipExtractor(),
                config, new RegionResolver(REGION, ACCOUNT), storageFactory);
    }

    private LambdaFunction createFunction(String name) {
        Map<String, Object> request = new HashMap<>();
        request.put("FunctionName", name);
        request.put("Runtime", "nodejs20.x");
        request.put("Role", "arn:aws:iam::" + ACCOUNT + ":role/test-role");
        request.put("Handler", "index.handler");
        return service.createFunction(REGION, request);
    }

    @Test
    void listsTheFunctionsOwnConfigAndItsAliasesConfigs() {
        LambdaFunction fn = createFunction("url-list-fn");
        service.createFunctionUrlConfig(REGION, "url-list-fn", null, Map.of("AuthType", "NONE"));
        service.publishVersion(REGION, "url-list-fn", "v1");
        service.createAlias(REGION, "url-list-fn", "live", "1", null, null);
        service.createFunctionUrlConfig(REGION, "url-list-fn", "live", Map.of("AuthType", "AWS_IAM"));

        List<LambdaUrlConfig> configs = service.listFunctionUrlConfigs(REGION, "url-list-fn");

        assertEquals(List.of(fn.getFunctionArn(), fn.getFunctionArn() + ":live"),
                configs.stream().map(LambdaUrlConfig::getFunctionArn).toList());
        assertEquals(List.of("NONE", "AWS_IAM"),
                configs.stream().map(LambdaUrlConfig::getAuthType).toList());
    }

    @Test
    void isEmptyForAFunctionWithoutAUrl() {
        createFunction("url-less-fn");
        service.publishVersion(REGION, "url-less-fn", "v1");
        service.createAlias(REGION, "url-less-fn", "live", "1", null, null);

        assertTrue(service.listFunctionUrlConfigs(REGION, "url-less-fn").isEmpty());
    }

    @Test
    void rejectsAnUnknownFunction() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.listFunctionUrlConfigs(REGION, "no-such-fn"));

        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }
}
