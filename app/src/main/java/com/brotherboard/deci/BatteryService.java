package com.brotherboard.deci;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;

public class BatteryService extends Service {

    public static float DIVISOR = 20000f;
    public static int progressMax = 1000;
    public static int progressNow = 0;

    public static String topText = "--";
    public static String bottomText = "--";
    public static double displayedMah = -1;

    public static int lastCharge = -1;
    public static boolean isCharging = false;

    // Tracking States
    public static boolean isServiceRunning = false;
    public static boolean isTracking = false;
    public static boolean isIndeterminate = true;

    // Pure mathematical integration & learning variables
    public static double exact_charge = 0.0;
    public static double learned_ratio = -1.0; 
    public static double integral_since_tick = 0.0;
    public static long lastTickTime = 0;

    // UI Throttling
    private long lastUiUpdateTime = 0;

    public static int cachedCurrentNow = 0;
    public static float cachedVoltageMv = 3800f;
    public static float cachedTempC = 25f;

    private Handler handler = new Handler();
    private Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            // HIGH-SPEED POLLING: Run math engine every 150ms
            handler.postDelayed(this, 150); 
        }
    };

    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onBatteryChanged(intent);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if ("EXIT".equals(action)) {
            isTracking = false;
            isServiceRunning = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if ("STOP".equals(action)) {
            isTracking = false;
            topText = "--";
            bottomText = "--";
            updateNotification();
            updateWidget();
            return START_STICKY;
        }

        if ("START".equals(action) || action == null) {
            if (!isServiceRunning) {
                isServiceRunning = true;
                startForeground(1, buildNotification());
                registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                handler.post(ticker);
            }
            isTracking = true;
            isIndeterminate = true;

            lastCharge = -1;
            learned_ratio = -1.0;
            lastTickTime = SystemClock.elapsedRealtime();
            integral_since_tick = 0.0;
            lastUiUpdateTime = 0;

            BatteryManager bm = getSystemService(BatteryManager.class);
            lastCharge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            exact_charge = lastCharge;

            updateNotification();
            updateWidget();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isServiceRunning = false;
        isTracking = false;
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException e) {}
        handler.removeCallbacks(ticker);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void onBatteryChanged(Intent intent) {
        if (!isTracking) return;
        cachedVoltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3800);
        cachedTempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) / 10f;
        tick();
    }

    private void tick() {
        if (!isTracking) {
            throttleUiUpdates();
            return;
        }

        BatteryManager bm = getSystemService(BatteryManager.class);
        cachedCurrentNow = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);

        // 1. Calculate exact elapsed time (dt) for high-precision math
        long now = SystemClock.elapsedRealtime();
        if (lastTickTime == 0) lastTickTime = now;
        double dt = (now - lastTickTime) / 1000.0;
        lastTickTime = now;

        int newCharge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        int status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
        isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING 
            || status == BatteryManager.BATTERY_STATUS_FULL);

        double raw_delta = Math.abs(cachedCurrentNow) * dt;
        double step = DIVISOR / 10.0;

        // 2. Hardware Corrector: Learn the exact ratio when the hardware finally ticks
        if (lastCharge == -1 || newCharge != lastCharge) {
            if (lastCharge != -1) {
                int actual_step = Math.abs(newCharge - lastCharge);
                float possibleDivisor = actual_step * 10f;
                int currentLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

                float absCharge = Math.abs((float)newCharge);
                float calculatedLevel = absCharge / possibleDivisor;
                float distanceToLevel = Math.abs(calculatedLevel - currentLevel);
                float distanceToHalfLevel = Math.abs(calculatedLevel - (currentLevel / 2.0f));

                if (distanceToLevel < 3.0f && distanceToLevel < distanceToHalfLevel) {
                    SharedPreferences prefs = getSharedPreferences("deci_prefs", MODE_PRIVATE);
                    float savedX10 = prefs.getFloat("auto_divisor", -1f);
                    if (savedX10 == -1f || possibleDivisor < savedX10) {
                        prefs.edit().putFloat("auto_divisor", possibleDivisor).apply();
                    }
                }

                if (integral_since_tick > 0.0) {
                    double new_ratio = actual_step / integral_since_tick;
                    if (learned_ratio <= 0.0) {
                        learned_ratio = new_ratio;
                    } else {
                        learned_ratio = (learned_ratio * 0.7) + (new_ratio * 0.3);
                    }
                }
                isIndeterminate = false;
            }
            lastCharge = newCharge;
            exact_charge = newCharge; 
            integral_since_tick = 0.0;
        } else {
            // Live integration
            if (learned_ratio <= 0.0) {
                learned_ratio = step / (Math.max(1.0, Math.abs(cachedCurrentNow)) * 45.0);
            }

            integral_since_tick += raw_delta;
            double delta_charge = raw_delta * learned_ratio;

            if (isCharging) exact_charge += delta_charge;
            else exact_charge -= delta_charge;

            if (exact_charge > lastCharge + step) exact_charge = lastCharge + step;
            if (exact_charge < lastCharge - step) exact_charge = lastCharge - step;
        }

        // 3. Update variables safely
        double exact_pct = exact_charge / DIVISOR;
        int totalHundredths = (int) Math.floor(exact_pct * 100.0);
        int whole = totalHundredths / 100;
        int frac = totalHundredths % 100;
        if (frac < 0) {
            whole -= 1;
            frac += 100;
        }
        topText = String.valueOf(whole);
        bottomText = String.format("%02d", frac);

        // 4. Update Progress Bar completely independent of the anchor
        double exact_tenths = exact_pct * 10.0;
        double fraction = exact_tenths - Math.floor(exact_tenths); 

        if (isCharging) {
            if (exact_charge >= lastCharge + step) fraction = 1.0; 
        } else {
            if (exact_charge <= lastCharge - step) {
                fraction = 0.0; 
            } else if (fraction == 0.0) {
                fraction = 1.0; 
            }
        }

        progressNow = (int) (fraction * progressMax);

        // 5. Track live mAh
        double total_mAh = BatteryUtils.getMah(this);
        if (total_mAh <= 0) total_mAh = 4000.0;
        displayedMah = (exact_pct / 100.0) * total_mAh;

        throttleUiUpdates();
    }

    private void throttleUiUpdates() {
        long now = SystemClock.elapsedRealtime();
        // Only push to Android UI (Notifications/Widgets) every 1000ms
        if (now - lastUiUpdateTime >= 1000) {
            lastUiUpdateTime = now;
            updateNotification();
            updateWidget();
        }
    }

    public static String getPercentString() {
        return topText + "." + bottomText;
    }

    private Notification buildNotification() {
        Notification.Builder builder = new Notification.Builder(this, "channel_id")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent());

        if (!isTracking) {
            builder.setContentText("Service Stopped");
            builder.setSmallIcon(android.R.drawable.ic_lock_power_off);
        } else {
            String sign = cachedCurrentNow >= 0 ? "+" : "";
            float voltageV = cachedVoltageMv / 1000f;
            float watts = voltageV * Math.abs(cachedCurrentNow) / 1000f;

            String mahStr = displayedMah > 0
                ? String.format("%.0f mAh", displayedMah)
                : "-- mAh";

            String pctStr = getPercentString() + (isIndeterminate ? "%?" : "%");

            String currentStr = String.format("%s%d mA  ·  %.2fW  ·  %s  ·  %s",
                                              sign, cachedCurrentNow, watts, pctStr, mahStr);
            builder.setContentText(currentStr);

            String bottomDecimals = bottomText + (isIndeterminate ? "?" : "");
            builder.setSmallIcon(createBatteryIcon(topText, bottomDecimals));

            if (isIndeterminate) {
                builder.setProgress(0, 0, true);
            } else {
                builder.setProgress(progressMax, progressNow, false);
            }
        }

        Intent startIntent = new Intent(this, BatteryService.class).setAction("START");
        PendingIntent piStart = PendingIntent.getService(this, 1, startIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, BatteryService.class).setAction("STOP");
        PendingIntent piStop = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent exitIntent = new Intent(this, BatteryService.class).setAction("EXIT");
        PendingIntent piExit = PendingIntent.getService(this, 3, exitIntent, PendingIntent.FLAG_IMMUTABLE);

        if (isTracking) {
            builder.addAction(new Notification.Action.Builder(
                                  Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", piStop).build());
        } else {
            builder.addAction(new Notification.Action.Builder(
                                  Icon.createWithResource(this, android.R.drawable.ic_media_play), "Start", piStart).build());
        }

        builder.addAction(new Notification.Action.Builder(
                              Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Exit", piExit).build());

        return builder.build();
    }

    private PendingIntent openAppIntent() {
        return PendingIntent.getActivity(
            this, 0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(1, buildNotification());
    }

    private void updateWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        int[] ids = manager.getAppWidgetIds(new ComponentName(this, BatteryWidget.class));
        for (int id : ids) {
            BatteryWidget.updateWidget(this, manager, id);
        }
    }

    private Icon createBatteryIcon(String top, String bottom) {
        int size = 96;
        int half = size / 2;
        int margin = (int) (size * 0.05f);
        int availW = size - margin * 2;
        int availH = half - margin * 2;

        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setAntiAlias(true);

        Rect bounds = new Rect();

        paint.setTextSize(100);
        paint.getTextBounds(top, 0, top.length(), bounds);
        float topScale = Math.min((float) availW / bounds.width(), (float) availH / bounds.height()) * 0.95f;
        paint.setTextSize(100 * topScale);
        paint.getTextBounds(top, 0, top.length(), bounds);
        canvas.drawText(top, size / 2f, margin + availH / 2f + bounds.height() / 2f - bounds.bottom, paint);

        paint.setTextSize(100);
        paint.getTextBounds(bottom, 0, bottom.length(), bounds);
        float bottomScale = Math.min((float) availW / bounds.width(), (float) availH / bounds.height()) * 0.95f;
        paint.setTextSize(100 * bottomScale);
        paint.getTextBounds(bottom, 0, bottom.length(), bounds);
        canvas.drawText(bottom, size / 2f, half + margin + availH / 2f + bounds.height() / 2f - bounds.bottom, paint);

        return Icon.createWithBitmap(bitmap);
    }
}
