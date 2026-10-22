package io.github.hectorvent.floci.services.backup;

import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.SignedServiceRoute;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.util.function.Predicate;

/** Keeps Backup's newer top-level paths from claiming S3 path-style requests for same-named buckets. */
@Provider
@PreMatching
public class BackupRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_backup";

    private static final Predicate<String> ROUTED_PATHS = SignedServiceRoute.under("/resources")
            .or(SignedServiceRoute.under("/copy-jobs"))
            .or(SignedServiceRoute.under("/logically-air-gapped-backup-vaults"));

    private final SignedServiceRoute route;

    @Inject
    public BackupRouteFilter(ResolvedServiceCatalog catalog) {
        this.route = new SignedServiceRoute(catalog, "backup", INTERNAL_PREFIX, ROUTED_PATHS);
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
