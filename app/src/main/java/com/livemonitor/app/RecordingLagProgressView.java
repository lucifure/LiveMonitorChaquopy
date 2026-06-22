package com.livemonitor.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class RecordingLagProgressView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progressFraction = 0f;

    public RecordingLagProgressView(Context context) { this(context, null); }

    public RecordingLagProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        trackPaint.setColor(Color.argb(70, 255, 255, 255));
        fillPaint.setColor(Color.rgb(35, 225, 201));
    }

    public void setProgressFraction(float fraction) {
        progressFraction = Math.max(0f, Math.min(1f, fraction));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = Math.round(getResources().getDisplayMetrics().density * 8f);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = getHeight() / 2f;
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, trackPaint);
        float fillWidth = getWidth() * progressFraction;
        if (fillWidth > 0f) {
            canvas.drawRoundRect(0, 0, fillWidth, getHeight(), radius, radius, fillPaint);
        }
    }
}
