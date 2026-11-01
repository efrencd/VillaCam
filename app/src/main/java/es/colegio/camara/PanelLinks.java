package es.colegio.camara;

import java.net.URI;

/** Web panel navigation stays on the explicitly configured HTTP(S) origin. */
final class PanelLinks {
    static String normalize(String value) {
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            return uri.toASCIIString();
        } catch (Exception error) { throw new IllegalArgumentException("Invalid panel URL"); }
    }
    static boolean sameOrigin(String configured, String target) {
        try {
            URI a = new URI(normalize(configured)), b = new URI(normalize(target));
            return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
        } catch (Exception error) { return false; }
    }
    private static int port(URI uri) { return uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort(); }
}
