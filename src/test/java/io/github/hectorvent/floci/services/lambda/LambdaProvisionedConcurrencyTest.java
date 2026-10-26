package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Nothing can put a provisioned concurrency config, so reads answer as AWS does for a version or
 * alias without one, and a client walking every function sees the not-found code it handles.
 */
class LambdaProvisionedConcurrencyTest {

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
        Map<String, Object> request = new HashMap<>();
        request.put("FunctionName", "pcc-fn");
        request.put("Runtime", "nodejs20.x");
        request.put("Role", "arn:aws:iam::" + ACCOUNT + ":role/test-role");
        request.put("Handler", "index.handler");
        service.createFunction(REGION, request);
        service.publishVersion(REGION, "pcc-fn", "v1");
        service.createAlias(REGION, "pcc-fn", "live", "1", null, null);
    }

    @Test
    void aVersionAndAnAliasWithoutAConfigAreNotFound() {
        AwsException version = service.provisionedConcurrencyConfigNotFound(REGION, "pcc-fn", "1");
        AwsException alias = service.provisionedConcurrencyConfigNotFound(REGION, "pcc-fn", "live");

        assertEquals("ProvisionedConcurrencyConfigNotFoundException", version.getErrorCode());
        assertEquals(404, version.getHttpStatus());
        assertEquals("ProvisionedConcurrencyConfigNotFoundException", alias.getErrorCode());
    }

    @Test
    void anUnknownFunctionOrQualifierIsRejectedBeforeTheConfigLookup() {
        AwsException function = assertThrows(AwsException.class,
                () -> service.provisionedConcurrencyConfigNotFound(REGION, "no-such-fn", "1"));
        AwsException alias = assertThrows(AwsException.class,
                () -> service.provisionedConcurrencyConfigNotFound(REGION, "pcc-fn", "staging"));

        assertEquals("ResourceNotFoundException", function.getErrorCode());
        assertEquals("ResourceNotFoundException", alias.getErrorCode());
    }

    @Test
    void latestAndAMissingQualifierAreRejected() {
        AwsException latest = assertThrows(AwsException.class,
                () -> service.provisionedConcurrencyConfigNotFound(REGION, "pcc-fn", "$LATEST"));
        AwsException missing = assertThrows(AwsException.class,
                () -> service.provisionedConcurrencyConfigNotFound(REGION, "pcc-fn", null));

        assertEquals("InvalidParameterValueException", latest.getErrorCode());
        assertEquals("ValidationException", missing.getErrorCode());
    }
}
