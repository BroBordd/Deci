package com.brotherboard.deci;

import android.content.Context;

public class BatteryUtils {
    public static double getMah(Context context) {
        Object mPowerProfile;
        double batteryCapacity = 0;
        final String POWER_PROFILE_CLASS = "com.android.internal.os.PowerProfile";
        try {
            mPowerProfile = Class.forName(POWER_PROFILE_CLASS)
                .getConstructor(Context.class)
                .newInstance(context);
            batteryCapacity = (double) Class
                .forName(POWER_PROFILE_CLASS)
                .getMethod("getBatteryCapacity")
                .invoke(mPowerProfile);
        } catch (Exception e) {}
        return batteryCapacity;
    }
}
