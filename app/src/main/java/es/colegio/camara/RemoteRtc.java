package es.colegio.camara;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.PeerConnection;

/** Public signaling only. The peer connection owns all media traffic. */
final class RemoteRtc {
    interface Listener {
        void link(String url);
        void waiting();
        void failed();
        void nameInUse();
        void offer(String id, String sdp, List<PeerConnection.IceServer> servers);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS).pingInterval(4, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build();
    private final ConnectivityManager networks;
    private final Listener listener;
    private WebSocket socket;
    private Call pending;
    private String base, room, publisher, name, cameraId;
    private String incomingOffer;
    private boolean running;
    private Network activeNetwork;

    RemoteRtc(Context context, Listener listener) {
        networks = context.getSystemService(ConnectivityManager.class);
        this.listener = listener;
    }

    static String httpsOrigin(String raw) {
        try {
            URI uri = new URI(raw.trim());
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null &&
                uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null &&
                (uri.getPath().isEmpty() || "/".equals(uri.getPath())) && uri.getPort() <= 65535 && uri.getPort() != 0)
                return "https://" + uri.getRawAuthority();
        } catch (Exception ignored) {}
        return null;
    }

    void start(String base, String name, String cameraId) {
        this.base = base;
        this.name = name;
        this.cameraId = cameraId;
        running = true;
        register();
        main.postDelayed(watchNetwork, 700);
    }

    private void register() {
        if (!running || pending != null) return;
        Request request = new Request.Builder().url(base + "/api/session")
            .header("X-VillaCam-Camera", name)
            .header("X-VillaCam-Mode", "name")
            .header("X-VillaCam-Device", cameraId)
            .post(RequestBody.create("{}", MediaType.get("application/json"))).build();
        requestJson(request, (code, result) -> {
            if (code == 401 || code == 403 || code == 400 || code == 404 || code == 503) { listener.failed(); return; }
            if (result == null) { main.postDelayed(this::register, 1500); return; }
            room = result.optString("room");
            publisher = result.optString("publisher");
            String viewer = result.optString("viewer");
            if (!room.matches("[a-f0-9]{64}") || !publisher.matches("publisher\\.[0-9]{10}\\.[a-f0-9]{64}\\.[a-f0-9]{64}") ||
                !viewer.matches("viewer\\.[0-9]{10}\\.[a-f0-9]{64}")) { listener.failed(); return; }
            activeNetwork = networks.getActiveNetwork();
            listener.link(base + "/?view=" + name);
            connect();
        });
    }

    private void connect() {
        if (!running || room == null || socket != null) return;
        Request request = new Request.Builder().url(base + "/api/socket?room=" + room)
            .header("Sec-WebSocket-Protocol", "villacam, " + publisher).build();
        socket = http.newWebSocket(request, new WebSocketListener() {
            @Override public void onMessage(WebSocket connection, String message) {
                main.post(() -> {
                    if (socket != connection) return;
                    try {
                        JSONObject json = new JSONObject(message);
                        switch (json.optString("type")) {
                            case "ready":
                            case "offline":
                                incomingOffer = null;
                                if (pending != null) { pending.cancel(); pending = null; }
                                listener.waiting();
                                break;
                            case "offer": fetchIce(connection, json.getString("id"), json.getString("sdp")); break;
                            default: break;
                        }
                    } catch (Exception error) {
                        android.util.Log.w("VillaCam", "Invalid signaling message");
                    }
                });
            }
            @Override public void onClosing(WebSocket connection, int code, String reason) {
                connection.close(1000, null);
                disconnected(connection, code == 4003);
            }
            @Override public void onClosed(WebSocket connection, int code, String reason) {
                disconnected(connection, code == 4003);
            }
            @Override public void onFailure(WebSocket connection, Throwable error, Response response) {
                int status = response == null ? 0 : response.code();
                if (status == 409) main.post(() -> { if (socket == connection) listener.nameInUse(); });
                else disconnected(connection, status == 401 || status == 403);
            }
        });
    }

    private void fetchIce(WebSocket connection, String id, String sdp) {
        if (pending != null) { pending.cancel(); pending = null; }
        incomingOffer = id;
        Request request = new Request.Builder().url(base + "/api/ice?room=" + room)
            .header("Authorization", "Bearer " + publisher).build();
        requestJson(request, (code, result) -> {
            if (socket != connection || !id.equals(incomingOffer)) return;
            if (code == 401 || code == 403) { listener.failed(); return; }
            if (result == null) { reconnect(); return; }
            try {
                ArrayList<PeerConnection.IceServer> servers = new ArrayList<>();
                JSONArray list = result.getJSONArray("iceServers");
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    JSONArray urls = item.getJSONArray("urls");
                    ArrayList<String> addresses = new ArrayList<>();
                    for (int j = 0; j < urls.length(); j++) addresses.add(urls.getString(j));
                    if (!addresses.isEmpty()) servers.add(PeerConnection.IceServer.builder(addresses)
                        .setUsername(item.optString("username")).setPassword(item.optString("credential")).createIceServer());
                }
                listener.offer(id, sdp, servers);
            } catch (Exception error) { reconnect(); }
        });
    }

    void answer(String id, String sdp) {
        if (socket == null || !id.equals(incomingOffer)) return;
        try {
            if (!socket.send(new JSONObject().put("type","answer").put("id",id).put("sdp",sdp).toString()))
                reconnect();
        } catch (Exception error) { reconnect(); }
    }

    private interface JsonResult { void accept(int status, JSONObject result); }

    private void requestJson(Request request, JsonResult callback) {
        pending = http.newCall(request);
        pending.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) { deliver(call, 0, null); }
            @Override public void onResponse(Call call, Response response) {
                try (Response closed = response) {
                    if (!closed.isSuccessful()) { deliver(call, closed.code(), null); return; }
                    deliver(call, closed.code(), new JSONObject(closed.body().string()));
                } catch (Exception error) { deliver(call, 0, null); }
            }
            private void deliver(Call call, int code, JSONObject result) {
                main.post(() -> {
                    if (!running || pending != call) return;
                    pending = null;
                    callback.accept(code, result);
                });
            }
        });
    }

    private void disconnected(WebSocket connection, boolean fatal) {
        main.post(() -> {
            if (socket != connection) return;
            resetSocket();
            if (fatal) listener.failed();
            else {
                listener.waiting();
                main.postDelayed(this::connect, 1000);
            }
        });
    }

    private void resetSocket() {
        WebSocket old = socket;
        socket = null;
        incomingOffer = null;
        if (old != null) old.cancel();
        if (pending != null) { pending.cancel(); pending = null; }
    }

    void reconnect() {
        if (!running || room == null) return;
        resetSocket();
        listener.waiting();
        connect();
    }

    private final Runnable watchNetwork = new Runnable() {
        @Override public void run() {
            if (!running) return;
            Network current = networks.getActiveNetwork();
            if (!Objects.equals(current, activeNetwork) && room != null) {
                activeNetwork = current;
                reconnect();
            }
            main.postDelayed(this, 700);
        }
    };

    void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
        resetSocket();
        room = publisher = null;
    }
}
