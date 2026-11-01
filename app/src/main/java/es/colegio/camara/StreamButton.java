package es.colegio.camara;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Preview-only shutter control; stop remains available throughout connection retries. */
final class StreamButton extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean streaming;

    StreamButton(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setStreaming(false);
    }
    void setStreaming(boolean value) {
        streaming = value;
        setContentDescription(getContext().getString(value ? R.string.stop_stream : R.string.start_stream));
        invalidate();
    }
    @Override public CharSequence getAccessibilityClassName() { return android.widget.Button.class.getName(); }
    @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) * 0.44f;
        canvas.save();
        if (isPressed()) canvas.scale(0.94f, 0.94f, cx, cy);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(radius * 0.18f);
        paint.setColor(0xB3000000);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStrokeWidth(radius * 0.10f);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        if (streaming) {
            paint.setColor(0xFFEF4444);
            float half = radius * 0.45f;
            canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, radius * 0.07f, radius * 0.07f, paint);
        } else {
            paint.setColor(Color.WHITE);
            canvas.drawCircle(cx, cy, radius * 0.77f, paint);
        }
        canvas.restore();
    }
}
