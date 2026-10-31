package es.colegio.camara;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.TrafficStats;
import android.view.SurfaceHolder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.*;
import com.pedro.common.ConnectChecker;
import com.pedro.encoder.input.video.CameraHelper;
import com.pedro.encoder.utils.gl.AspectRatioMode;
import com.pedro.library.srt.SrtCamera2;
import com.pedro.library.view.OpenGlView;
import java.net.URI;

/** Foreground-only camera: leaving the screen always releases camera and microphone. */
public class MainActivity extends LocalizedActivity implements SurfaceHolder.Callback {
    private SrtCamera2 camera;
    private EditText host, port;
    private TextView status;
    private View liveDot;
    private Button transmit;
    private ImageButton mute;
    private Spinner resolution, latency;
    private TextView receiverHelp;
    private AudioInputRouter audioRouter;
    private Spinner audioInput;
    private TextView audioInputStatus;
    private static final int[] LATENCIES = {60, 120, 200, 400, 800};
    private CameraHelper.Facing facing = CameraHelper.Facing.BACK;
    private boolean surfaceReady, resumed, active, muted;
    private int generation;
    private final Handler recoveryHandler = new Handler(Looper.getMainLooper());
    private final RecoveryMonitor recovery = new RecoveryMonitor();
    private String streamUrl;
    private int reconnectCount;
    private AdaptiveBitrate adaptiveBitrate;
    private boolean dynamicBitrateSupported = true;
    private long measuredBitrate;
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!active || !resumed || !surfaceReady) return;
            if (recovery.timedOut(SystemClock.elapsedRealtime(), receivedPackets())) {
                reconnect(getString(R.string.no_receiver_response));
                return;
            }
            if (recovery.isLive()) adaptBitrate();
            recoveryHandler.postDelayed(this, 1000);
        }
    };
    private final Runnable retry = () -> {
        if (!active || !resumed || !surfaceReady) return;
        recovery.beginAttempt(SystemClock.elapsedRealtime(), receivedPackets());
        startAttempt();
    };
    private long receivedPackets() { return TrafficStats.getUidRxPackets(android.os.Process.myUid()); }

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
        OpenGlView preview = new OpenGlView(this);
        preview.setAspectRatioMode(AspectRatioMode.Adjust);
        FrameLayout videoArea = new FrameLayout(this);
        videoArea.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        root.addView(videoArea, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout videoControls = new LinearLayout(this);
        videoControls.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams overlay = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        overlay.setMargins(dp(12), dp(12), dp(12), dp(12));
        // Native sibling views overlay only the local preview, never the encoded video.
        videoArea.addView(videoControls, overlay);
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(sidebar, new LinearLayout.LayoutParams(dp(290), -1));
        transmit = button(sidebar, getString(R.string.start_stream), v -> toggle());
        transmit.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(4), dp(8), dp(4), dp(8));
        liveDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Color.rgb(74, 222, 128));
        liveDot.setBackground(dot);
        liveDot.setVisibility(View.INVISIBLE);
        liveDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dotSize = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotSize.rightMargin = dp(8);
        statusRow.addView(liveDot, dotSize);
        status = new TextView(this);
        status.setText(getString(R.string.grant_capture));
        statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        sidebar.addView(statusRow, new LinearLayout.LayoutParams(-1, -2));
        Spinner mode = selector(sidebar, getString(R.string.streaming_mode), new String[]{getString(R.string.srt_default), getString(R.string.vdo_mode)}, 0);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 1) {
                    mode.setSelection(0); // SRT remains the startup/return mode; never persist WebRTC mode.
                    release();
                    startActivity(new Intent(MainActivity.this, VdoActivity.class));
                }
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(panel);
        sidebar.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        host = field(panel, getString(R.string.receiver_host), "host", "192.168.1.100");
        port = field(panel, getString(R.string.udp_port), "port", "9000");
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        resolution = selector(panel, getString(R.string.resolution), new String[]{getString(R.string.resolution_720), getString(R.string.resolution_1080)},
            getPreferences(0).getInt("resolution", 0));
        latency = selector(panel, getString(R.string.srt_latency), new String[]{getString(R.string.latency_60), getString(R.string.latency_120), getString(R.string.latency_200), getString(R.string.latency_400), getString(R.string.latency_800)},
            getPreferences(0).getInt("latency", 1));
        audioInput = selector(panel, getString(R.string.audio_input_label),
            new String[]{getString(R.string.audio_auto_android), getString(R.string.audio_internal_mic), getString(R.string.audio_usb_mic)},
            getPreferences(0).getInt("audioInput", 0));
        audioInputStatus = new TextView(this);
        audioInputStatus.setPadding(0, dp(4), 0, dp(12));
        panel.addView(audioInputStatus);
        audioRouter = new AudioInputRouter(this, text -> audioInputStatus.setText(text));
        audioRouter.select(audioInput.getSelectedItemPosition());
        audioInput.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                getPreferences(0).edit().putInt("audioInput", position).apply();
                audioRouter.select(position);
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        iconButton(videoControls, R.drawable.ic_switch_camera, getString(R.string.switch_camera), v -> {
            if (camera != null && camera.isOnPreview()) {
                try { camera.switchCamera(); facing = camera.getCameraFacing(); }
                catch (Exception e) { message(getString(R.string.switch_camera_error)); }
            }
        });
        mute = iconButton(videoControls, R.drawable.ic_microphone, getString(R.string.mute_microphone), v -> {
            muted = !muted;
            if (camera != null) { if (muted) camera.disableAudio(); else camera.enableAudio(); }
            updateMicrophoneIcon();
        });
        controls();
        receiverHelp = new TextView(this);
        panel.addView(receiverHelp);
        updateReceiverHelp();
        setContentView(root);
        preview.getHolder().addCallback(this);
        // A fresh callback per session prevents old disconnect events changing a new session.
        previewView = preview;
        resolution.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                getPreferences(0).edit().putInt("resolution", position).apply();
                if (!active) { release(); preview(); }
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        latency.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                getPreferences(0).edit().putInt("latency", position).apply();
                updateReceiverHelp();
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        if (!permissions()) requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1);
    }

    private OpenGlView previewView;
    private int videoWidth() { return resolution.getSelectedItemPosition() == 1 ? 1920 : 1280; }
    private int videoHeight() { return resolution.getSelectedItemPosition() == 1 ? 1080 : 720; }
    private int maximumVideoBitrate() { return resolution.getSelectedItemPosition() == 1 ? 5000000 : 2500000; }
    private int videoBitrate() { return adaptiveBitrate == null ? maximumVideoBitrate() : adaptiveBitrate.target(); }
    private void showLiveStatus() {
        message(getString(R.string.live_bitrate, measuredBitrate / 1000000.0));
    }
    private void adaptBitrate() {
        if (camera == null || adaptiveBitrate == null || !dynamicBitrateSupported) return;
        var client = camera.getStreamClient();
        int previous = adaptiveBitrate.target();
        int target = adaptiveBitrate.sample(SystemClock.elapsedRealtime(), client.hasCongestion(20f),
            client.getDroppedVideoFrames() + client.getDroppedAudioFrames(),
            Integer.toUnsignedLong(client.getPacketsLost()), client.getRtt(), receivedPackets());
        if (target != previous) {
            try { camera.setVideoBitrateOnFly(target); }
            catch (RuntimeException error) {
                adaptiveBitrate.restoreTarget(previous);
                dynamicBitrateSupported = false;
                android.util.Log.w("CamaraEscolar", "El codificador no permite ajustar bitrate en directo", error);
            }
            showLiveStatus();
        }
    }
    private int latencyMs() { return LATENCIES[Math.max(0, latency.getSelectedItemPosition())]; }
    private Spinner selector(LinearLayout panel, String label, String[] options, int selected) {
        TextView text = new TextView(this);
        text.setText(label);
        panel.addView(text);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(selected, options.length - 1)));
        spinner.setContentDescription(label);
        panel.addView(spinner);
        return spinner;
    }
    private void updateReceiverHelp() {
        String receiverUrl = "srt://0.0.0.0:" + port.getText() + "?mode=listener&latency="
            + (latencyMs() * 1000) + "&timeout=5000000";
        receiverHelp.setText(getString(R.string.srt_receiver_help, receiverUrl));
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private EditText field(LinearLayout panel, String hint, String key, String fallback) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(getPreferences(0).getString(key, fallback));
        input.setContentDescription(hint);
        panel.addView(input);
        return input;
    }
    private Button button(LinearLayout panel, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        panel.addView(button);
        return button;
    }
    private ImageButton iconButton(LinearLayout panel, int icon, String description, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        button.setContentDescription(description);
        button.setPadding(dp(14), dp(14), dp(14), dp(14));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setBackground(circleBackground(Color.argb(220, 17, 24, 39)));
        button.setOnClickListener(listener);
        button.setOnLongClickListener(view -> {
            Toast.makeText(this, button.getContentDescription(), Toast.LENGTH_SHORT).show();
            return true;
        });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(56), dp(56));
        size.rightMargin = dp(12);
        panel.addView(button, size);
        return button;
    }
    private RippleDrawable circleBackground(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(color);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), background, mask);
    }
    private void updateMicrophoneIcon() {
        mute.setImageResource(muted ? R.drawable.ic_microphone_off : R.drawable.ic_microphone);
        mute.setContentDescription(muted ? getString(R.string.unmute_microphone) : getString(R.string.mute_microphone));
        mute.setSelected(muted);
        mute.setBackground(circleBackground(muted ? Color.rgb(185, 28, 28) : Color.argb(220, 17, 24, 39)));
    }
    private boolean permissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }
    private void preview() {
        if (!resumed || !surfaceReady || !permissions()) return;
        if (camera == null) {
            final int session = ++generation;
            camera = new SrtCamera2(previewView, new ConnectChecker() {
                private void update(Runnable task) { runOnUiThread(() -> { if (session == generation && resumed) task.run(); }); }
                @Override public void onConnectionStarted(String url) { update(() -> { if (active) connectingMessage(); }); }
                @Override public void onConnectionSuccess() { update(() -> {
                    if (!active) return;
                    recovery.connected(SystemClock.elapsedRealtime(), receivedPackets());
                    var client = camera.getStreamClient();
                    if (adaptiveBitrate != null) adaptiveBitrate.connected(SystemClock.elapsedRealtime(),
                        client.getDroppedVideoFrames() + client.getDroppedAudioFrames(),
                        Integer.toUnsignedLong(client.getPacketsLost()), receivedPackets());
                    measuredBitrate = 0;
                    camera.requestKeyFrame();
                    showLiveStatus();
                }); }
                @Override public void onConnectionFailed(String reason) { update(() -> reconnect(getString(R.string.connect_error))); }
                @Override public void onDisconnect() { update(() -> reconnect(getString(R.string.connection_interrupted))); }
                @Override public void onAuthError() { update(() -> fail(getString(R.string.connection_rejected))); }
                @Override public void onAuthSuccess() { }
                public void onNewBitrate(long bitrate) { update(() -> {
                    if (active && recovery.isLive()) { measuredBitrate = bitrate; showLiveStatus(); }
                }); }
            });
        }
        try {
            if (!camera.isOnPreview()) {
                // Configure the encoder BEFORE opening the camera. Camera2Base otherwise
                // requests its default 640x480 capture into a 16:9 GL render target.
                if (!camera.prepareVideo(videoWidth(), videoHeight(), 30, videoBitrate(), 1, 0)) {
                    release();
                    message(getString(R.string.unsupported_resolution));
                    return;
                }
                camera.startPreview(facing, videoWidth(), videoHeight(), 30, 0);
            }
            if (muted) camera.disableAudio();
            if (!active) message(getString(R.string.ready));
        } catch (Exception e) { message(getString(R.string.camera_permission_error)); }
    }
    private void toggle() {
        if (active) { release(); preview(); message(getString(R.string.stream_stopped)); return; }
        if (!permissions()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        try {
            String address = host.getText().toString().trim();
            int number = Integer.parseInt(port.getText().toString());
            if (address.isEmpty() || number < 1 || number > 65535) throw new IllegalArgumentException();
            URI uri = new URI("srt", null, address, number, "/camara", null, null);
            if (uri.getHost() == null) throw new IllegalArgumentException();
            adaptiveBitrate = new AdaptiveBitrate(resolution.getSelectedItemPosition() == 1 ? 1500000 : 700000,
                maximumVideoBitrate());
            dynamicBitrateSupported = true;
            preview();
            if (camera == null || !camera.isOnPreview()) return;
            getPreferences(0).edit().putString("host", address).putString("port", String.valueOf(number)).apply();
            streamUrl = uri.toASCIIString();
            reconnectCount = 0;
            active = true;
            recovery.start(SystemClock.elapsedRealtime(), receivedPackets());
            controls();
            updateReceiverHelp();
            startAttempt();
        } catch (IllegalArgumentException | java.net.URISyntaxException e) {
            message(getString(R.string.invalid_destination));
        } catch (Exception e) { fail(getString(R.string.encoder_start_error)); }
    }
    private void connectingMessage() {
        message(reconnectCount == 0 ? getString(R.string.connecting)
            : getString(R.string.reconnecting_attempt, reconnectCount));
    }
    private void startAttempt() {
        if (!active || !resumed || !surfaceReady) return;
        try {
            preview();
            if (!active) return; // Unsupported video profile stops the session in preview().
            if (camera == null || !camera.isOnPreview()) {
                reconnect(getString(R.string.camera_open_error)); return;
            }
            if (!camera.prepareAudio(128000, 48000, true)) {
                fail(getString(R.string.unsupported_audio)); return;
            }
            audioRouter.attach(camera);
            camera.getStreamClient().setLatency(latencyMs());
            camera.getStreamClient().setSocketTimeout(5000);
            // One retry owner: this Activity. Each attempt gets fresh sockets and encoders.
            camera.getStreamClient().setReTries(0);
            if (muted) camera.disableAudio(); else camera.enableAudio();
            connectingMessage();
            recoveryHandler.removeCallbacks(watchdog);
            recoveryHandler.postDelayed(watchdog, 1000);
            camera.startStream(streamUrl);
        } catch (Exception e) { reconnect(getString(R.string.stream_start_error)); }
    }
    private void reconnect(String reason) {
        if (!active || !resumed || !surfaceReady) return;
        long delay = recovery.failed();
        if (delay < 0) return; // Duplicate error/disconnect callback from the same attempt.
        if (adaptiveBitrate != null) adaptiveBitrate.connectionFailed();
        recoveryHandler.removeCallbacks(watchdog);
        recoveryHandler.removeCallbacks(retry);
        closeCamera(); // Invalidates callbacks before stopping the old transport.
        reconnectCount++;
        message(getString(R.string.retry_status, reason, delay / 1000, reconnectCount));
        recoveryHandler.postDelayed(retry, delay);
    }
    private void controls() {
        transmit.setText(active ? getString(R.string.stop_stream) : getString(R.string.start_stream));
        transmit.setTextColor(Color.WHITE);
        transmit.setBackgroundTintList(ColorStateList.valueOf(active ? Color.rgb(185, 28, 28) : Color.rgb(21, 128, 61)));
        host.setEnabled(!active); port.setEnabled(!active);
        resolution.setEnabled(!active); latency.setEnabled(!active);
    }
    private void stop() {
        active = false;
        recovery.stop();
        adaptiveBitrate = null;
        measuredBitrate = 0;
        updateLiveIndicator();
        recoveryHandler.removeCallbacksAndMessages(null);
        controls();
    }
    private void fail(String text) { release(); preview(); message(text); }
    private void release() {
        stop();
        closeCamera();
    }
    private void closeCamera() {
        ++generation;
        if (audioRouter != null) audioRouter.detach();
        SrtCamera2 previous = camera;
        camera = null;
        if (previous != null) {
            try { previous.stopStream(); }
            catch (RuntimeException error) { android.util.Log.w("CamaraEscolar", "Error cerrando emisión", error); }
            try { previous.stopPreview(); }
            catch (RuntimeException error) { android.util.Log.w("CamaraEscolar", "Error cerrando cámara", error); }
        }
    }
    private void message(String text) {
        status.setText(text);
        updateLiveIndicator();
    }
    private void updateLiveIndicator() {
        boolean live = active && resumed && recovery.isLive();
        if (live) {
            liveDot.setVisibility(View.VISIBLE);
            // Bitrate updates must not restart the blink on every status refresh.
            if (liveDot.getAnimation() == null) {
                AlphaAnimation blink = new AlphaAnimation(1f, 0.15f);
                blink.setDuration(550);
                blink.setRepeatMode(Animation.REVERSE);
                blink.setRepeatCount(Animation.INFINITE);
                liveDot.startAnimation(blink);
            }
        } else {
            liveDot.clearAnimation();
            liveDot.setVisibility(View.INVISIBLE);
        }
    }
    @Override protected void onResume() { super.onResume(); resumed = true; audioRouter.resume(); preview(); }
    @Override protected void onPause() { resumed = false; audioRouter.pause(); release(); super.onPause(); }
    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; preview(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; release(); }
    @Override public void onRequestPermissionsResult(int request, String[] names, int[] results) {
        super.onRequestPermissionsResult(request, names, results);
        if (permissions()) preview(); else message(getString(R.string.capture_permissions_required));
    }
}
