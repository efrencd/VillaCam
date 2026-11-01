package es.colegio.camara;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.UUID;

/** Hosts the official client. WebRTC state and capture controls belong to VDO.Ninja. */
public class VdoActivity extends LocalizedActivity {
    private FrameLayout webArea;
    private SidebarPanel sidebarPanel;
    private WebView web;
    private EditText identifier, password;
    private TextView status, receiverLink;
    private Button open;
    private boolean resumed, openAfterPermission;
    private int pageGeneration;
    private boolean pageFailed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.rgb(17, 24, 39));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        FrameLayout stage = new FrameLayout(this);
        webArea = new FrameLayout(this);
        stage.addView(webArea, new FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new LinearLayout.LayoutParams(0, -1, 1));
        placeholder();
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(12), 0, dp(12), 0);
        root.addView(sidebar, new LinearLayout.LayoutParams(dp(290), -1));
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(panel);
        sidebar.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        button(panel, getString(R.string.back_to_srt), view -> finish());
        open = button(panel, getString(R.string.open_publisher), view -> { if (web == null) openPublisher(); else closePublisher(); });
        open.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(21, 128, 61)));
        open.setTextColor(Color.WHITE);

        label(panel, getString(R.string.vdo_mode));
        label(panel, getString(R.string.private_camera_id));
        identifier = new EditText(this);
        identifier.setSingleLine(true);
        identifier.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        String saved = getPreferences(0).getString("streamId", "");
        if (!saved.matches("[A-Za-z0-9_-]{8,64}")) saved = "cam_" + UUID.randomUUID().toString().replace("-", "");
        identifier.setText(saved);
        identifier.setContentDescription(getString(R.string.private_camera_id));
        panel.addView(identifier);
        label(panel, getString(R.string.optional_password));
        password = new EditText(this);
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setContentDescription(getString(R.string.vdo_password));
        panel.addView(password);
        button(panel, getString(R.string.copy_obs_link), view -> copyReceiver());
        receiverLink = label(panel, "");
        receiverLink.setTextIsSelectable(true);
        status = new TextView(this);
        status.setText(R.string.configure_publisher);
        status.setTextSize(12);
        status.setMaxWidth(dp(360));
        status.setMaxLines(3);
        status.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.graphics.drawable.GradientDrawable statusBackground = new android.graphics.drawable.GradientDrawable();
        statusBackground.setColor(0x99111827); statusBackground.setCornerRadius(dp(8));
        status.setBackground(statusBackground);
        FrameLayout.LayoutParams statusPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        statusPosition.setMargins(dp(12), dp(12), dp(12), dp(12));
        stage.addView(status, statusPosition);
        label(panel, getString(R.string.vdo_help));
        TextView terms = label(panel, getString(R.string.vdo_terms));
        android.text.util.Linkify.addLinks(terms, android.text.util.Linkify.WEB_URLS);
        sidebarPanel = new SidebarPanel(this, sidebar, scroll, panel);
        setContentView(root);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(LinearLayout parent, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setPadding(0, dp(8), 0, dp(8));
        parent.addView(label);
        return label;
    }
    private Button button(LinearLayout parent, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }
    private boolean permissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }
    private String link(boolean publishing) {
        return VdoLinks.build(publishing, identifier.getText().toString().trim(), password.getText().toString());
    }
    private void copyReceiver() {
        try {
            String url = link(false);
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_obs_link), url));
            receiverLink.setText(url);
            getPreferences(0).edit().putString("streamId", identifier.getText().toString().trim()).apply();
            Toast.makeText(this, getString(R.string.obs_link_copied), Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException error) { invalidId(); }
    }
    private void invalidId() { status.setText(getString(R.string.invalid_camera_id)); }
    private void openPublisher() {
        String url;
        try { url = link(true); }
        catch (IllegalArgumentException error) { invalidId(); return; }
        if (!permissions()) {
            openAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 2);
            return;
        }
        getPreferences(0).edit().putString("streamId", identifier.getText().toString().trim()).apply();
        receiverLink.setText(link(false));
        final int page = ++pageGeneration;
        pageFailed = false;
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                if (page != pageGeneration || !resumed || !permissions()
                    || !VdoLinks.trusted(request.getOrigin().toString()) || web == null
                    || !VdoLinks.trusted(web.getUrl())) { request.deny(); return; }
                ArrayList<String> allowed = new ArrayList<>();
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                        || PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) allowed.add(resource);
                }
                if (allowed.isEmpty()) request.deny(); else request.grant(allowed.toArray(new String[0]));
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return blockNavigation(request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String target) { return blockNavigation(target); }
            private boolean blockNavigation(String target) {
                if (VdoLinks.trusted(target)) return false;
                status.setText(getString(R.string.external_link));
                return true;
            }
            @Override public void onPageFinished(WebView view, String target) {
                if (page == pageGeneration && !pageFailed && VdoLinks.trusted(target))
                    status.setText(getString(R.string.vdo_loaded));
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (page == pageGeneration && request.isForMainFrame()) {
                    pageFailed = true;
                    status.setText(getString(R.string.vdo_load_error));
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (page == pageGeneration && request.isForMainFrame()) {
                    pageFailed = true;
                    status.setText(getString(R.string.vdo_http_error, response.getStatusCode()));
                }
            }
        });
        webArea.removeAllViews();
        webArea.addView(web, new FrameLayout.LayoutParams(-1, -1));
        identifier.setEnabled(false); password.setEnabled(false);
        open.setText(getString(R.string.close_publisher));
        open.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(185, 28, 28)));
        status.setText(getString(R.string.vdo_loading));
        web.loadUrl(url);
    }
    private void placeholder() {
        webArea.removeAllViews();
        TextView hint = new TextView(this);
        hint.setText(getString(R.string.vdo_placeholder));
        hint.setGravity(Gravity.CENTER);
        hint.setTextSize(20);
        webArea.addView(hint, new FrameLayout.LayoutParams(-1, -1));
    }
    private void closePublisher() {
        ++pageGeneration;
        if (web != null) {
            WebView previous = web;
            web = null;
            previous.stopLoading();
            previous.onPause();
            webArea.removeView(previous);
            previous.destroy(); // Releases WebRTC capture, connections and pending permissions.
        }
        placeholder();
        identifier.setEnabled(true); password.setEnabled(true);
        open.setText(getString(R.string.open_publisher));
        open.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(21, 128, 61)));
        status.setText(getString(R.string.publisher_closed));
    }
    @Override protected void onResume() { super.onResume(); resumed = true; sidebarPanel.resume(); }
    @Override protected void onPause() { resumed = false; sidebarPanel.pause(); closePublisher(); super.onPause(); }
    @Override protected void onDestroy() { closePublisher(); super.onDestroy(); }
    @Override public void onRequestPermissionsResult(int code, String[] names, int[] results) {
        super.onRequestPermissionsResult(code, names, results);
        if (code == 2 && openAfterPermission) {
            openAfterPermission = false;
            // Defer until onResume: permission dialogs can temporarily pause the Activity.
            open.post(() -> {
                if (!isFinishing() && !isDestroyed() && resumed && permissions()) openPublisher();
                else status.setText(getString(R.string.vdo_permissions_required));
            });
        }
    }
}
