package es.colegio.camara;

public class PcmLevelTest {
    private static void near(double actual, double expected) {
        if (Math.abs(actual - expected) > 0.01) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        near(PcmLevel.peakDb(new byte[4096]), -60);
        near(PcmLevel.peakDb(new byte[]{0, -128}), 0); // Negative full scale must not overflow.
        near(PcmLevel.peakDb(new byte[]{-1, 127}), 0);
        near(PcmLevel.peakDb(new byte[]{0, 64}), -6.0206);
        near(PcmLevel.peakDb(new byte[]{0, 0, 0, -64}), -6.0206); // Second stereo channel.
        byte[] frame = {1, 0, 0, 32, 100};
        byte[] original = frame.clone();
        near(PcmLevel.peakDb(frame), -12.0412);
        if (!java.util.Arrays.equals(frame, original)) throw new AssertionError("PCM changed");
        near(PcmLevel.peakDb(new byte[0]), -60);
        System.out.println("PcmLevel: all scenarios passed");
    }
}
