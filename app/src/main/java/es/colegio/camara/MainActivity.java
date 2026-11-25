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
    private LocalRtc localRtc;
    private static final String STREAM_MODE = "streamingModeV2";
    private boolean localMode;
    private Spinner mode;
    private EditText localName;
    private TextView localAddress, localRate, localLimitStatus;
    private Spinner localLimit, localMic;
    private TextView localMicStatus;
    private static final int[] LOCAL_VIDEO_LIMITS = {0, 2000000, 4000000, 6000000, 8000000};
    private LinearLayout localOptions, lanFields, internetFields;
    private Spinner webRtcConnection, webRtcProvider;
    private EditText signalingOrigin, remoteName;
    private final java.util.List<View> srtOptions = new java.util.ArrayList<>();
    private SidebarPanel sidebarPanel;
    private MicrophoneMeter meter;
    private EditText host, port;
    private TextView status;
    private View liveDot;
    private StreamButton transmit;
    private ImageButton mute;
    private Spinner resolution, latency, stabilization;
    private PhoneStabilization phoneStabilization;
    private TextView receiverHelp;
    private AudioInputRouter audioRouter;
    private Spinner audioInput;
    private TextView audioInputStatus;
    private static final int[] LATENCIES = {60, 120, 200, 400, 800};
    private CameraHelper.Facing facing = CameraHelper.Facing.BACK;
    private boolean surfaceReady, resumed, active, muted;
    private volatile int generation;
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
        sidebar.setPadding(dp(12), 0, dp(12), 0);
        root.addView(sidebar, new LinearLayout.LayoutParams(dp(290), -1));
        transmit = new StreamButton(this);
        transmit.setOnClickListener(view -> toggle());
        FrameLayout.LayoutParams transmitPosition = new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        transmitPosition.bottomMargin = dp(12);
        videoArea.addView(transmit, transmitPosition);
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable statusBackground = new GradientDrawable();
        statusBackground.setColor(0x99111827);
        statusBackground.setCornerRadius(dp(8));
        statusRow.setBackground(statusBackground);
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
        FrameLayout.LayoutParams statusPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        statusPosition.setMargins(dp(12), dp(12), dp(70), dp(12));
        videoArea.addView(statusRow, statusPosition);
        meter = new MicrophoneMeter(this);
        FrameLayout.LayoutParams meterPosition = new FrameLayout.LayoutParams(dp(14), dp(180), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        meterPosition.rightMargin = dp(8);
        videoArea.addView(meter, meterPosition);
        localRate = new TextView(this);
        localRate.setTextColor(Color.WHITE);
        localRate.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable rateBackground = new GradientDrawable();
        rateBackground.setColor(0x99111827);
        rateBackground.setCornerRadius(dp(8));
        localRate.setBackground(rateBackground);
        FrameLayout.LayoutParams ratePosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        ratePosition.setMargins(0, dp(12), dp(12), 0);
        videoArea.addView(localRate, ratePosition);
        localRate.setVisibility(View.GONE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(panel);
        sidebar.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        int previousMode = getPreferences(0).getInt("streamingMode", 0);
        int migratedMode = previousMode == 1 ? 2 : previousMode == 2 ? 1 : 0;
        int storedMode = getPreferences(0).getInt(STREAM_MODE, migratedMode);
        int initialMode = storedMode == 0 ? 0 : 1;
        if (storedMode == 2) getPreferences(0).edit().putInt("webrtcConnection", 1).putInt("remoteProvider", 0).apply();
        mode = selector(panel, getString(R.string.streaming_mode),
            new String[]{getString(R.string.srt_mode), getString(R.string.webrtc_mode)}, initialMode);
        int srtStart = panel.getChildCount();
        host = field(panel, getString(R.string.receiver_host), "host", "192.168.1.100");
        port = field(panel, getString(R.string.udp_port), "port", "9000");
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        for (int i = srtStart; i < panel.getChildCount(); i++) srtOptions.add(panel.getChildAt(i));
        resolution = selector(panel, getString(R.string.resolution), new String[]{getString(R.string.resolution_720), getString(R.string.resolution_1080)},
            getPreferences(0).getInt("resolution", 0));
        srtStart = panel.getChildCount();
        stabilization = selector(panel, getString(R.string.stabilization_label),
            new String[]{getString(R.string.stabilization_off), getString(R.string.stabilization_eis), getString(R.string.stabilization_ois)},
            getPreferences(0).getInt("stabilization", 0));
        TextView stabilizationStatus = new TextView(this);
        stabilizationStatus.setTextSize(12);
        panel.addView(stabilizationStatus);
        phoneStabilization = new PhoneStabilization(stabilizationStatus);
        TextView stabilizationHelp = new TextView(this);
        stabilizationHelp.setText(R.string.stabilization_help);
        stabilizationHelp.setTextSize(12);
        panel.addView(stabilizationHelp);
        stabilization.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                getPreferences(0).edit().putInt("stabilization", position).apply();
                if (camera != null && !active) phoneStabilization.attach(camera, position);
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
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
        for (int i = srtStart; i < panel.getChildCount(); i++) srtOptions.add(panel.getChildAt(i));
        localOptions = new LinearLayout(this);
        localOptions.setOrientation(LinearLayout.VERTICAL);
        panel.addView(localOptions);
        String fallback = getSharedPreferences("LocalRtcActivity", 0).getString("cameraName",
            "villacam-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        webRtcConnection = selector(localOptions, getString(R.string.webrtc_connection),
            new String[]{getString(R.string.webrtc_lan), getString(R.string.webrtc_internet)},
            getPreferences(0).getInt("webrtcConnection", 0));
        lanFields = new LinearLayout(this);
        lanFields.setOrientation(LinearLayout.VERTICAL);
        localOptions.addView(lanFields);
        internetFields = new LinearLayout(this);
        internetFields.setOrientation(LinearLayout.VERTICAL);
        localOptions.addView(internetFields);
        internetFields.setVisibility(View.GONE);
        webRtcProvider = selector(internetFields, getString(R.string.remote_provider),
            new String[]{getString(R.string.remote_provider_ninja), getString(R.string.remote_provider_custom)},
            getPreferences(0).getInt("remoteProvider", 0));
        webRtcProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                getPreferences(0).edit().putInt("remoteProvider", position).apply();
                updateWebRtcConnection();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        signalingOrigin = field(internetFields, getString(R.string.remote_server), "remoteServer", "");
        signalingOrigin.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        remoteName = field(internetFields, getString(R.string.remote_camera_name), "remoteCameraName", fallback);
        remoteName.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        localName = field(lanFields, getString(R.string.local_name), "localName", fallback);
        localName.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        localAddress = new TextView(this);
        localAddress.setTextIsSelectable(true);
        localOptions.addView(localAddress);
        TextView nameHelp = new TextView(this);
        nameHelp.setText(R.string.local_name_help);
        nameHelp.setTextSize(12);
        lanFields.addView(nameHelp);
        updateWebRtcConnection();
        webRtcConnection.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                getPreferences(0).edit().putInt("webrtcConnection", position).apply();
                updateWebRtcConnection();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        String[] limits = new String[LOCAL_VIDEO_LIMITS.length];
        limits[0] = getString(R.string.audio_automatic);
        for (int i = 1; i < limits.length; i++)
            limits[i] = getString(R.string.local_video_limit_value, LOCAL_VIDEO_LIMITS[i] / 1000000);
        localLimit = selector(localOptions, getString(R.string.local_video_limit), limits,
            getPreferences(0).getInt("localVideoLimit", 0));
        localLimitStatus = new TextView(this);
        localOptions.addView(localLimitStatus);
        localMic = selector(localOptions, getString(R.string.audio_input_label),
            new String[]{getString(R.string.audio_auto_android), getString(R.string.audio_internal_mic), getString(R.string.audio_usb_mic)},
            getPreferences(0).getInt("localAudioInput", 0));
        localMicStatus = new TextView(this);
        localOptions.addView(localMicStatus);
        TextView localHelp = new TextView(this);
        localHelp.setText(R.string.local_native_help);
        localOptions.addView(localHelp);
        localOptions.setVisibility(View.GONE);
        localRtc = new LocalRtc(this, videoArea, meter);
        localMic.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                getPreferences(0).edit().putInt("localAudioInput", position).apply();
                localRtc.selectAudioInput(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        iconButton(videoControls, R.drawable.ic_switch_camera, getString(R.string.switch_camera), v -> {
            if (localMode) { localRtc.switchCamera(); return; }
            if (camera != null && camera.isOnPreview()) {
                try { camera.switchCamera(); facing = camera.getCameraFacing();
                    phoneStabilization.attach(camera, stabilization.getSelectedItemPosition()); }
                catch (Exception e) { message(getString(R.string.switch_camera_error)); }
            }
        });
        mute = iconButton(videoControls, R.drawable.ic_microphone, getString(R.string.mute_microphone), v -> {
            muted = !muted;
            if (localMode) localRtc.mute(muted);
            if (camera != null) { if (muted) camera.disableAudio(); else camera.enableAudio(); }
            updateMicrophoneIcon();
        });
        controls();
        receiverHelp = new TextView(this);
        sidebarPanel = new SidebarPanel(this, sidebar, scroll, panel);
        panel.addView(receiverHelp);
        srtOptions.add(receiverHelp);
        updateReceiverHelp();
        setContentView(root);
        preview.getHolder().addCallback(this);
        // A fresh callback per session prevents old disconnect events changing a new session.
        previewView = preview;
        resolution.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                getPreferences(0).edit().putInt("resolution", position).apply();
                if (localMode) {
                    if (resumed && permissions() && !localRtc.running()) openLocalCamera();
                } else if (!active) { release(); preview(); }
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
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                getPreferences(0).edit().putInt(STREAM_MODE, position).apply();
                if ((position == 1) != localMode) {
                    if (position == 1) {
                        release();
                        localMode = true;
                        previewView.setVisibility(View.GONE);
                        if (resumed && permissions()) openLocalCamera();
                    } else {
                        localRtc.closeCamera();
                        localMode = false;
                        previewView.setVisibility(View.VISIBLE);
                        preview();
                    }
                    for (View option : srtOptions) option.setVisibility(localMode ? View.GONE : View.VISIBLE);
                    localOptions.setVisibility(localMode ? View.VISIBLE : View.GONE);
                    controls();
                }
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        if (!permissions())
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1);
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
        meter.setMuted(muted);
        mute.setBackground(circleBackground(muted ? Color.rgb(185, 28, 28) : Color.argb(220, 17, 24, 39)));
    }
    private boolean permissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }
    private void preview() {
        if (localMode || mode.getSelectedItemPosition() != 0) return;
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
                phoneStabilization.attach(camera, stabilization.getSelectedItemPosition());
            }
            if (muted) camera.disableAudio();
            if (!active) message(getString(R.string.ready));
        } catch (Exception e) { message(getString(R.string.camera_permission_error)); }
    }
    private void openLocalCamera() {
        localRtc.selectAudioInput(localMic.getSelectedItemPosition());
        localRtc.openCamera(videoWidth(), videoHeight());
        localRtc.mute(muted);
        if (localRtc.hasCamera()) localStatus(R.string.ready, false);
    }

    void localStatus(int text, boolean live) {
        localLive = live;
        message(getString(text));
    }

    void localVideoBitrate(double mbps) {
        localRate.setVisibility(localMode && mbps >= 0 ? View.VISIBLE : View.GONE);
        if (mbps >= 0) localRate.setText(getString(R.string.local_video_rate, mbps));
    }
    void localBitrateLimitRejected() { localLimitStatus.setText(R.string.local_video_limit_rejected); }

    void localAudioStatus(String text) { localMicStatus.setText(text); }
    void localLinks(String address) { localAddress.setText(address); }
    void localControls() { controls(); }
    private boolean localLive;

    private void updateWebRtcConnection() {
        boolean internet = webRtcConnection.getSelectedItemPosition() == 1;
        lanFields.setVisibility(internet ? View.GONE : View.VISIBLE);
        internetFields.setVisibility(internet ? View.VISIBLE : View.GONE);
        signalingOrigin.setVisibility(webRtcProvider.getSelectedItemPosition() == 1 ? View.VISIBLE : View.GONE);
    }

    private void toggleLocal() {
        if (!permissions()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        if (localRtc.running()) {
            localRtc.stop();
            localStatus(R.string.ready, false);
        } else if (webRtcConnection.getSelectedItemPosition() == 1) {
            boolean ninja = webRtcProvider.getSelectedItemPosition() == 0;
            String origin = ninja ? null : RemoteRtc.httpsOrigin(signalingOrigin.getText().toString());
            if (!ninja && origin == null) {
                signalingOrigin.setError(getString(R.string.remote_invalid_server));
                return;
            }
            String name = remoteName.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            if (!name.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                remoteName.setError(getString(R.string.local_name_error));
                return;
            }
            String cameraId = getPreferences(0).getString("remoteCameraId", "");
            if (cameraId.isEmpty()) cameraId = java.util.UUID.randomUUID().toString();
            remoteName.setText(name);
            if (!localRtc.hasCamera()) openLocalCamera();
            int limit = localLimit.getSelectedItemPosition();
            var settings = getPreferences(0).edit().putInt("localVideoLimit", limit)
                .putString("remoteCameraName", name).putString("remoteCameraId", cameraId);
            if (!ninja) settings.putString("remoteServer", origin);
            settings.apply();
            localLimitStatus.setText("");
            if (ninja) localRtc.startWhip(name, LOCAL_VIDEO_LIMITS[limit]);
            else localRtc.startRemote(origin, name, cameraId, LOCAL_VIDEO_LIMITS[limit]);
        } else {
            String name = localName.getText().toString().trim().toLowerCase(java.util.Locale.ROOT)
                .replaceFirst("[.]local[.]?$", "");
            if (!name.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                localName.setError(getString(R.string.local_name_error));
                return;
            }
            getPreferences(0).edit().putString("localName", name).apply();
            localName.setText(name + ".local");
            if (!localRtc.hasCamera()) openLocalCamera();
            int limit = localLimit.getSelectedItemPosition();
            getPreferences(0).edit().putInt("localVideoLimit", limit).apply();
            localLimitStatus.setText("");
            localRtc.start(name, LOCAL_VIDEO_LIMITS[limit]);
        }
        controls();
    }

    private void toggle() {
        if (localMode) { toggleLocal(); return; }
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
            final int audioSession = generation;
            camera.setCustomAudioEffect(new com.pedro.encoder.input.audio.CustomAudioEffect() {
                @Override public byte[] process(byte[] pcm) {
                    if (audioSession == generation) meter.sample(pcm);
                    return pcm; // Observe only: the encoder receives the identical samples.
                }
            });
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
        boolean busy = active || localRtc.running();
        transmit.setStreaming(busy);
        localName.setEnabled(!busy);
        localLimit.setEnabled(!busy);
        webRtcConnection.setEnabled(!busy);
        webRtcProvider.setEnabled(!busy);
        signalingOrigin.setEnabled(!busy);
        remoteName.setEnabled(!busy);
        mode.setEnabled(!busy);
        host.setEnabled(!active); port.setEnabled(!active);
        resolution.setEnabled(!busy); latency.setEnabled(!active);
        stabilization.setEnabled(!active);
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
        meter.reset();
        phoneStabilization.detach();
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
        boolean live = resumed && (localMode ? localLive : active && recovery.isLive());
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
    @Override protected void onResume() { super.onResume(); resumed = true; audioRouter.resume(); sidebarPanel.resume(); if (localMode && permissions()) openLocalCamera(); else preview(); }
    @Override protected void onPause() { resumed = false; sidebarPanel.pause(); audioRouter.pause(); localRtc.closeCamera(); release(); super.onPause(); }
    @Override protected void onDestroy() { localRtc.destroy(); super.onDestroy(); }
    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; preview(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; if (!localMode) release(); }
    @Override public void onRequestPermissionsResult(int request, String[] names, int[] results) {
        super.onRequestPermissionsResult(request, names, results);
        if (permissions()) { if (localMode && resumed) openLocalCamera(); else preview(); } else message(getString(R.string.capture_permissions_required));
    }
}
