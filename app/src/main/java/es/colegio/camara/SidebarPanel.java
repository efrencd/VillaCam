package es.colegio.camara;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

/** Tabs keep a single receive-only WebView alive while the foreground camera is running. */
final class SidebarPanel {
    private final Activity activity;
    private final SharedPreferences preferences;
    private final FrameLayout pages, browser;
    private final View options, about;
    private final LinearLayout webPage;
    private final ImageButton[] tabs = new ImageButton[3];
    private final TextView notice;
    private final EditText address;
    private WebView web;
    private String url;
    private boolean audioEnabled, resumed, closed;
    private int selected;

    SidebarPanel(Activity activity, LinearLayout sidebar, ScrollView options, LinearLayout settings) {
        this.activity = activity;
        this.options = options;
        preferences = activity.getSharedPreferences("directorPanel", Context.MODE_PRIVATE);
        url = preferences.getString("url", "");
        audioEnabled = preferences.getBoolean("audioEnabled", false);
        sidebar.removeView(options);
        LinearLayout navigation = new LinearLayout(activity);
        int[] icons = {R.drawable.ic_settings, R.drawable.ic_web, R.drawable.ic_info};
        int[] descriptions = {R.string.tab_options, R.string.tab_web, R.string.tab_about};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            ImageButton tab = new ImageButton(activity);
            tabs[i] = tab;
            tab.setImageResource(icons[i]);
            tab.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            tab.setContentDescription(activity.getString(descriptions[i]));
            tab.setOnClickListener(view -> select(index));
            tab.setOnLongClickListener(view -> { Toast.makeText(activity, tab.getContentDescription(), Toast.LENGTH_SHORT).show(); return true; });
            LinearLayout.LayoutParams tabSize = new LinearLayout.LayoutParams(0, dp(40), 1);
            if (i > 0) tabSize.leftMargin = dp(6);
            navigation.addView(tab, tabSize);
        }
        sidebar.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        pages = new FrameLayout(activity);
        sidebar.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        pages.addView(options, new FrameLayout.LayoutParams(-1, -1));
        webPage = new LinearLayout(activity);
        webPage.setOrientation(LinearLayout.VERTICAL);
        notice = label(webPage, R.string.web_empty);
        notice.setTextSize(12);
        browser = new FrameLayout(activity);
        webPage.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        pages.addView(webPage, new FrameLayout.LayoutParams(-1, -1));
        ScrollView aboutScroll = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        aboutScroll.addView(content);
        label(content, R.string.app_name).setTextSize(24);
        try { TextView version = new TextView(activity); version.setText(activity.getString(R.string.about_version,
            activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName)); content.addView(version); }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        label(content, R.string.about_body);
        TextView links = label(content, R.string.about_links);
        android.text.util.Linkify.addLinks(links, android.text.util.Linkify.WEB_URLS);
        about = aboutScroll;
        pages.addView(about, new FrameLayout.LayoutParams(-1, -1));
        label(settings, R.string.web_address);
        address = new EditText(activity);
        address.setSingleLine(true);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("https://…"); address.setText(url);
        address.setContentDescription(activity.getString(R.string.web_address));
        settings.addView(address);
        Switch sound = new Switch(activity);
        sound.setText(R.string.web_audio_enabled);
        sound.setChecked(audioEnabled);
        boolean canMute = WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO);
        sound.setEnabled(canMute);
        if (!canMute) sound.setChecked(true);
        settings.addView(sound);
        if (!canMute) label(settings, R.string.web_mute_unavailable).setTextSize(12);
        sound.setOnCheckedChangeListener((button, checked) -> {
            audioEnabled = checked;
            preferences.edit().putBoolean("audioEnabled", checked).apply();
            if (web != null && WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO))
                WebViewCompat.setAudioMuted(web, !audioEnabled);
        });
        label(settings, R.string.web_audio_help).setTextSize(12);
        Button save = new Button(activity);
        save.setText(R.string.web_save);
        settings.addView(save);
        save.setOnClickListener(view -> {
            String next = address.getText().toString().trim();
            try { if (!next.isEmpty()) next = PanelLinks.normalize(next); }
            catch (IllegalArgumentException error) { address.setError(activity.getString(R.string.web_invalid)); return; }
            url = next;
            closed = false;
            preferences.edit().putString("url", url).apply();
            destroyWeb();
            activity.getWindow().getDecorView().clearFocus();
            ((android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(address.getWindowToken(), 0);
            select(1);
        });
        LinearLayout actions = new LinearLayout(activity);
        action(actions, R.drawable.ic_refresh, R.string.web_reload, () -> { load(true); select(1); });
        action(actions, R.drawable.ic_close, R.string.web_close, this::closePanel);
        settings.addView(actions);
        select(0);
    }
    private void showNotice(int resource) {
        notice.setText(resource);
        notice.setVisibility(View.VISIBLE);
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private TextView label(LinearLayout parent, int resource) {
        TextView text = new TextView(activity); text.setText(resource);
        text.setPadding(0, dp(8), 0, dp(8)); parent.addView(text); return text;
    }
    private void action(LinearLayout parent, int icon, int description, Runnable action) {
        ImageButton button = new ImageButton(activity);
        button.setImageResource(icon); button.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        button.setContentDescription(activity.getString(description));
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
    }
    private void closePanel() { closed = true; destroyWeb(); showNotice(R.string.web_closed); }
    private void select(int index) {
        selected = index;
        options.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        webPage.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        about.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setSelected(i == index);
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setColor(i == index ? 0xFF166534 : 0xFF263244);
            background.setCornerRadius(dp(4));
            tabs[i].setBackground(background);
        }
        if (index == 1 && web == null && !closed) load(false);
    }
    private void load(boolean reload) {
        if (reload) closed = false;
        if (!resumed || closed) return;
        if (url.isEmpty()) { showNotice(R.string.web_empty); return; }
        if (!reload && web != null) return;
        destroyWeb();
        WebView next = new WebView(activity);
        web = next;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) WebViewCompat.setAudioMuted(next, !audioEnabled);
        WebSettings settings = next.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setGeolocationEnabled(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        next.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }
        });
        next.setWebViewClient(new WebViewClient() {
            private boolean blocked(String target) {
                if (PanelLinks.sameOrigin(url, target)) return false;
                showNotice(R.string.web_navigation_blocked); return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return blocked(request.getUrl().toString()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String target) { return blocked(target); }
            @Override public void onPageFinished(WebView view, String target) {
                if (web == view && !failed) notice.setVisibility(View.GONE);
            }
            private boolean failed;
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (web == view && request.isForMainFrame()) { failed = true; showNotice(R.string.web_load_failed); }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (web == view && request.isForMainFrame()) { failed = true; showNotice(R.string.web_load_failed); }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                handler.cancel();
                if (web == view) { failed = true; showNotice(R.string.web_load_failed); }
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (web == view) { destroyWeb(); showNotice(R.string.web_load_failed); }
                return true;
            }
        });
        browser.addView(next, new FrameLayout.LayoutParams(-1, -1));
        showNotice(R.string.web_loading);
        next.loadUrl(url);
    }
    void resume() {
        resumed = true;
        if (selected == 1) load(false);
    }
    void pause() { resumed = false; destroyWeb(); }
    void destroyWeb() {
        if (web == null) return;
        WebView previous = web; web = null;
        browser.removeView(previous);
        previous.stopLoading(); previous.onPause(); previous.destroy();
    }
}
