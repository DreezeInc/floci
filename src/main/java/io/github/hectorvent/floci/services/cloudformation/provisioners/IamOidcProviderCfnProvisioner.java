package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.OpenIDConnectProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::IAM::OIDCProvider}, backed by {@link IamService}.
 * {@code Ref} and {@code Fn::GetAtt Arn} are the provider ARN, matching AWS.
 *
 * <p>Url is createOnly: an update that keeps it reuses the provider it already has (ClientIdList,
 * ThumbprintList and Tags are not reconciled), and a changed Url is refused as a replacement.
 */
@ApplicationScoped
public class IamOidcProviderCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(IamOidcProviderCfnProvisioner.class);
    private static final String TYPE = "AWS::IAM::OIDCProvider";
    private static final String NOT_FOUND = "NoSuchEntity";
    private static final String SCHEME = "https://";

    private final IamService iamService;

    @Inject
    public IamOidcProviderCfnProvisioner(IamService iamService) {
        this.iamService = iamService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String url = ctx.resolveRequired(props, "Url", TYPE);

        if (ctx.isUpdate()) {
            OpenIDConnectProvider existing = findProvider(ctx.priorPhysicalId());
            if (existing != null) {
                // The service stores the URL without its scheme, which is also how AWS reports it.
                if (!withoutScheme(url).equals(existing.getUrl())) {
                    throw CfnExceptions.replacementNotSupported("Url");
                }
                record(r, existing);
                return;
            }
        }

        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        record(r, iamService.createOpenIDConnectProvider(url,
                ctx.resolveStringList(props, "ClientIdList"),
                ctx.resolveStringList(props, "ThumbprintList"),
                tags.isEmpty() ? null : tags));
    }

    private static String withoutScheme(String url) {
        return url.startsWith(SCHEME) ? url.substring(SCHEME.length()) : url;
    }

    private OpenIDConnectProvider findProvider(String arn) {
        try {
            return iamService.getOpenIDConnectProvider(arn);
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("IAM OIDC provider {0} from the previous provision is gone, creating it again", arn);
            return null;
        }
    }

    private static void record(StackResource r, OpenIDConnectProvider provider) {
        r.setPhysicalId(provider.getArn());
        r.getAttributes().put("Arn", provider.getArn());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        CfnDeletes.safeDelete("IAM OIDC provider", physicalId,
                () -> iamService.deleteOpenIDConnectProvider(physicalId), NOT_FOUND);
    }
}
