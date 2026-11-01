package es.colegio.camara;

/** Signed little-endian PCM16 peak, across all channels. Never changes capture data. */
final class PcmLevel {
    static double peakDb(byte[] pcm) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak == 0 ? -60 : Math.max(-60, 20 * Math.log10(peak / 32768.0));
    }
}
