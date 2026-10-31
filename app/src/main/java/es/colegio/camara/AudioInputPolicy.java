package es.colegio.camara;

/** Device indexes are from the current input-device snapshot, never persisted IDs. */
final class AudioInputPolicy {
    static final int AUTOMATIC = 0, INTERNAL = 1, USB = 2;
    static int preferredIndex(int mode, int internal, int usb) {
        if (mode == INTERNAL) return internal;
        if (mode == USB) return usb >= 0 ? usb : internal;
        return -1; // null AudioRecord preference restores Android's automatic routing.
    }
}
