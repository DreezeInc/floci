package io.github.hectorvent.floci.services.eks;

import java.util.List;

/** The pod networks of the EKS clusters that currently have a running container. */
public interface EksPodNetworks {

    List<EksPodNetwork> podNetworks();
}
