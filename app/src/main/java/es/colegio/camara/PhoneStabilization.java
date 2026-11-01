package es.colegio.camara;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import com.pedro.library.srt.SrtCamera2;

/** Requests one native mode and reports actual capture metadata, rather than request acceptance. */
final class PhoneStabilization {
    private final TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile int generation;
    private volatile boolean applied;
    private volatile long lastReport;

    PhoneStabilization(TextView status) { this.status = status; }
    void detach() { generation++; applied = false; handler.removeCallbacksAndMessages(null); }
    void attach(SrtCamera2 camera, int mode) {
        detach();
        int session = generation;
        status.setText(R.string.stabilization_wait);
        lastReport = 0;
        camera.setCustomOnCaptureCompletedCallback(result -> {
            if (session != generation || !applied) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastReport < 1000) return;
            lastReport = now;
            Integer eis = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE);
            Integer ois = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE);
            boolean electronic = eis != null && eis != CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
            boolean optical = ois != null && ois == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON;
            boolean confirmed = mode == 1 ? electronic && !optical : mode == 2 ? optical && !electronic
                : eis != null && eis == 0 && (ois == null || ois == 0);
            int text = confirmed ? (mode == 1 ? R.string.stabilization_confirmed_eis
                : mode == 2 ? R.string.stabilization_confirmed_ois : R.string.stabilization_confirmed_off)
                : R.string.stabilization_unconfirmed;
            handler.post(() -> { if (session == generation) status.setText(text); });
        });
        apply(camera, mode, session, 0);
    }
    private static boolean supports(int[] modes, int target) {
        if (modes != null) for (int mode : modes) if (mode == target) return true;
        return false;
    }
    private void apply(SrtCamera2 camera, int mode, int session, int attempt) {
        if (session != generation) return;
        try {
            CameraCharacteristics info = camera.getCameraCharacteristics();
            if (info != null) {
                int[] eis = info.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
                int[] ois = info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
                boolean supported = mode == 0 || supports(mode == 1 ? eis : ois, 1);
                // Explicitly turn the other mode off, including on cameras with a default OIS mode.
                boolean accepted = camera.setCustomRequest(builder -> {
                    if (eis != null) builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        supported && mode == 1 ? 1 : 0);
                    if (ois != null) builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        supported && mode == 2 ? 1 : 0);
                });
                if (accepted) {
                    if (!supported) { status.setText(R.string.stabilization_unsupported); return; }
                    applied = true;
                    status.setText(R.string.stabilization_unconfirmed);
                    return;
                }
            }
        } catch (RuntimeException error) {
            android.util.Log.w("VillaCam", "Native stabilization request failed", error);
        }
        // Camera2 opens asynchronously; a missing capture builder is not evidence of unsupported EIS.
        if (attempt < 30) handler.postDelayed(() -> apply(camera, mode, session, attempt + 1), 100);
        else status.setText(R.string.stabilization_unavailable);
    }
}
