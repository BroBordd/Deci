# Deci

<img width="2828" height="1591" alt="Image" src="https://github.com/user-attachments/assets/9ec19ecf-1b27-480a-99bf-f560fc01d062" />

Deci is a high-precision battery monitoring application for Android. 

By default, the Android operating system reports battery life in standard 1% increments. Deci utilizes high-frequency software-based Coulomb counting to interpolate the data between hardware ticks, calculating and displaying the device's exact battery state down to two decimal places (e.g., 63.24%).

## Features

* **Sub-Percent Precision:** Calculates and displays exact battery charge states down to the hundredth of a percent.
* **High-Frequency Interpolation:** Polls instantaneous current (`currentNow`) at 150ms intervals, integrating it against elapsed time to estimate charge fluctuation between physical hardware register updates.
* **Automated Hardware Calibration:** Hardware Coulomb counter divisors vary significantly across device manufacturers. Deci automatically detects hardware ticks, calculates the mathematical differential, and determines the device-specific hardware divisor.
* **Dynamic System Integration:** Includes a persistent foreground service with a dynamically rendered notification icon, displaying the fractional battery state directly in the status bar.
* **Home Screen Widget:** Provides a minimalist widget that updates in real-time with the fractional battery life.
* **Live Telemetry:** Monitors and displays Current (mA), Power (W), Voltage (V), Temperature (°C), and remaining Capacity (mAh).

## Architecture & Methodology

Standard Android battery broadcasts (`ACTION_BATTERY_CHANGED`) are event-driven and typically only fire upon significant state changes (e.g., a full 1% drop or power state change). Deci bypasses this limitation by interfacing directly with the Linux kernel's SysFS battery nodes via the Android `BatteryManager`.

1. **Mathematical Integration:** The core engine runs at 150ms intervals, multiplying the raw current traversing the device by the exact millisecond time delta since the last execution.
2. **Hardware Synchronization:** To prevent mathematical drift over time, Deci synchronizes its internal calculations with the physical hardware counter whenever a hardware tick is registered. This acts as an absolute anchor.
3. **UI Throttling:** While the background mathematical integration operates at 150ms, the user interface, notifications, and widgets are throttled to 1000ms updates. This mitigates unnecessary CPU wake-locks and prevents the operating system from terminating the service due to excessive resource consumption.

## Installation

1. Clone the repository:

    git clone https://github.com/yourusername/Deci-Battery.git

2. Open the project directory in Android Studio.
3. Sync Gradle and build the APK for your target device.

*Note: To ensure the background service runs uninterrupted, the application will prompt the user to exempt it from Android's battery optimization restrictions upon the initial launch.*

## Usage

* **Service Initialization:** The background tracking service does not initiate automatically on boot or app launch. The user must manually press "Start" within the application.
* **Indeterminate State Calibration:** Upon initialization, Deci displays an indeterminate progress state (indicated by a `?`). The application requires at least one hardware tick (typically a 0.1% to 1% change) to lock onto the hardware anchor and commence accurate fractional tracking.
* **Divisor Configuration:** If the automated divisor detection cannot confidently ascertain the hardware parameters, or if the specific hardware chip's divisor is already known, it can be inputted manually via the application interface.

## Screenshots

*(Add screenshots of the Main Activity, the Notification, and the Widget here)*

## License

This project is licensed under the MIT License - see the LICENSE file for details.
