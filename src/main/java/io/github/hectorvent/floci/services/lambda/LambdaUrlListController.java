package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.LambdaUrlConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * AWS Lambda ListFunctionUrlConfigs: the URL configs of a function and of its aliases.
 */
@Path("/2021-10-31/functions/{FunctionName}/urls")
@Produces(MediaType.APPLICATION_JSON)
public class LambdaUrlListController {

    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaUrlListController(LambdaService lambdaService, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    public Response listFunctionUrlConfigs(@Context HttpHeaders headers,
                                           @PathParam("FunctionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        List<LambdaUrlConfig> configs = lambdaService.listFunctionUrlConfigs(region, functionName);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("FunctionUrlConfigs");
        for (LambdaUrlConfig config : configs) {
            items.addPOJO(config);
        }
        return Response.ok(root).build();
    }
}
