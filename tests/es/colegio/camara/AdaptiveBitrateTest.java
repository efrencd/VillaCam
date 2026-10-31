package es.colegio.camara;

public class AdaptiveBitrateTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        AdaptiveBitrate abr = new AdaptiveBitrate(700000, 2500000);
        abr.connected(0, 0, 0, 100);
        check(abr.sample(2000, true, 0, 0, 10000, 120) == 2500000); // cooldown
        check(abr.sample(4000, true, 0, 0, 10000, 140) == 1875000);
        check(abr.sample(5000, true, 0, 0, 10000, 150) == 1875000); // sample rate
        check(abr.sample(6000, false, 0, 0, 10000, 160) == 1875000);
        check(abr.sample(8000, false, 0, 0, 10000, 180) == 2500000); // recover after 4 clean seconds
        for (int time = 16000; time <= 100000; time += 2000) abr.sample(time, false, 0, 0, 10000, time);
        check(abr.target() == 2500000); // upper bound
        abr.connected(0, 0, 0, 0);
        check(abr.sample(2000, false, 0, 5, 10000, 10) == 2500000); // isolated burst
        check(abr.sample(4000, false, 0, 10, 10000, 20) == 1875000); // repeated NAKs
        check(abr.sample(8000, false, 1, 10, 10000, 30) == 1406250); // dropped frames
        abr.connected(0, 0, 0, 0);
        abr.sample(2000, false, 0, 0, 10000, 10);
        abr.sample(4000, false, 0, 0, 300000, 20);
        check(abr.sample(6000, false, 0, 0, 300000, 30) == 1054687); // sustained RTT inflation
        for (int i = 0; i < 20; i++) abr.connectionFailed();
        check(abr.target() == 700000); // restart retains reduced target, bounded floor
        abr.connected(0, 5, 10, 10);
        for (int time = 2000; time <= 20000; time += 2000) abr.sample(time, false, 0, 0, 10000, 10);
        check(abr.target() == 700000); // no receive evidence means no recovery
        abr.connected(0, 0, 0, -1);
        for (int time = 2000; time <= 20000; time += 2000) abr.sample(time, false, 0, 0, 10000, -1);
        check(abr.target() == 700000); // unsupported counters cannot justify an increase
        abr.restoreTarget(99999999);
        check(abr.target() == 2500000);
        AdaptiveBitrate fullHd = new AdaptiveBitrate(1500000, 5000000);
        for (int i = 0; i < 30; i++) fullHd.connectionFailed();
        check(fullHd.target() == 1500000);
        fullHd.connected(0, 0, 0, 0);
        check(fullHd.sample(2000, false, 0, 0, 10000, 10) == 1500000);
        check(fullHd.sample(4000, false, 0, 0, 10000, 20) == 2750000);
        check(fullHd.sample(6000, false, 0, 0, 10000, 30) == 4000000);
        check(fullHd.sample(8000, false, 0, 0, 10000, 40) == 5000000); // floor to max in 8 seconds
        abr.restoreTarget(700000);
        abr.connected(0, 0, 0, 0);
        abr.sample(2000, false, 0, 0, 10000, 10);
        check(abr.sample(4000, false, 0, 0, 10000, 20) == 1325000);
        check(abr.sample(6000, false, 0, 0, 10000, 30) == 1950000);
        check(abr.sample(8000, false, 0, 0, 10000, 40) == 2500000);
        abr.restoreTarget(700000);
        abr.connected(0, 0, 0, 0);
        abr.sample(2000, false, 0, 1, 10000, 10);
        check(abr.sample(4000, false, 0, 1, 10000, 20) == 700000); // fresh loss delays recovery
        check(abr.sample(6000, false, 0, 1, 10000, 30) == 1325000);
        check(abr.sample(8000, true, 1, 1, 10000, 40) == 1325000); // respect 4-second decrease cooldown
        check(abr.sample(10000, true, 2, 1, 10000, 50) == 993750); // pressure still reduces after recovery
        System.out.println("AdaptiveBitrate: all scenarios passed");
    }
}
