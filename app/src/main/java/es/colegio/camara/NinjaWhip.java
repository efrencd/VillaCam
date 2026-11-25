package es.colegio.camara;

import android.os.Handler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.webrtc.PeerConnection;

/** WHIP signaling to the documented VDO.Ninja ingest endpoint. */
final class NinjaWhip {
    interface Reply<T> { void accept(T value); }
    private static final Pattern LINK = Pattern.compile("<([^>]+)>\\s*((?:;[^<]*)?)");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .callTimeout(18, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
    private final Handler main;
    private final HttpUrl endpoint;
    private Call pending;
    private HttpUrl resource;

    NinjaWhip(Handler main, String name) {
        this.main = main;
        endpoint = HttpUrl.get("https://whip.vdo.ninja/").newBuilder().addPathSegment(name).build();
    }

    void discover(Reply<List<PeerConnection.IceServer>> callback) {
        request(new Request.Builder().url(endpoint).method("OPTIONS", null).build(), response -> {
            if (!response.isSuccessful()) return null;
            ArrayList<PeerConnection.IceServer> servers = new ArrayList<>();
            for (String header : response.headers("Link")) {
                Matcher links = LINK.matcher(header);
                while (links.find()) {
                    String uri = links.group(1), attrs = links.group(2);
                    if (!"ice-server".equals(attribute(attrs, "rel"))) continue;
                    if (!uri.startsWith("stun:") && !uri.startsWith("stuns:") &&
                        !uri.startsWith("turn:") && !uri.startsWith("turns:")) continue;
                    servers.add(PeerConnection.IceServer.builder(uri)
                        .setUsername(attribute(attrs, "username"))
                        .setPassword(attribute(attrs, "credential")).createIceServer());
                }
            }
            return servers;
        }, callback);
    }

    private static String attribute(String attrs, String key) {
        Matcher value = Pattern.compile("(?:^|;)\\s*" + key + "\\s*=\\s*\"([^\"]*)\"").matcher(attrs);
        return value.find() ? value.group(1) : "";
    }

    void offer(String sdp, Reply<String> callback) {
        request(new Request.Builder().url(endpoint)
            .post(RequestBody.create(sdp, MediaType.get("application/sdp"))).build(), response -> {
                if (!response.isSuccessful() || response.body() == null) return null;
                String location = response.header("Location");
                HttpUrl created = location == null ? null : endpoint.resolve(location);
                if (created != null && (!"https".equals(created.scheme()) || !endpoint.host().equals(created.host())
                    || endpoint.port() != created.port())) created = null;
                String answer = response.body().string();
                HttpUrl session = created;
                main.post(() -> {
                    if (pending == null) delete(session);
                    else resource = session;
                });
                return answer.startsWith("v=0") ? answer : null;
            }, callback);
    }

    private interface Read<T> { T read(Response response) throws IOException; }

    private <T> void request(Request request, Read<T> read, Reply<T> callback) {
        pending = HTTP.newCall(request);
        pending.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) { deliver(call, null); }
            @Override public void onResponse(Call call, Response response) {
                try (Response closed = response) { deliver(call, read.read(closed)); }
                catch (IOException | RuntimeException error) { deliver(call, null); }
            }
            private void deliver(Call call, T result) {
                main.post(() -> {
                    if (pending != call) return;
                    pending = null;
                    callback.accept(result);
                });
            }
        });
    }

    private static void delete(HttpUrl url) {
        if (url == null) return;
        HTTP.newCall(new Request.Builder().url(url).delete().build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {}
            @Override public void onResponse(Call call, Response response) { response.close(); }
        });
    }

    void stop() {
        if (pending != null) { pending.cancel(); pending = null; }
        delete(resource);
        resource = null;
    }
}
