package com.brotherboard.deci;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class MainActivity extends Activity {

    private EditText etDivisor;
    private TextView tvPercent;
    private TextView tvCurrentNow;
    private TextView tvMah;
    private TextView tvCounter;
    private Button btnApply;
    private ProgressBar pbMain;
    private TextView tvWatts;
    private TextView tvVoltage;
    private TextView tvTemp;

    private Button btnToggle;
    private LinearLayout llNote;
    private TextView tvNote;

    private boolean applied = false;

    private Handler uiHandler = new Handler();
    private Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            updateCurrentLabel();
            uiHandler.postDelayed(this, 500); // Fast 500ms for smooth progress bar updates
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // create notification channel
        NotificationChannel channel = new NotificationChannel(
            "channel_id", "Deci", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        // calibrate divisor
        BatteryService.DIVISOR = calibrate();

        // battery optimization
        Intent batteryIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        batteryIntent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(batteryIntent);

        // bind views
        etDivisor    = findViewById(R.id.et_divisor);
        btnApply     = findViewById(R.id.btn_apply);
        pbMain       = findViewById(R.id.pb_main);
        tvPercent    = findViewById(R.id.tv_percent);
        tvCurrentNow = findViewById(R.id.tv_current_now);
        tvMah        = findViewById(R.id.tv_mah);
        tvCounter    = findViewById(R.id.tv_counter);
        tvWatts      = findViewById(R.id.tv_watts);
        tvVoltage    = findViewById(R.id.tv_voltage);
        tvTemp       = findViewById(R.id.tv_temp);

        btnToggle    = findViewById(R.id.btn_toggle);
        llNote       = findViewById(R.id.ll_note);
        tvNote       = findViewById(R.id.tv_note);

        etDivisor.setHint(formatDivisor(BatteryService.DIVISOR));
        updateCurrentLabel();

        findViewById(R.id.btn_calibrate).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    float divisor = calibrate();
                    etDivisor.setText(formatDivisor(divisor));
                    updateCurrentLabel();
                }
            });

        btnApply.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        String input = etDivisor.getText().toString().trim();
                        float divisor = input.isEmpty() ? calibrate() : Float.parseFloat(input);

                        // Resync exact math strictly to new divisor
                        BatteryService.DIVISOR = divisor;
                        BatteryService.lastCharge = -1;
                        BatteryService.integral_since_tick = 0.0;
                        BatteryService.learned_ratio = -1.0;
                        BatteryService.lastTickTime = SystemClock.elapsedRealtime();

                        updateCurrentLabel();
                        setApplied(true);
                    } catch (NumberFormatException e) {
                        etDivisor.setError("Invalid");
                    }
                }
            });

        etDivisor.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                public void onTextChanged(CharSequence s, int st, int b, int c) {
                    if (applied) setApplied(false);
                }
                public void afterTextChanged(Editable s) {}
            });

        btnToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (BatteryService.isServiceRunning && BatteryService.isTracking) {
                        Intent stopIntent = new Intent(MainActivity.this, BatteryService.class);
                        stopIntent.setAction("STOP");
                        startService(stopIntent);
                    } else {
                        Intent startIntent = new Intent(MainActivity.this, BatteryService.class);
                        startIntent.setAction("START");
                        startForegroundService(startIntent);
                    }
                    updateCurrentLabel();
                }
            });
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.post(uiTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(uiTicker);
    }

    private void setApplied(boolean state) {
        applied = state;
        if (state) {
            btnApply.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
            btnApply.setTextColor(Color.BLACK);
        } else {
            btnApply.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#222222")));
            btnApply.setTextColor(Color.WHITE);
        }
    }

    private float calibrate() {
        SharedPreferences prefs = getSharedPreferences("deci_prefs", MODE_PRIVATE);
        float autoDivisor = prefs.getFloat("auto_divisor", -1f);
        if (autoDivisor != -1f) return autoDivisor;

        BatteryManager bm = getSystemService(BatteryManager.class);
        int charge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        int pct = (int) bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

        if (pct == 100) return charge / 100f;

        float base = (float) charge / pct;
        float magnitude = (float) Math.pow(10, Math.floor(Math.log10(base)));
        return Math.round(base / magnitude) * magnitude;
    }

    private String formatDivisor(float d) {
        if (d == (int) d) return String.valueOf((int) d);
        return String.valueOf(d);
    }

    private void updateNoteUI() {
        SharedPreferences prefs = getSharedPreferences("deci_prefs", MODE_PRIVATE);
        float auto = prefs.getFloat("auto_divisor", -1f);

        if (BatteryService.isServiceRunning && BatteryService.isTracking && BatteryService.isIndeterminate) {
            llNote.setVisibility(View.VISIBLE);
            tvNote.setText("Waiting for a 0.1% change. This shouldn't take long.");
        } else if (auto != -1f) {
            llNote.setVisibility(View.VISIBLE);
            tvNote.setText("Your divisor may be: " + formatDivisor(auto));
        } else {
            llNote.setVisibility(View.GONE);
        }
    }

    private void updateCurrentLabel() {
        // Toggle Button Style
        if (BatteryService.isTracking) {
            btnToggle.setText("STOP");
            btnToggle.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#333333")));
            btnToggle.setTextColor(Color.WHITE);
        } else {
            btnToggle.setText("START");
            btnToggle.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
            btnToggle.setTextColor(Color.BLACK);
        }

        updateNoteUI();

        if (!BatteryService.isTracking || !BatteryService.isServiceRunning) {
            tvPercent.setText("--.--%");
            tvCurrentNow.setText("-- mA");
            tvWatts.setText("-- W");
            tvMah.setText("-- mAh");
            tvVoltage.setText("-- V");
            tvTemp.setText("-- °C");
            tvCounter.setText("--");

            pbMain.setVisibility(View.INVISIBLE);
            pbMain.setIndeterminate(false);
            pbMain.setProgress(0);
            return;
        }

        BatteryManager bm = getSystemService(BatteryManager.class);
        int charge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);

        // Grab values directly from Service to ensure exact sync
        int currentNow = BatteryService.cachedCurrentNow;
        String sign = currentNow >= 0 ? "+" : "";

        // Unrounded original exact math requested
        float voltageV = BatteryService.cachedVoltageMv / 1000f;
        float watts = voltageV * Math.abs(currentNow) / 1000f;

        String mahStr = BatteryService.displayedMah > 0
            ? String.format("%.0f mAh", BatteryService.displayedMah)
            : "-- mAh";

        // Append the '?' conditionally to text UI as requested
        String pctStr = BatteryService.getPercentString() + (BatteryService.isIndeterminate ? "%?" : "%");
        tvPercent.setText(pctStr);

        // Display exactly as you intended originally
        tvCurrentNow.setText(sign + currentNow + " mA");
        tvWatts.setText(String.format("%.2f W", watts));
        tvMah.setText(mahStr);
        tvVoltage.setText(String.format("%.3f V", voltageV));
        tvTemp.setText(String.format("%.1f °C", BatteryService.cachedTempC));
        tvCounter.setText(String.valueOf(charge));

        pbMain.setVisibility(View.VISIBLE);
        pbMain.setIndeterminate(BatteryService.isIndeterminate);
        if (!BatteryService.isIndeterminate) {
            pbMain.setMax(BatteryService.progressMax);
            pbMain.setProgress(Math.max(0, Math.min(BatteryService.progressMax, BatteryService.progressNow)));
        }
    }
}
