package com.brotherboard.deci;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.widget.RemoteViews;

public class BatteryWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateWidget(context, appWidgetManager, id);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        updateWidget(context, appWidgetManager, appWidgetId);
    }

    public static void updateWidget(Context context, AppWidgetManager appWidgetManager, int widgetId) {
        Bundle options = appWidgetManager.getAppWidgetOptions(widgetId);
        int width  = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH);
        int height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        if (width  <= 0) width  = 200;
        if (height <= 0) height = 100;

        float density = context.getResources().getDisplayMetrics().density;
        int w = (int) (width  * density);
        int h = (int) (height * density);

        Bitmap bitmap = drawWidget(context, w, h);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
        views.setImageViewBitmap(R.id.widget_image, bitmap);
        appWidgetManager.updateAppWidget(widgetId, views);
    }

    private static Bitmap drawWidget(Context context, int w, int h) {
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);

        float density = context.getResources().getDisplayMetrics().density;
        float pad = 14 * density;
        float innerW = w - pad * 2;

        // ── grab live data from service ──────────────────────────────────
        String pct      = BatteryService.getPercentString();
        int    progress = BatteryService.progressNow;
        int    progMax  = BatteryService.progressMax;
        int    current  = BatteryService.cachedCurrentNow;
        float  voltage  = BatteryService.cachedVoltageMv / 1000f;
        float  temp     = BatteryService.cachedTempC;
        double mah      = BatteryService.displayedMah;
        boolean charging = BatteryService.isCharging;

        float watts = voltage * Math.abs(current) / 1000f;

        // ── paint setup ──────────────────────────────────────────────────
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // ── label color ──────────────────────────────────────────────────
        int labelColor = Color.argb(255, 68, 68, 68);   // #444444
        int valueColor = Color.WHITE;
        int dimColor   = Color.argb(255, 34, 34, 34);   // #222222

        // ── layout zones ────────────────────────────────────────────────
        // top zone: big percentage (50% height)
        // progress bar: 2dp
        // bottom zone: stats row (remaining height)

        float barH        = 2 * density;
        float statsH      = 11 * density + 4 * density + 16 * density; // label + gap + value
        float bigPctH     = h - pad - barH - 8 * density - statsH - pad;

        // ── BIG PERCENTAGE ───────────────────────────────────────────────
        paint.setColor(valueColor);
        paint.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        // fit text to available height
        float targetH = bigPctH * 0.85f;
        paint.setTextSize(targetH);
        // scale down if too wide
        float textW = paint.measureText(pct + "%");
        if (textW > innerW) {
            paint.setTextSize(targetH * (innerW / textW));
        }
        float pctY = pad + bigPctH * 0.5f + (paint.descent() - paint.ascent()) * 0.5f - paint.descent();
        canvas.drawText(pct + "%", pad, pctY, paint);

        // ── PROGRESS BAR ─────────────────────────────────────────────────
        float barTop = pad + bigPctH + 6 * density;
        float barBot = barTop + barH;

        // background
        paint.setColor(dimColor);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(pad, barTop, pad + innerW, barBot, paint);

        // fill
        float fillFrac = (progMax > 0) ? (float) progress / progMax : 0f;
        if (fillFrac > 0f) {
            paint.setColor(valueColor);
            canvas.drawRect(pad, barTop, pad + innerW * fillFrac, barBot, paint);
        }

        // ── STATS ROW ────────────────────────────────────────────────────
        float statsTop = barBot + 8 * density;

        // 4 columns: CURRENT | POWER | REMAINING | TEMP
        String[] labels = { "CURRENT", "POWER", "REMAINING", "TEMP" };
        String[] values = {
            (current >= 0 ? "+" : "") + current + " mA",
            String.format("%.2f W", watts),
            mah > 0 ? String.format("%.0f mAh", mah) : "-- mAh",
            String.format("%.1f°C", temp)
        };

        float colW = innerW / labels.length;

        // label style
        paint.setColor(labelColor);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        float labelSize = 9 * density;
        paint.setTextSize(labelSize);
        float labelY = statsTop + labelSize;

        // value style
        Paint valPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        valPaint.setColor(valueColor);
        valPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        float valSize = 13 * density;
        valPaint.setTextSize(valSize);
        float valY = labelY + 4 * density + valSize;

        for (int i = 0; i < labels.length; i++) {
            float x = pad + colW * i;

            // clip label to column width
            paint.setTextSize(labelSize);
            String lbl = labels[i];
            float lblW = paint.measureText(lbl);
            if (lblW > colW - 4 * density) {
                paint.setTextSize(labelSize * ((colW - 4 * density) / lblW));
            }
            canvas.drawText(lbl, x, labelY, paint);
            paint.setTextSize(labelSize); // reset

            // clip value to column width
            String val = values[i];
            float vw = valPaint.measureText(val);
            if (vw > colW - 4 * density) {
                valPaint.setTextSize(valSize * ((colW - 4 * density) / vw));
            }
            canvas.drawText(val, x, valY, valPaint);
            valPaint.setTextSize(valSize); // reset
        }

        // ── charging indicator: tiny dot next to percentage ──────────────
        if (charging) {
            paint.setColor(Color.argb(255, 180, 255, 180));
            paint.setStyle(Paint.Style.FILL);
            float dotR = 3 * density;
            // place dot top-right corner
            canvas.drawCircle(w - pad - dotR, pad + dotR * 2, dotR, paint);
        }

        return bitmap;
    }
}

