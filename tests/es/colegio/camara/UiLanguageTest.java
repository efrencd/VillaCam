package es.colegio.camara;

public class UiLanguageTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        check(UiLanguage.select("es").equals("es"));
        check(UiLanguage.select("ES").equals("es"));
        for (String language : new String[]{"en", "fr", "de", "pt", "ca", "ar", "zh", "", "und"})
            check(UiLanguage.select(language).equals("en"));
        check(UiLanguage.select(null).equals("en"));
        System.out.println("UiLanguage: all scenarios passed");
    }
}
