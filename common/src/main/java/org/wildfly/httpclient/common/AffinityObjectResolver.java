package org.wildfly.httpclient.common;

import io.undertow.server.ServerConnection;
import org.jboss.ejb.client.Affinity;
import org.jboss.ejb.client.NodeAffinity;
import org.jboss.marshalling.ObjectResolver;

final class AffinityObjectResolver implements ObjectResolver {
    private final NodeAffinity peerNodeAffinity;
    private final NodeAffinity selfNodeAffinity;

    AffinityObjectResolver(final ServerConnection connection) {
        final String remoteEndpointName = connection.getPeerAddress().toString();
        peerNodeAffinity = remoteEndpointName == null ? null : new NodeAffinity(remoteEndpointName);
        final String localEndpointName = connection.getLocalAddress().toString();
        selfNodeAffinity = localEndpointName == null ? null : new NodeAffinity(localEndpointName);
    }

    public Object readResolve(final Object replacement) {
        if (replacement == Affinity.LOCAL) {
            // This shouldn't be possible.  If it happens though, we will guess that it is the peer talking about itself
            return peerNodeAffinity != null ? peerNodeAffinity : Affinity.NONE;
        } else if (replacement instanceof NodeAffinity) {
            if (selfNodeAffinity != null && replacement.equals(selfNodeAffinity)) {
                return Affinity.LOCAL;
            }
        }
        return replacement;
    }

    public Object writeReplace(final Object original) {
        if (original == Affinity.LOCAL && selfNodeAffinity != null) {
            // we don't know the peer's view URI of us, if there even is one, so switch it to node affinity and let the peer sort it out
            return selfNodeAffinity;
        }
        return original;
    }
}