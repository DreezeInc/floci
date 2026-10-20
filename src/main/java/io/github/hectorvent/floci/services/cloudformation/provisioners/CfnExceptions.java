package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.github.hectorvent.floci.core.common.AwsException;

/**
 * Exceptions shared by extracted CloudFormation resource provisioners.
 */
public final class CfnExceptions {

    private CfnExceptions() {
    }

    /**
     * Thrown when an update requires replacing a custom-named resource that kept its name.
     */
    public static AwsException customNamedReplacement(String name) {
        return new AwsException("ValidationError",
                "CloudFormation cannot update a stack when a custom-named resource requires replacing. "
                        + "Rename " + name + " and update the stack again.", 400);
    }

    /**
     * Thrown when an update changes a createOnly property of a type whose provisioner reuses the
     * prior entity and has no replacement path.
     */
    public static AwsException replacementNotSupported(String property) {
        return new AwsException("ValidationError",
                "Updating " + property + " requires resource replacement, which is not supported.", 400);
    }
}
