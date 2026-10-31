package es.colegio.camara;

/** Only the primary device language selects Spanish; every other language uses English. */
final class UiLanguage {
    static String select(String primaryLanguage) {
        return "es".equalsIgnoreCase(primaryLanguage) ? "es" : "en";
    }
}
