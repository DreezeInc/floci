package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.eks.EksPodNetworkRouting.Route;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EksPodNetworkRoutingTest {

    private static final String PREAMBLE = String.join("\n",
            "set -eu",
            "IPTABLES=$(command -v iptables 2>/dev/null || command -v /bin/aux/iptables 2>/dev/null || true)",
            "if [ -z \"$IPTABLES\" ]; then",
            "  echo 'iptables not found in container' >&2",
            "  exit 1",
            "fi",
            "$IPTABLES -t nat -N FLOCI-LINK-LOCAL 2>/dev/null || $IPTABLES -t nat -F FLOCI-LINK-LOCAL",
            "$IPTABLES -t nat -C PREROUTING -j FLOCI-LINK-LOCAL 2>/dev/null"
                    + " || $IPTABLES -t nat -I PREROUTING 1 -j FLOCI-LINK-LOCAL",
            "$IPTABLES -t nat -N FLOCI-LINK-LOCAL-OUTPUT 2>/dev/null || $IPTABLES -t nat -F FLOCI-LINK-LOCAL-OUTPUT",
            "$IPTABLES -t nat -C OUTPUT -j FLOCI-LINK-LOCAL-OUTPUT 2>/dev/null"
                    + " || $IPTABLES -t nat -I OUTPUT 1 -j FLOCI-LINK-LOCAL-OUTPUT",
            "$IPTABLES -t nat -N FLOCI-LINK-LOCAL-SNAT 2>/dev/null || $IPTABLES -t nat -F FLOCI-LINK-LOCAL-SNAT",
            "$IPTABLES -t nat -C POSTROUTING -j FLOCI-LINK-LOCAL-SNAT 2>/dev/null"
                    + " || $IPTABLES -t nat -I POSTROUTING 1 -j FLOCI-LINK-LOCAL-SNAT");

    @Test
    void linkLocalEndpointValidatesInput() {
        assertThrows(NullPointerException.class, () -> new LinkLocalEndpoint(null, 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("   ", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("invalid-ip", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169.256", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("-1.0.0.0", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169.254.1", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.01.1", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("; rm -rf / ;", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169.254", 0));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169.254", -1));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169.254", 65536));

        LinkLocalEndpoint ep = new LinkLocalEndpoint("169.254.169.254", 80);
        assertEquals("169.254.169.254", ep.ip());
        assertEquals(80, ep.port());
    }

    @Test
    void routeOnlyTargetsAnIpv4AddressAndAValidPort() {
        LinkLocalEndpoint imds = EksPodNetworkRouting.IMDS_ENDPOINT;
        assertThrows(NullPointerException.class, () -> new Route(null, "172.17.0.5", 9169, true));
        assertThrows(NullPointerException.class, () -> new Route(imds, null, 9169, true));
        assertThrows(IllegalArgumentException.class, () -> new Route(imds, "host.docker.internal", 9169, true));
        assertThrows(IllegalArgumentException.class, () -> new Route(imds, "172.17.0.5; reboot", 9169, true));
        assertThrows(IllegalArgumentException.class, () -> new Route(imds, "172.17.0.5", 0, true));
        assertThrows(IllegalArgumentException.class, () -> new Route(imds, "172.17.0.5", 65536, true));
    }

    @Test
    void buildRoutingCommandRejectsNullOrEmptyRoutes() {
        assertThrows(IllegalArgumentException.class, () ->
                EksPodNetworkRouting.buildRoutingCommand(null, null));
        assertThrows(IllegalArgumentException.class, () ->
                EksPodNetworkRouting.buildRoutingCommand("10.42.0.0/16", List.of()));
    }

    @Test
    void podIdentityEndpointIsDnatedToFlociFromPodsAndFromTheNode() {
        String[] cmd = EksPodNetworkRouting.buildRoutingCommand("10.42.0.0/16", List.of(
                new Route(EksPodNetworkRouting.POD_IDENTITY_ENDPOINT, "172.17.0.5", 4566, true)));

        assertArrayEquals(new String[]{"sh", "-c", PREAMBLE + "\n" + String.join("\n",
                "$IPTABLES -t nat -A FLOCI-LINK-LOCAL -s 10.42.0.0/16 -d 169.254.170.23 -p tcp --dport 80"
                        + " -j DNAT --to-destination 172.17.0.5:4566",
                "$IPTABLES -t nat -A FLOCI-LINK-LOCAL-OUTPUT -d 169.254.170.23 -p tcp --dport 80"
                        + " -j DNAT --to-destination 172.17.0.5:4566",
                "$IPTABLES -t nat -A FLOCI-LINK-LOCAL-SNAT -p tcp -m conntrack --ctstate DNAT"
                        + " --ctorigdst 169.254.170.23 --ctorigdstport 80 -j MASQUERADE")}, cmd);
    }

    @Test
    void imdsWithoutPodNetworkIsOnlyRoutedFromTheNode() {
        String[] cmd = EksPodNetworkRouting.buildRoutingCommand("10.42.0.0/16", List.of(
                new Route(EksPodNetworkRouting.IMDS_ENDPOINT, "172.17.0.5", 9169, false),
                new Route(EksPodNetworkRouting.POD_IDENTITY_ENDPOINT, "172.17.0.5", 4566, true)));

        assertArrayEquals(new String[]{"sh", "-c", PREAMBLE + "\n" + String.join("\n",
                "$IPTABLES -t nat -A FLOCI-LINK-LOCAL-OUTPUT -d 169.254.169.254 -p tcp --dport 80"
                        + " -j DNAT --to-destination 172.17.0.5:9169",
                "$IPTABLES -t nat -A FLOCI-LINK-LOCAL-SNAT -p tcp -m conntrack --ctstate DNAT"
                        + " --ctorigdst 169.254.169.254 --ctorigdstport 80 -j MASQUERADE",
                "$IPTABLES -t nat -A FLOCI-LINK-LOCAL -s 10.42.0.0/16 -d 169.254.170.23 -p tcp --dport 80"
                        + " -j DNAT --to-destination 172.17.0.5:4566",
                "$IPTABLES -t nat -A FLOCI-LINK-LOCAL-OUTPUT -d 169.254.170.23 -p tcp --dport 80"
                        + " -j DNAT --to-destination 172.17.0.5:4566",
                "$IPTABLES -t nat -A FLOCI-LINK-LOCAL-SNAT -p tcp -m conntrack --ctstate DNAT"
                        + " --ctorigdst 169.254.170.23 --ctorigdstport 80 -j MASQUERADE")}, cmd);
    }

    @Test
    void buildRoutingCommandUsesDefaultPodCidrWhenNullOrBlankAndHonoursACustomOne() {
        Route imds = new Route(EksPodNetworkRouting.IMDS_ENDPOINT, "10.0.0.9", 9169, true);
        String expectedDefault = "$IPTABLES -t nat -A FLOCI-LINK-LOCAL -s 10.42.0.0/16 -d 169.254.169.254";
        assertEquals(1, EksPodNetworkRouting.buildRoutingCommand(null, List.of(imds))[2]
                .lines().filter(line -> line.startsWith(expectedDefault)).count());
        assertEquals(1, EksPodNetworkRouting.buildRoutingCommand("   ", List.of(imds))[2]
                .lines().filter(line -> line.startsWith(expectedDefault)).count());
        assertEquals(1, EksPodNetworkRouting.buildRoutingCommand("192.168.0.0/16", List.of(imds))[2]
                .lines().filter(line -> line.startsWith(
                        "$IPTABLES -t nat -A FLOCI-LINK-LOCAL -s 192.168.0.0/16 -d 169.254.169.254")).count());
    }

    @Test
    void resolveCommandPassesTheHostAsAnArgumentNotAsShellText() {
        assertArrayEquals(new String[]{"sh", "-c", EksPodNetworkRouting.RESOLVE_SCRIPT, "sh", "host.docker.internal"},
                EksPodNetworkRouting.buildResolveCommand("host.docker.internal"));
    }

    @Test
    void parseResolvedAddressTakesTheFirstIpv4Line() {
        assertEquals(Optional.of("192.168.65.254"),
                EksPodNetworkRouting.parseResolvedAddress("192.168.65.254\n10.0.0.1\n"));
        assertEquals(Optional.of("172.17.0.1"),
                EksPodNetworkRouting.parseResolvedAddress("nslookup: warning\n 172.17.0.1 \n"));
        assertEquals(Optional.empty(), EksPodNetworkRouting.parseResolvedAddress(""));
        assertEquals(Optional.empty(), EksPodNetworkRouting.parseResolvedAddress("192.168.65.7:53\nfd00::1\n"));
        assertEquals(Optional.empty(), EksPodNetworkRouting.parseResolvedAddress(null));
    }
}
