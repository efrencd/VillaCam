package es.colegio.camara;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import java.util.Locale;

/** Keeps unsupported primary languages from falling through to a secondary Spanish locale. */
abstract class LocalizedActivity extends Activity {
    @Override protected void attachBaseContext(Context base) {
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        // The Activity configuration may already promote a supported secondary locale.
        // Read the original system order, or the explicit Android 13+ app override.
        Configuration system = Resources.getSystem().getConfiguration();
        Locale primary = Build.VERSION.SDK_INT >= 24 ? system.getLocales().get(0) : system.locale;
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = base.getSystemService(LocaleManager.class);
            LocaleList appLocales = manager.getApplicationLocales();
            if (!appLocales.isEmpty()) primary = appLocales.get(0);
        }
        Locale uiLocale = new Locale(UiLanguage.select(primary.getLanguage()));
        configuration.setLocale(uiLocale);
        if (Build.VERSION.SDK_INT >= 24) configuration.setLocales(new LocaleList(uiLocale));
        super.attachBaseContext(base.createConfigurationContext(configuration));
    }
}
