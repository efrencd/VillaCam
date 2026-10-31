package es.colegio.camara;

/** Feedback-based bitrate controller with fast, bounded recovery on a healthy network. */
final class AdaptiveBitrate {
    private final int minimum, maximum;
    private int target, badSamples;
    private long lastSample, lastChange, healthySince, lastDropped, lastLost, lastRx;
    private int baseRtt;
    private boolean initialized;

    AdaptiveBitrate(int minimum, int maximum) {
        if (minimum <= 0 || maximum < minimum) throw new IllegalArgumentException();
        this.minimum = minimum;
        this.maximum = maximum;
        target = maximum;
    }
    int target() { return target; }
    void connected(long now, long dropped, long lost, long rx) {
        lastSample = lastChange = healthySince = now;
        lastDropped = dropped; lastLost = lost; lastRx = rx;
        baseRtt = 0; badSamples = 0; initialized = true;
    }
    int sample(long now, boolean congested, long dropped, long lost, int rttUs, long rx) {
        if (!initialized || now - lastSample < 2000) return target;
        long droppedDelta = dropped >= lastDropped ? dropped - lastDropped : 0;
        long lostDelta = lost >= lastLost ? lost - lastLost : 0;
        boolean received = rx >= 0 && lastRx >= 0 && rx > lastRx;
        lastSample = now; lastDropped = dropped; lastLost = lost; lastRx = rx;
        if (rttUs > 0 && (baseRtt == 0 || rttUs < baseRtt)) baseRtt = rttUs;
        boolean slow = baseRtt > 0 && rttUs > Math.max(200000L, baseRtt * 3L);
        boolean bad = lostDelta >= 5 || slow;
        badSamples = bad ? badSamples + 1 : 0;
        boolean pressure = congested || droppedDelta > 0 || badSamples >= 2;
        if (pressure || lostDelta > 0 || slow || !received) healthySince = now;
        if (pressure && now - lastChange >= 4000) {
            target = Math.max(minimum, target * 3 / 4);
            lastChange = now;
            badSamples = 0;
        } else if (!pressure && now - healthySince >= 4000 && now - lastChange >= 2000) {
            target = Math.min(maximum, target + maximum / 4);
            lastChange = now;
        }
        return target;
    }
    void connectionFailed() {
        target = Math.max(minimum, target * 3 / 4);
        initialized = false;
    }
    void restoreTarget(int previous) { target = Math.max(minimum, Math.min(maximum, previous)); }
}
