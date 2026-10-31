package es.colegio.camara;

public class VdoLinksTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(VdoLinks.build(true, "cam_12345678", "").equals("https://vdo.ninja/?push=cam_12345678"));
        check(VdoLinks.build(false, "cam_12345678", "a&b #á").equals("https://vdo.ninja/?view=cam_12345678&password=a%26b+%23%C3%A1"));
        for (String id : new String[]{"short", "camera&view=other", "camera/../../", "camara con espacios", "x".repeat(65)}) {
            boolean rejected = false;
            try { VdoLinks.build(true, id, ""); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected);
        }
        check(VdoLinks.trusted("https://vdo.ninja/?push=cam_12345678"));
        check(VdoLinks.trusted("https://vdo.ninja:443/"));
        check(!VdoLinks.trusted(null));
        for (String url : new String[]{"http://vdo.ninja/", "https://vdo.ninja.evil.com/", "https://evil.com/vdo.ninja", "https://vdo.ninja@evil.com/", "https://user@vdo.ninja/", "file:///vdo.ninja", "https://vdo.ninja:8443/", "javascript:alert(1)", "https://%"})
            check(!VdoLinks.trusted(url));
        System.out.println("VdoLinks: all scenarios passed");
    }
}
