package com.livemonitor.app;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/** Circular free-space gauge for the Monitoring screen storage health card. */
public class StorageGaugeView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private float freePercent = 0f;

    public StorageGaugeView(Context context) { this(context, null); }

    public StorageGaugeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        int track = ContextCompat.getColor(context, R.color.surface_2);
        int accent = ContextCompat.getColor(context, R.color.accent);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(12));
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(track);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(20));
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setColor(withAlpha(accent, 70));
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(dp(12));
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(accent);
    }

    public void setFreePercent(float percent) {
        freePercent = Math.max(0f, Math.min(100f, percent));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = Math.max(glowPaint.getStrokeWidth(), arcPaint.getStrokeWidth()) / 2f + dp(2);
        arcBounds.set(pad, pad, getWidth() - pad, getHeight() - pad);
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint);
        float sweep = 360f * (freePercent / 100f);
        if (sweep > 0f) {
            canvas.drawArc(arcBounds, -90f, sweep, false, glowPaint);
            canvas.drawArc(arcBounds, -90f, sweep, false, arcPaint);
        }
    }

    private float dp(int value) { return value * Resources.getSystem().getDisplayMetrics().density; }
    private static int withAlpha(int color, int alpha) { return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)); }
}
