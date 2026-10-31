package es.colegio.camara;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class VdoLinks {
    static String build(boolean publish, String id, String password) {
        if (!id.matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalArgumentException("Identificador no válido");
        String url = "https://vdo.ninja/?" + (publish ? "push=" : "view=") + id;
        if (!password.isEmpty()) {
            try { url += "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8.name()); }
            catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
        }
        return url;
    }
    static boolean trusted(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && "vdo.ninja".equalsIgnoreCase(uri.getHost())
                && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException error) { return false; }
    }
}
