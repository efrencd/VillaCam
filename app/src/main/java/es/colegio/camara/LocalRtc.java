package es.colegio.camara;

import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioDeviceCallback;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

final class LocalRtc {
    private static final int PORT = 8080;

    static {
        // This JmDNS setting is read once, when its DNS constants are initialized.
        // Keep stale address records short-lived if the old Wi-Fi cannot send a goodbye.
        System.setProperty("net.dns.ttl", "60");
    }
    private final MainActivity activity;
    private final FrameLayout preview;
    private final MicrophoneMeter meter;
    private volatile boolean meterEnabled;
    private int videoLimit;
    private long lastStatsRequest;
    private java.util.Map<String, Number> previousBytes = new java.util.HashMap<>();
    private double previousTimestamp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EglBase egl;
    private JavaAudioDeviceModule audioDevice;
    private final AudioManager audioManager;
    private int inputMode;
    private final AudioDeviceCallback audioDevices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { applyAudioInput(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { applyAudioInput(); }
    };
    private PeerConnectionFactory factory;
    private SurfaceTextureHelper texture;
    private SurfaceViewRenderer renderer;
    private VideoCapturer capturer;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private VideoTrack video;
    private AudioTrack audio;
    private PeerConnection peer;
    private boolean capturing;
    private String viewerId;
    private volatile LanSession session;
    private boolean closed;
    private boolean live;

    LocalRtc(MainActivity activity, FrameLayout preview, MicrophoneMeter meter) {
        this.activity = activity;
        this.preview = preview;
        this.meter = meter;
        audioManager = activity.getSystemService(AudioManager.class);
    }

    void openCamera(int width, int height) {
        closeCamera();
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(activity).createInitializationOptions());
            egl = EglBase.create();
            audioDevice = JavaAudioDeviceModule.builder(activity)
                .setSamplesReadyCallback(samples -> {
                    if (meterEnabled && samples.getAudioFormat() == android.media.AudioFormat.ENCODING_PCM_16BIT)
                        meter.sample(samples.getData());
                }).createAudioDeviceModule();
            applyAudioInput();
            audioManager.registerAudioDeviceCallback(audioDevices, main);
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDevice)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .createPeerConnectionFactory();
            renderer = new SurfaceViewRenderer(activity);
            renderer.init(egl.getEglBaseContext(), null);
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
            preview.addView(renderer, 0, new FrameLayout.LayoutParams(-1, -1));
            CameraEnumerator cameras = new Camera2Enumerator(activity);
            for (String name : cameras.getDeviceNames()) {
                if (cameras.isBackFacing(name)) {
                    capturer = cameras.createCapturer(name, null);
                    if (capturer != null) break;
                }
            }
            if (capturer == null) {
                for (String name : cameras.getDeviceNames()) {
                    capturer = cameras.createCapturer(name, null);
                    if (capturer != null) break;
                }
            }
            if (capturer == null) throw new IllegalStateException("No Camera2 capture device");
            texture = SurfaceTextureHelper.create("VillaCam capture", egl.getEglBaseContext());
            videoSource = factory.createVideoSource(false);
            videoSource.adaptOutputFormat(width, height, 30);
            capturer.initialize(texture, activity, videoSource.getCapturerObserver());
            capturer.startCapture(width, height, 30);
            capturing = true;
            video = factory.createVideoTrack("camera", videoSource);
            video.addSink(renderer);
            audioSource = factory.createAudioSource(new MediaConstraints());
            audio = factory.createAudioTrack("microphone", audioSource);
        } catch (RuntimeException error) {
            android.util.Log.w("VillaCam", "Native WebRTC camera", error);
            closeCamera();
            activity.localStatus(R.string.local_error, false);
        }
    }

    void selectAudioInput(int mode) {
        inputMode = mode;
        applyAudioInput();
    }

    private void applyAudioInput() {
        if (audioDevice == null) return;
        AudioDeviceInfo internal = null, usb = null;
        try {
            for (AudioDeviceInfo input : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                int type = input.getType();
                if (internal == null && type == AudioDeviceInfo.TYPE_BUILTIN_MIC) internal = input;
                if (usb == null && (type == AudioDeviceInfo.TYPE_USB_DEVICE
                    || type == AudioDeviceInfo.TYPE_USB_ACCESSORY || type == AudioDeviceInfo.TYPE_USB_HEADSET)) usb = input;
            }
            AudioDeviceInfo preferred = inputMode == AudioInputPolicy.USB ? (usb != null ? usb : internal)
                : inputMode == AudioInputPolicy.INTERNAL ? internal : null;
            audioDevice.setPreferredInputDevice(preferred);
            String note = inputMode == AudioInputPolicy.USB && usb == null
                ? activity.getString(R.string.audio_usb_missing) : inputMode == AudioInputPolicy.INTERNAL && internal == null
                ? activity.getString(R.string.audio_internal_missing) : "";
            String name = preferred == null ? activity.getString(R.string.audio_automatic) : preferred.getProductName().toString();
            activity.localAudioStatus(note + activity.getString(R.string.local_audio_requested, name));
        } catch (RuntimeException error) {
            activity.localAudioStatus(activity.getString(R.string.audio_route_error));
            android.util.Log.w("VillaCam", "WebRTC microphone routing", error);
        }
    }

    void mute(boolean value) { if (audio != null) audio.setEnabled(!value); }

    void switchCamera() {
        if (capturer instanceof CameraVideoCapturer) ((CameraVideoCapturer) capturer).switchCamera(null);
    }

    private String publishingName;
    private RemoteRtc remote;
    private NinjaWhip whip;
    private String whipName;
    private android.net.Network whipNetwork;

    boolean startWhip(String name, int limit) {
        if (video == null || running()) return false;
        videoLimit = limit;
        whipName = name;
        whipNetwork = activeNetwork();
        activity.localLinks("https://vdo.ninja/?whip=" + name);
        createWhipAttempt();
        main.post(remoteStats);
        return true;
    }

    private android.net.Network activeNetwork() {
        return activity.getSystemService(android.net.ConnectivityManager.class).getActiveNetwork();
    }

    private void createWhipAttempt() {
        if (whipName == null) return;
        activity.localStatus(R.string.remote_connecting, false);
        NinjaWhip attempt = new NinjaWhip(main, whipName);
        whip = attempt;
        attempt.discover(servers -> {
            if (whip != attempt) return;
            if (servers == null) { retryWhip(attempt); return; }
            startWhipPeer(attempt, servers);
        });
    }

    private final Runnable whipRetry = this::createWhipAttempt;

    private void retryWhip(NinjaWhip attempt) {
        if (whip != attempt || whipName == null) return;
        attempt.stop();
        whip = null;
        closePeer();
        activity.localStatus(R.string.remote_connecting, false);
        main.removeCallbacks(whipRetry);
        main.postDelayed(whipRetry, 1800);
    }

    private void startWhipPeer(NinjaWhip attempt, java.util.List<PeerConnection.IceServer> servers) {
        String id = java.util.UUID.randomUUID().toString();
        final boolean[] offerSent = {false};
        final boolean[] localSet = {false};
        Runnable send = () -> {
            if (whip != attempt || !id.equals(viewerId) || peer == null || offerSent[0] || !localSet[0]) return;
            SessionDescription offer = peer.getLocalDescription();
            if (offer == null) { retryWhip(attempt); return; }
            offerSent[0] = true;
            attempt.offer(offer.description, answer -> {
                if (whip != attempt || !id.equals(viewerId) || peer == null) return;
                if (answer == null) { retryWhip(attempt); return; }
                peer.setRemoteDescription(new Sdp() {
                    @Override public void onSetFailure(String error) { main.post(() -> retryWhip(attempt)); }
                }, new SessionDescription(SessionDescription.Type.ANSWER, answer));
                main.postDelayed(() -> { if (whip == attempt && !live) retryWhip(attempt); }, 12000);
            });
        };
        createPeer(id, servers, send);
        if (peer == null) { retryWhip(attempt); return; }
        PeerConnection current = peer;
        RtpTransceiver.RtpTransceiverInit sendOnly = new RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, java.util.Collections.singletonList("villacam"));
        RtpSender sender = current.addTransceiver(video, sendOnly).getSender();
        meterEnabled = true;
        current.addTransceiver(audio, sendOnly);
        current.createOffer(new Sdp() {
            @Override public void onCreateSuccess(SessionDescription description) {
                main.post(() -> {
                    if (peer != current || whip != attempt) return;
                    current.setLocalDescription(new Sdp() {
                        @Override public void onSetSuccess() {
                            main.post(() -> {
                                if (peer != current || whip != attempt) return;
                                localSet[0] = true;
                                applyVideoLimit(sender);
                                if (current.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) send.run();
                                else main.postDelayed(send, 5000);
                            });
                        }
                        @Override public void onSetFailure(String error) { main.post(() -> retryWhip(attempt)); }
                    }, description);
                });
            }
            @Override public void onCreateFailure(String error) { main.post(() -> retryWhip(attempt)); }
        }, new MediaConstraints());
    }

    private void applyVideoLimit(RtpSender sender) {
        if (videoLimit == 0 || sender == null) return;
        RtpParameters parameters = sender.getParameters();
        for (RtpParameters.Encoding encoding : parameters.encodings) encoding.maxBitrateBps = videoLimit;
        if (parameters.encodings.isEmpty() || !sender.setParameters(parameters))
            activity.localBitrateLimitRejected();
    }

    boolean startRemote(String origin, String name, String cameraId, int limit) {
        if (video == null || running()) return false;
        videoLimit = limit;
        remote = new RemoteRtc(activity, new RemoteRtc.Listener() {
            @Override public void link(String url) { activity.localLinks(url); }
            @Override public void waiting() {
                closePeer();
                activity.localStatus(R.string.remote_connecting, false);
            }
            @Override public void failed() {
                stop();
                activity.localStatus(R.string.remote_error, false);
                activity.localControls();
            }
            @Override public void nameInUse() {
                stop();
                activity.localStatus(R.string.remote_name_in_use, false);
                activity.localControls();
            }
            @Override public void offer(String id, String sdp, java.util.List<PeerConnection.IceServer> servers) {
                closePeer();
                answer(id, sdp, servers);
            }
        });
        activity.localStatus(R.string.remote_connecting, false);
        remote.start(origin, name, cameraId);
        main.post(remoteStats);
        return true;
    }

    private final Runnable remoteStats = new Runnable() {
        @Override public void run() {
            if (remote == null && whipName == null) return;
            if (whipName != null) {
                android.net.Network network = activeNetwork();
                if (!java.util.Objects.equals(network, whipNetwork)) {
                    whipNetwork = network;
                    if (whip != null) retryWhip(whip);
                }
            }
            collectStats();
            main.postDelayed(this, 1000);
        }
    };

    boolean running() { return publishingName != null || remote != null || whipName != null; }

    boolean start(String name, int videoLimit) {
        if (video == null || running()) return false;
        publishingName = name;
        this.videoLimit = videoLimit;
        networkWatch.run();
        return true;
    }

    // Network identity matters even when two access networks hand out the same address.
    private final Runnable networkWatch = new Runnable() {
        @Override public void run() {
            if (!running()) return;
            LanEndpoint endpoint = lanEndpoint();
            if (endpoint == null) {
                if (session != null) detachNetwork();
                activity.localStatus(R.string.local_wait, false);
            } else if (session == null || !session.endpoint.matches(endpoint)) {
                detachNetwork();
                attachNetwork(endpoint);
            }
            LanSession current = session;
            if (current != null && current.viewerReady && current.hostname == null
                && android.os.SystemClock.elapsedRealtime() >= current.nextMdnsAttempt) {
                current.nextMdnsAttempt = Long.MAX_VALUE; // One queued attempt per session.
                worker.execute(() -> {
                    if (session != current) return;
                    current.announce();
                    main.post(() -> { if (session == current) showLinks(current); });
                });
            }
            main.postDelayed(this, 500);
        }
    };

    private void attachNetwork(LanEndpoint endpoint) {
        LanSession next = new LanSession(endpoint, publishingName);
        session = next;
        activity.localStatus(R.string.local_reconnecting, false);
        worker.execute(() -> {
            if (session != next) return;
            try {
                next.open();
                main.post(() -> {
                    if (session != next) return;
                    activity.localStatus(R.string.local_obs, false);
                    main.post(poll);
                });
                if (session != next) return;
                next.announce();
                next.viewerReady = true;
                main.post(() -> { if (session == next) showLinks(next); });
            } catch (Exception error) {
                android.util.Log.w("VillaCam", "Native WebRTC server", error);
                main.post(() -> {
                    if (session == next) {
                        detachNetwork();
                        activity.localStatus(R.string.local_reconnecting, false);
                    }
                });
            }
        });
    }

    private void showLinks(LanSession current) {
        String ip = "http://" + current.address.getHostAddress() + ":" + PORT + "/";
        String hostname = current.hostname;
        if (hostname == null) {
            activity.localLinks(activity.getString(R.string.local_mdns_unavailable) + "\n" + ip);
            return;
        }
        String name = hostname.replaceFirst("[.]$", "");
        String urls = "http://" + name + ":" + PORT + "/\n" + ip;
        if (!name.equalsIgnoreCase(current.cameraName + ".local"))
            urls = activity.getString(R.string.local_mdns_conflict, name) + "\n" + urls;
        activity.localLinks(urls);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (session == null) return;
            LocalRtcServer.Session state = session.state;
            String id, offer;
            synchronized (state) {
                state.expire();
                id = state.id;
                offer = state.offer;
            }
            if (id == null) {
                if (peer != null) closePeer();
            } else if (!id.equals(viewerId)) {
                closePeer();
                answer(id, offer, java.util.Collections.emptyList());
            }
            collectStats();
            main.postDelayed(this, 200);
        }
    };

    private void collectStats() {
            long now = android.os.SystemClock.elapsedRealtime();
            if (live && peer != null && now - lastStatsRequest >= 1000) {
                lastStatsRequest = now;
                PeerConnection current = peer;
                current.getStats(report -> main.post(() -> {
                    if (peer == current && live) updateBitrate(report);
                }));
            }
    }

    private void createPeer(String id, java.util.List<PeerConnection.IceServer> servers, Runnable gathered) {
        viewerId = id;
        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(servers);
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        peer = factory.createPeerConnection(configuration, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState value) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState value) {
                main.post(() -> {
                    if (!id.equals(viewerId)) return;
                    live = value == PeerConnection.IceConnectionState.CONNECTED || value == PeerConnection.IceConnectionState.COMPLETED;
                    if (!live) clearBitrate();
                    if ((remote != null || whip != null) && (value == PeerConnection.IceConnectionState.FAILED
                        || value == PeerConnection.IceConnectionState.DISCONNECTED)) {
                        main.postDelayed(() -> {
                            if (!id.equals(viewerId) || live) return;
                            if (remote != null) remote.reconnect();
                            else if (whip != null) retryWhip(whip);
                        }, 2000);
                    }
                    activity.localStatus(live ? R.string.local_live : whipName != null ? R.string.remote_connecting : R.string.local_obs, live);
                });
            }
            @Override public void onIceConnectionReceivingChange(boolean value) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState value) {
                if (value == PeerConnection.IceGatheringState.COMPLETE) main.post(gathered);
            }
            @Override public void onIceCandidate(IceCandidate value) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] value) {}
            @Override public void onAddStream(MediaStream value) {}
            @Override public void onRemoveStream(MediaStream value) {}
            @Override public void onDataChannel(DataChannel value) {}
            @Override public void onRenegotiationNeeded() {}
        });
    }

    private void answer(String id, String offer, java.util.List<PeerConnection.IceServer> servers) {
        createPeer(id, servers, () -> publishAnswer(id));
        if (peer == null) return;
        PeerConnection current = peer;
        if (videoLimit > 0) {
            // Seed the bandwidth estimate without setting a floor that would fight congestion control.
            current.setBitrate(null, Math.min(videoLimit, 3000000), null);
        }
        RtpSender videoSender = current.addTrack(video, java.util.Collections.singletonList("villacam"));
        meterEnabled = true;
        current.addTrack(audio, java.util.Collections.singletonList("villacam"));
        current.setRemoteDescription(new Sdp() {
            @Override public void onSetSuccess() {
                main.post(() -> {
                    if (peer != current) return;
                    current.createAnswer(new Sdp() {
                        @Override public void onCreateSuccess(SessionDescription answer) {
                            main.post(() -> {
                                if (peer != current) return;
                                current.setLocalDescription(new Sdp() {
                                    @Override public void onSetSuccess() {
                                        main.post(() -> {
                                            if (peer == current) applyVideoLimit(videoSender);
                                        });
                                        main.postDelayed(() -> publishAnswer(id), remote == null ? 2000 : 5000);
                                    }
                                }, answer);
                            });
                        }
                    }, new MediaConstraints());
                });
            }
        }, new SessionDescription(SessionDescription.Type.OFFER, offer));
    }

    private abstract static class Sdp implements SdpObserver {
        @Override public void onSetSuccess() {}
        @Override public void onCreateSuccess(SessionDescription description) {}
        @Override public void onSetFailure(String error) { android.util.Log.w("VillaCam", error); }
        @Override public void onCreateFailure(String error) { android.util.Log.w("VillaCam", error); }
    }

    private String answeredId;

    private void publishAnswer(String id) {
        if (peer == null || !id.equals(viewerId) || id.equals(answeredId)) return;
        SessionDescription answer = peer.getLocalDescription();
        if (answer == null) return;
        answeredId = id;
        if (remote != null) {
            remote.answer(id, answer.description);
            return;
        }
        if (session == null) return;
        LocalRtcServer.Session state = session.state;
        synchronized (state) {
            if (answer != null && id.equals(state.id)) state.answer = answer.description;
        }
    }

    private void updateBitrate(RTCStatsReport report) {
        if (remote != null || whipName != null) {
            for (RTCStats stat : report.getStatsMap().values()) {
                if (!"transport".equals(stat.getType())) continue;
                RTCStats pair = report.getStatsMap().get(stat.getMembers().get("selectedCandidatePairId"));
                if (pair == null) continue;
                RTCStats local = report.getStatsMap().get(pair.getMembers().get("localCandidateId"));
                RTCStats other = report.getStatsMap().get(pair.getMembers().get("remoteCandidateId"));
                if (local != null && other != null) {
                    boolean relay = "relay".equals(local.getMembers().get("candidateType"))
                        || "relay".equals(other.getMembers().get("candidateType"));
                    activity.localStatus(relay ? R.string.remote_relay : R.string.remote_direct, true);
                    break;
                }
            }
        }
        java.util.Map<String, Number> bytes = new java.util.HashMap<>();
        long sent = 0;
        boolean comparable = false;
        for (RTCStats stat : report.getStatsMap().values()) {
            if (!"outbound-rtp".equals(stat.getType()) || !"video".equals(stat.getMembers().get("kind"))) continue;
            Object counter = stat.getMembers().get("bytesSent");
            if (!(counter instanceof Number)) continue;
            Number current = (Number) counter;
            bytes.put(stat.getId(), current);
            Number previous = previousBytes.get(stat.getId());
            if (previous != null && current.longValue() >= previous.longValue()) {
                sent += current.longValue() - previous.longValue();
                comparable = true;
            }
        }
        double elapsedUs = report.getTimestampUs() - previousTimestamp;
        if (comparable && elapsedUs > 0) activity.localVideoBitrate(sent * 8.0 / elapsedUs);
        previousBytes = bytes;
        previousTimestamp = report.getTimestampUs();
    }

    private void clearBitrate() {
        previousBytes.clear();
        previousTimestamp = 0;
        lastStatsRequest = 0;
        activity.localVideoBitrate(-1);
    }

    private void closePeer() {
        meterEnabled = false;
        clearBitrate();
        viewerId = answeredId = null;
        if (peer != null) { peer.dispose(); peer = null; }
        meter.reset();
        if (live) {
            live = false;
            activity.localStatus(R.string.local_obs, false);
        }
    }

    void stop() {
        whipName = null;
        main.removeCallbacks(whipRetry);
        if (whip != null) {
            whip.stop();
            whip = null;
        }
        if (remote != null) {
            remote.stop();
            remote = null;
        }
        main.removeCallbacks(remoteStats);
        publishingName = null;
        main.removeCallbacks(networkWatch);
        detachNetwork();
    }

    private void detachNetwork() {
        LanSession previous = session;
        session = null;
        main.removeCallbacks(poll);
        closePeer();
        if (previous != null) worker.execute(previous::close);
        activity.localLinks("");
    }

    void closeCamera() {
        stop();
        if (capturer != null) {
            if (capturing) {
                try { capturer.stopCapture(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                catch (RuntimeException error) { android.util.Log.w("VillaCam", "Stopping camera", error); }
            }
            capturer.dispose();
        }
        if (video != null) video.dispose();
        if (audio != null) audio.dispose();
        if (videoSource != null) videoSource.dispose();
        if (audioSource != null) audioSource.dispose();
        if (texture != null) texture.dispose();
        if (renderer != null) { preview.removeView(renderer); renderer.release(); }
        if (factory != null) factory.dispose();
        if (audioDevice != null) {
            audioManager.unregisterAudioDeviceCallback(audioDevices);
            audioDevice.release();
            audioDevice = null;
        }
        if (egl != null) egl.release();
        capturing = false;
        capturer = null; video = null; audio = null; videoSource = null; audioSource = null;
        texture = null; renderer = null; factory = null; egl = null;
    }

    boolean hasCamera() { return video != null; }

    void destroy() {
        if (closed) return;
        closeCamera();
        closed = true;
        worker.shutdown();
    }

    private static final class LanEndpoint {
        final Network network;
        final Inet4Address address;
        final String interfaceName;
        final int prefixLength;

        LanEndpoint(Network network, LinkProperties properties, LinkAddress link) {
            this.network = network;
            address = (Inet4Address) link.getAddress();
            interfaceName = properties.getInterfaceName();
            prefixLength = link.getPrefixLength();
        }

        boolean matches(LanEndpoint other) {
            return network.equals(other.network) && address.equals(other.address)
                && java.util.Objects.equals(interfaceName, other.interfaceName)
                && prefixLength == other.prefixLength;
        }
    }

    private LanEndpoint lanEndpoint() {
        ConnectivityManager manager = activity.getSystemService(ConnectivityManager.class);
        Network[] networks = manager.getAllNetworks();
        Network active = manager.getActiveNetwork();
        int[] transports = {NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_ETHERNET};
        for (int transport : transports) {
            LanEndpoint fallback = null;
            for (Network network : networks) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                if (caps == null || !caps.hasTransport(transport)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue;
                LinkProperties properties = manager.getLinkProperties(network);
                if (properties == null) continue;
                for (LinkAddress link : properties.getLinkAddresses()) {
                    if (!(link.getAddress() instanceof Inet4Address) || link.getAddress().isLoopbackAddress()
                        || link.getAddress().isLinkLocalAddress() || link.getAddress().isAnyLocalAddress()) continue;
                    LanEndpoint endpoint = new LanEndpoint(network, properties, link);
                    if (network.equals(active)) return endpoint;
                    if (fallback == null) fallback = endpoint;
                }
            }
            if (fallback != null) return fallback;
        }
        return null;
    }


    private String asset(String path) throws IOException {
        try (InputStream in = activity.getAssets().open("localrtc/" + path)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private final class LanSession {
        final LanEndpoint endpoint;
        final Inet4Address address;
        final String cameraName;
        final LocalRtcServer.Session state = new LocalRtcServer.Session();
        LocalRtcServer viewer;
        JmDNS mdns;
        WifiManager.MulticastLock multicast;
        volatile String hostname;
        volatile boolean viewerReady;
        volatile long nextMdnsAttempt = Long.MAX_VALUE;

        LanSession(LanEndpoint endpoint, String cameraName) {
            this.endpoint = endpoint;
            this.address = endpoint.address;
            this.cameraName = cameraName;
        }

        void open() throws IOException {
            String page = asset("index.html"), script = asset("rtc.js");

            viewer = new LocalRtcServer(address.getHostAddress(), PORT, state, page, script);
            viewer.start(5000);
        }

        void announce() {
            try {
                WifiManager wifi = activity.getApplicationContext().getSystemService(WifiManager.class);
                if (wifi != null) {
                    multicast = wifi.createMulticastLock("VillaCam local WebRTC");
                    multicast.acquire();
                }
                mdns = JmDNS.create(Inet4Address.getByAddress(cameraName, address.getAddress()), cameraName);
                mdns.registerService(ServiceInfo.create("_http._tcp.local.", cameraName, PORT, "VillaCam local WebRTC"));
                hostname = mdns.getHostName();
                nextMdnsAttempt = Long.MAX_VALUE;
            } catch (IOException | RuntimeException error) {
                android.util.Log.w("VillaCam", "mDNS unavailable; using LAN IP", error);
                closeMdns();
                nextMdnsAttempt = android.os.SystemClock.elapsedRealtime() + 5000;
            }
        }

        void close() {
            if (viewer != null) viewer.stop();
            closeMdns();
        }

        private void closeMdns() {
            try { if (mdns != null) mdns.close(); }
            catch (IOException error) { android.util.Log.w("VillaCam", "Closing mDNS", error); }
            finally {
                if (multicast != null && multicast.isHeld()) multicast.release();
                mdns = null;
                multicast = null;
            }
        }
    }
}
