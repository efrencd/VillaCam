package es.colegio.camara;

/** Monotonic-time recovery policy, independent of Android and the transport callbacks. */
final class RecoveryMonitor {
    private enum State { STOPPED, CONNECTING, LIVE, WAITING }
    private State state = State.STOPPED;
    private long startedAt, lastReceiveAt, lastPackets;
    static final long RETRY_DELAY_MS = 1000;
    static final long CONNECT_TIMEOUT_MS = 15000;
    static final long RECEIVE_TIMEOUT_MS = 12000;

    void start(long now, long packets) {
        beginAttempt(now, packets);
    }
    void beginAttempt(long now, long packets) {
        state = State.CONNECTING;
        startedAt = lastReceiveAt = now;
        lastPackets = packets;
    }
    void connected(long now, long packets) {
        if (state != State.CONNECTING) return;
        state = State.LIVE;
        lastReceiveAt = now;
        lastPackets = packets;
    }
    boolean isLive() { return state == State.LIVE; }
    boolean timedOut(long now, long packets) {
        if (state == State.CONNECTING) return now - startedAt >= CONNECT_TIMEOUT_MS;
        if (state != State.LIVE) return false;
        // UNSUPPORTED (-1) must not cause a healthy stream to be restarted forever.
        if (packets < 0) return false;
        if (packets != lastPackets) {
            lastPackets = packets;
            lastReceiveAt = now;
        }
        if (now - lastReceiveAt >= RECEIVE_TIMEOUT_MS) return true;
        return false;
    }
    long failed() {
        if (state == State.STOPPED || state == State.WAITING) return -1;
        state = State.WAITING;
        return RETRY_DELAY_MS;
    }
    void stop() { state = State.STOPPED; }
}
