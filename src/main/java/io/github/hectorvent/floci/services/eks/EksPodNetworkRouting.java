package io.github.hectorvent.floci.services.eks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Generates the iptables rules that answer a cluster node's link-local endpoints (IMDS on
 * 169.254.169.254:80, EKS Pod Identity on 169.254.170.23:80) from Floci itself.
 * <p>
 * The rules live in the node container's nat table and need nothing but iptables, which every k3s
 * node image ships: no listener and no package runs on the node. Traffic to an endpoint is DNATed
 * to Floci's address and port, from the node's own network namespace (and so from {@code hostNetwork}
 * pods) in {@code OUTPUT}, and from the pod CIDR in {@code PREROUTING} when the route reaches pods.
 * {@code POSTROUTING} masquerades that traffic, so Floci sees it arrive from the node's address
 * whichever namespace sent it, and replies return through the same connection tracking entry.
 */
public final class EksPodNetworkRouting {

    public static final String DEFAULT_POD_CIDR = "10.42.0.0/16";
    public static final String CHAIN_NAME = "FLOCI-LINK-LOCAL";
    public static final String OUTPUT_CHAIN_NAME = "FLOCI-LINK-LOCAL-OUTPUT";
    public static final String SNAT_CHAIN_NAME = "FLOCI-LINK-LOCAL-SNAT";
    public static final LinkLocalEndpoint IMDS_ENDPOINT = new LinkLocalEndpoint("169.254.169.254", 80);
    public static final LinkLocalEndpoint POD_IDENTITY_ENDPOINT = new LinkLocalEndpoint("169.254.170.23", 80);

    /**
     * Prints the IPv4 address the node container resolves its first argument to: its
     * {@code /etc/hosts} entry first, as {@code host.docker.internal} is set on Linux, then DNS.
     */
    static final String RESOLVE_SCRIPT = """
            set -u
            IPV4='^[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+$'
            HOSTS=$(awk -v host="$1" -v ipv4="$IPV4" '$1 ~ ipv4 { for (i = 2; i <= NF; i++) if ($i == host) { print $1; exit } }' /etc/hosts 2>/dev/null)
            if [ -n "$HOSTS" ]; then
              echo "$HOSTS"
              exit 0
            fi
            nslookup "$1" 2>/dev/null | awk -v ipv4="$IPV4" '/^Name:/ { named = 1 } named && /^Address/ { for (i = 2; i <= NF; i++) if ($i ~ ipv4) { print $i; exit } }'
            exit 0
            """;

    /**
     * One link-local endpoint answered by Floci at {@code targetIp:targetPort}. The node's own
     * namespace always reaches it; {@code fromPods} also routes the pod CIDR to it.
     */
    public record Route(LinkLocalEndpoint endpoint, String targetIp, int targetPort, boolean fromPods) {

        public Route {
            Objects.requireNonNull(endpoint, "endpoint cannot be null");
            Objects.requireNonNull(targetIp, "targetIp cannot be null");
            if (!LinkLocalEndpoint.isValidIpv4Literal(targetIp)) {
                throw new IllegalArgumentException("targetIp must be a valid IPv4 address literal: " + targetIp);
            }
            if (targetPort <= 0 || targetPort > 65535) {
                throw new IllegalArgumentException("targetPort must be between 1 and 65535");
            }
        }
    }

    private EksPodNetworkRouting() {}

    /**
     * Builds a POSIX sh command array that rebuilds Floci's link-local chains in the node container
     * nat table so they hold exactly the given routes. Rebuilding rather than appending lets a
     * restarted Floci at a new address re-point a node it adopts.
     *
     * @param podCidr pod network CIDR (defaults to {@link #DEFAULT_POD_CIDR} if null or blank)
     * @param routes link-local endpoints and the Floci address answering each
     * @return command array suitable for container exec (["sh", "-c", "..."])
     */
    public static String[] buildRoutingCommand(String podCidr, List<Route> routes) {
        String cidr = (podCidr != null && !podCidr.isBlank()) ? podCidr : DEFAULT_POD_CIDR;
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("routes cannot be null or empty");
        }

        List<String> lines = new ArrayList<>();
        lines.add("set -eu");
        lines.add("IPTABLES=$(command -v iptables 2>/dev/null || command -v /bin/aux/iptables 2>/dev/null || true)");
        lines.add("if [ -z \"$IPTABLES\" ]; then");
        lines.add("  echo 'iptables not found in container' >&2");
        lines.add("  exit 1");
        lines.add("fi");
        addChain(lines, CHAIN_NAME, "PREROUTING");
        addChain(lines, OUTPUT_CHAIN_NAME, "OUTPUT");
        addChain(lines, SNAT_CHAIN_NAME, "POSTROUTING");

        for (Route route : routes) {
            LinkLocalEndpoint ep = route.endpoint();
            String match = String.format("-d %s -p tcp --dport %d", ep.ip(), ep.port());
            String dnat = String.format("-j DNAT --to-destination %s:%d", route.targetIp(), route.targetPort());
            if (route.fromPods()) {
                lines.add(String.format("$IPTABLES -t nat -A %s -s %s %s %s", CHAIN_NAME, cidr, match, dnat));
            }
            lines.add(String.format("$IPTABLES -t nat -A %s %s %s", OUTPUT_CHAIN_NAME, match, dnat));
            lines.add(String.format(
                    "$IPTABLES -t nat -A %s -p tcp -m conntrack --ctstate DNAT --ctorigdst %s --ctorigdstport %d -j MASQUERADE",
                    SNAT_CHAIN_NAME, ep.ip(), ep.port()));
        }

        return new String[]{"sh", "-c", String.join("\n", lines)};
    }

    /** A command printing the IPv4 address the node container resolves {@code host} to, if any. */
    public static String[] buildResolveCommand(String host) {
        return new String[]{"sh", "-c", RESOLVE_SCRIPT, "sh", host};
    }

    /** The first line of {@link #buildResolveCommand} output that is an IPv4 address literal. */
    public static Optional<String> parseResolvedAddress(String output) {
        if (output == null) {
            return Optional.empty();
        }
        return output.lines()
                .map(String::trim)
                .filter(LinkLocalEndpoint::isValidIpv4Literal)
                .findFirst();
    }

    private static void addChain(List<String> lines, String chain, String hook) {
        lines.add("$IPTABLES -t nat -N " + chain + " 2>/dev/null || $IPTABLES -t nat -F " + chain);
        lines.add("$IPTABLES -t nat -C " + hook + " -j " + chain + " 2>/dev/null || $IPTABLES -t nat -I " + hook + " 1 -j " + chain);
    }
}
