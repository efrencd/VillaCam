package es.colegio.camara;

import fi.iki.elonen.NanoHTTPD;
import java.util.HashMap;
import java.util.UUID;
import org.json.JSONObject;

/** LAN signaling for one browser viewer. Native capture owns the answer. */
final class LocalRtcServer extends NanoHTTPD {
    static final class Session {
        String id, offer, answer;
        long lastSeen;
        void expire() {
            if (android.os.SystemClock.elapsedRealtime() - lastSeen > 6000) clear();
        }
        void clear() { id = offer = answer = null; }
    }
    private final Session state;
    private final String page, script;

    LocalRtcServer(String address, int port, Session state, String page, String script) {
        super(address, port);
        this.state = state;
        this.page = page;
        this.script = script;
    }

    private Response reply(Response.Status code, String type, String body) {
        Response response = newFixedLengthResponse(code, type, body);
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("X-Content-Type-Options", "nosniff");
        return response;
    }

    // The LAN viewer is open during the trial. Future access checks belong here,
    // covering both page access and the signaling routes.
    private boolean allowViewer(IHTTPSession request) { return true; }

    @Override public Response serve(IHTTPSession request) {
        String path = request.getUri();
        boolean get = request.getMethod() == Method.GET;
        boolean post = request.getMethod() == Method.POST;
        try {
            if (!allowViewer(request)) return error(Response.Status.FORBIDDEN);
            if (get && "/".equals(path)) return reply(Response.Status.OK, "text/html; charset=utf-8", page);
            if (get && "/rtc.js".equals(path)) return reply(Response.Status.OK, "application/javascript; charset=utf-8", script);
            JSONObject body = null;
            if (post && "/offer".equals(path)) {
                String host = request.getHeaders().get("host");
                if (host == null || !("http://" + host).equals(request.getHeaders().get("origin")))
                    return error(Response.Status.FORBIDDEN);
                String length = request.getHeaders().get("content-length");
                int bytes = length == null ? 0 : Integer.parseInt(length);
                if (bytes < 1 || bytes > 32768) return error(Response.Status.BAD_REQUEST);
                HashMap<String, String> fields = new HashMap<>();
                request.parseBody(fields);
                body = new JSONObject(fields.get("postData"));
            }
            synchronized (state) {
                long now = android.os.SystemClock.elapsedRealtime();
                state.expire();
                JSONObject result = new JSONObject();
                if (body != null && "/offer".equals(path)) {
                    // The browser can replace its own connection without waiting for the lease.
                    if (state.id != null && !state.id.equals(body.optString("previousId")))
                        return error(Response.Status.CONFLICT);
                    state.offer = body.getString("sdp");
                    state.answer = null;
                    state.id = UUID.randomUUID().toString();
                    state.lastSeen = now;
                    result.put("id", state.id);
                } else if (((get && "/answer".equals(path)) || (post && "/leave".equals(path)))) {
                    if (state.id == null || !state.id.equals(request.getParms().get("id")))
                        return error(Response.Status.NOT_FOUND);
                    if (get) {
                        state.lastSeen = now;
                        result.put("sdp", state.answer);
                    } else state.clear();
                } else return error(Response.Status.NOT_FOUND);
                return reply(Response.Status.OK, "application/json", result.toString());
            }
        } catch (Exception badRequest) {
            return error(Response.Status.BAD_REQUEST);
        }
    }

    private Response error(Response.Status status) { return reply(status, "application/json", "{}"); }
}
