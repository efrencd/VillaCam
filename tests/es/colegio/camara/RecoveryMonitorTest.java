package es.colegio.camara;

/** Run with javac/java; fake monotonic time avoids sleeping or Android dependencies. */
public class RecoveryMonitorTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        RecoveryMonitor monitor = new RecoveryMonitor();
        monitor.start(0, 100);
        check(!monitor.timedOut(14999, 100));
        check(monitor.timedOut(15000, 100));
        check(monitor.failed() == 1000);
        check(monitor.failed() == -1); // Duplicate callbacks cannot queue duplicate retries.
        for (int attempt = 0; attempt < 1000; attempt++) {
            monitor.beginAttempt(20000, 100);
            check(monitor.failed() == 1000); // Fixed delay, even after prolonged failures.
            check(monitor.failed() == -1); // Each attempt queues at most one retry.
        }
        monitor.beginAttempt(30000, 100);
        monitor.connected(31000, 101);
        check(monitor.isLive());
        check(!monitor.timedOut(40000, 130));
        check(!monitor.timedOut(51999, 130));
        check(monitor.timedOut(52000, 130)); // UDP sends alone cannot hide a dead receiver.
        check(monitor.failed() == 1000); // Same delay after a brief success.
        monitor.beginAttempt(60000, 130);
        monitor.connected(61000, 131);
        check(!monitor.timedOut(92000, 300));
        check(monitor.failed() == 1000); // Same delay after a stable connection.
        monitor.stop();
        check(!monitor.timedOut(200000, 300));
        check(monitor.failed() == -1);
        monitor.connected(200000, 301); // Late success after STOP is ignored.
        check(!monitor.isLive());
        monitor.start(0, -1);
        monitor.connected(1, -1);
        check(!monitor.timedOut(50000, -1)); // Unsupported platform counters.
        monitor.stop();
        monitor.start(0, 5);
        monitor.connected(1, 6);
        check(!monitor.timedOut(11000, 0)); // Counter reset is treated as activity.
        check(monitor.timedOut(23000, 0));
        System.out.println("RecoveryMonitor: all scenarios passed");
    }
}
