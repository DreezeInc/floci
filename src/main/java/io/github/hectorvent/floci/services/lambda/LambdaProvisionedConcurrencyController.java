package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * AWS Lambda provisioned concurrency reads. Get and List share one route; {@code ?List=ALL} selects the list.
 * Nothing can put a config, so the list is empty and the get is not found for every existing function.
 */
@Path("/2019-09-30/functions/{FunctionName}/provisioned-concurrency")
@Produces(MediaType.APPLICATION_JSON)
public class LambdaProvisionedConcurrencyController {

    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaProvisionedConcurrencyController(LambdaService lambdaService, RegionResolver regionResolver,
                                                  ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    public Response getOrListProvisionedConcurrencyConfigs(@Context HttpHeaders headers,
                                                           @PathParam("FunctionName") String functionName,
                                                           @QueryParam("Qualifier") String qualifier,
                                                           @QueryParam("List") String list) {
        String region = regionResolver.resolveRegion(headers);
        if ("ALL".equals(list)) {
            lambdaService.getFunction(region, functionName);
            ObjectNode root = objectMapper.createObjectNode();
            root.putArray("ProvisionedConcurrencyConfigs");
            return Response.ok(root).build();
        }
        throw lambdaService.provisionedConcurrencyConfigNotFound(region, functionName, qualifier);
    }
}
