package es.colegio.camara;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/** A local-only peak meter. Audio thread publishes levels; UI redraws at 20 Hz. */
final class MicrophoneMeter extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double incoming = -60;
    private long sampledAt;
    private double level = -60, held = -60;
    private long heldAt, drawnAt;
    private boolean muted;

    MicrophoneMeter(Context context) {
        super(context);
        setContentDescription(context.getString(R.string.meter_idle));
    }
    synchronized void sample(byte[] pcm) {
        incoming = Math.max(incoming, PcmLevel.peakDb(pcm));
        sampledAt = SystemClock.elapsedRealtime();
    }
    synchronized void reset() { sampledAt = 0; incoming = level = held = -60; invalidate(); }
    void setMuted(boolean value) { muted = value; reset(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.elapsedRealtime();
        double elapsed = drawnAt == 0 ? 0.05 : Math.min(0.2, (now - drawnAt) / 1000.0);
        drawnAt = now;
        boolean signal;
        double target;
        synchronized (this) {
            signal = !muted && sampledAt > 0 && now - sampledAt < 300;
            target = signal ? incoming : -60;
            incoming = -60; // Preserve the loudest sample between draws, including brief peaks.
        }
        level = Math.max(target, level - elapsed * 40);
        if (level >= held) { held = level; heldAt = now; }
        else if (now - heldAt > 1000) held = Math.max(level, held - elapsed * 20);
        float density = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight(), top = 4 * density, bottom = h - 4 * density;
        for (int i = 0; i < 30; i++) {
            double db = -60 + (i + 1) * 2;
            float y = bottom - (bottom - top) * (i + 1) / 30;
            paint.setColor(level >= db ? (db >= -6 ? 0xFFEF4444 : db >= -18 ? 0xFFFACC15 : 0xFF4ADE80) : 0x66374151);
            canvas.drawRect(2 * density, y, w - 2 * density, y + (bottom - top) / 30 - 2 * density, paint);
        }
        if (signal && held > -60) {
            paint.setColor(Color.WHITE);
            float y = (float) (bottom - (bottom - top) * (held + 60) / 60);
            canvas.drawRect(density, y, w - density, y + 2 * density, paint);
        }
        if (muted) {
            paint.setColor(0xFFEF4444);
            paint.setStrokeWidth(2 * density);
            canvas.drawLine(2 * density, h / 2 - 5 * density, w - 2 * density, h / 2 + 5 * density, paint);
            canvas.drawLine(w - 2 * density, h / 2 - 5 * density, 2 * density, h / 2 + 5 * density, paint);
        }
        setContentDescription(getContext().getString(muted ? R.string.meter_muted : signal ? R.string.meter_active : R.string.meter_idle));
        if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) postInvalidateDelayed(50);
    }
}
