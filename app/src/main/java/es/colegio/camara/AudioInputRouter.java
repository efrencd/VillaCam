package es.colegio.camara;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Handler;
import android.os.Looper;
import com.pedro.encoder.input.audio.MicrophoneManager;
import com.pedro.library.base.Camera2Base;
import java.lang.reflect.Field;

/** All methods run on the main thread. Never stops/recreates the live audio recorder. */
final class AudioInputRouter {
    interface Listener { void onStatus(String status); }
    private final AudioManager manager;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private AudioRecord recorder;
    private int mode, appliedId = Integer.MIN_VALUE;
    private boolean running, bridgeFailed, requestRejected;
    private final Runnable poll = new Runnable() {
        public void run() {
            if (!running) return;
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    AudioInputRouter(Context context, Listener listener) {
        this.context = context;
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener;
    }
    void resume() { if (!running) { running = true; handler.post(poll); } }
    void pause() { running = false; handler.removeCallbacksAndMessages(null); detach(); }
    void select(int selected) { mode = selected; appliedId = Integer.MIN_VALUE; refresh(); }
    void detach() {
        recorder = null;
        appliedId = Integer.MIN_VALUE;
        requestRejected = bridgeFailed = false;
        refresh();
    }
    void attach(Camera2Base camera) {
        recorder = null;
        appliedId = Integer.MIN_VALUE;
        bridgeFailed = requestRejected = false;
        try {
            // RootEncoder 2.8.1 exposes neither the AudioRecord nor device routing.
            // Read only these pinned library fields, then use public Android APIs.
            // No Android hidden API, replacement of library internals, or second recorder.
            Field microphone = Camera2Base.class.getDeclaredField("microphoneManager");
            microphone.setAccessible(true);
            Field record = MicrophoneManager.class.getDeclaredField("audioRecord");
            record.setAccessible(true);
            recorder = (AudioRecord) record.get(microphone.get(camera));
            bridgeFailed = recorder == null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            bridgeFailed = true;
            android.util.Log.w("CamaraEscolar", "No se pudo acceder al selector de audio", error);
        }
        refresh();
    }
    private static boolean usb(AudioDeviceInfo device) {
        return device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE
            || device.getType() == AudioDeviceInfo.TYPE_USB_ACCESSORY
            || device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET;
    }
    private String name(AudioDeviceInfo device) {
        String category = usb(device) ? context.getString(R.string.audio_usb) : device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC ? context.getString(R.string.audio_internal) : context.getString(R.string.audio_other);
        return context.getString(R.string.audio_device_name, category, device.getProductName());
    }
    private void refresh() {
        try {
            AudioDeviceInfo[] inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            int internal = -1, external = -1;
            for (int index = 0; index < inputs.length; index++) {
                if (internal < 0 && inputs[index].getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) internal = index;
                if (external < 0 && usb(inputs[index])) external = index;
            }
            int index = AudioInputPolicy.preferredIndex(mode, internal, external);
            AudioDeviceInfo preferred = index >= 0 ? inputs[index] : null;
            String notice = mode == AudioInputPolicy.USB && external < 0
                ? context.getString(R.string.audio_usb_missing) : mode == AudioInputPolicy.INTERNAL && internal < 0
                ? context.getString(R.string.audio_internal_missing) : "";
            if (bridgeFailed) { listener.onStatus(context.getString(R.string.audio_selector_unavailable)); return; }
            if (recorder == null) {
                listener.onStatus(notice + context.getString(R.string.audio_expected,
                    preferred == null ? context.getString(R.string.audio_automatic) : name(preferred)));
                return;
            }
            int id = preferred == null ? -1 : preferred.getId();
            if (id != appliedId) {
                requestRejected = !recorder.setPreferredDevice(preferred);
                appliedId = id;
            }
            AudioDeviceInfo actual = recorder.getRoutedDevice();
            if (requestRejected) notice += context.getString(R.string.audio_change_rejected);
            if (actual == null) {
                listener.onStatus(notice + context.getString(R.string.audio_waiting));
            } else {
                if (preferred != null && actual.getId() != preferred.getId() && !requestRejected)
                    notice += context.getString(R.string.audio_pending);
                listener.onStatus(notice + context.getString(R.string.audio_active, name(actual)));
            }
        } catch (RuntimeException error) {
            listener.onStatus(context.getString(R.string.audio_route_error));
            android.util.Log.w("CamaraEscolar", "Error consultando la ruta de audio", error);
        }
    }
}
