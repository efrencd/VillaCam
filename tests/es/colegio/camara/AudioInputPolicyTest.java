package es.colegio.camara;

public class AudioInputPolicyTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        check(AudioInputPolicy.preferredIndex(AudioInputPolicy.AUTOMATIC, 0, 1) == -1);
        check(AudioInputPolicy.preferredIndex(AudioInputPolicy.INTERNAL, 0, 1) == 0);
        check(AudioInputPolicy.preferredIndex(AudioInputPolicy.USB, 0, 1) == 1);
        // USB unplugged: fall back to internal; replugged with a new ID/index: select USB again.
        check(AudioInputPolicy.preferredIndex(AudioInputPolicy.USB, 2, -1) == 2);
        check(AudioInputPolicy.preferredIndex(AudioInputPolicy.USB, 2, 4) == 4);
        check(AudioInputPolicy.preferredIndex(AudioInputPolicy.USB, -1, -1) == -1);
        check(AudioInputPolicy.preferredIndex(AudioInputPolicy.INTERNAL, -1, 3) == -1);
        check(AudioInputPolicy.preferredIndex(999, 0, 1) == -1);
        System.out.println("AudioInputPolicy: all scenarios passed");
    }
}
