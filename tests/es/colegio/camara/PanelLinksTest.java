package es.colegio.camara;

public class PanelLinksTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        check(PanelLinks.normalize(" https://example.org/chat?q=niño ").equals("https://example.org/chat?q=ni%C3%B1o"));
        check(PanelLinks.sameOrigin("http://192.168.1.2:8080/chat", "http://192.168.1.2:8080/audio"));
        check(PanelLinks.sameOrigin("https://example.org", "https://EXAMPLE.org:443/page"));
        check(!PanelLinks.sameOrigin("https://example.org", "https://example.org.evil.test/"));
        check(!PanelLinks.sameOrigin("https://example.org", "http://example.org"));
        check(!PanelLinks.sameOrigin("https://example.org", "https://example.org:8443"));
        check(!PanelLinks.sameOrigin("https://example.org", "https://user@example.org/"));
        for (String value : new String[]{"file:///tmp/test", "javascript:alert(1)", "intent://foo", "example.org", "https://user:pass@example.org", "https://example.org:0", "https://example.org:65536", "https://%", ""}) {
            boolean rejected = false;
            try { PanelLinks.normalize(value); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected);
        }
        System.out.println("PanelLinks: all scenarios passed");
    }
}
