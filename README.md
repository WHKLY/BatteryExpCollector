# BatteryExpCollector

BatteryExpCollector is a data collection application for mobile phone power consumption experiments, designed for battery experiment scenarios. It supports continuous foreground collection, recording of multiple system states, CSV export, summary output, and a clearer collection control UI.

This version no longer includes automation scripting capabilities and is positioned as a **stable data collector**.

## Version Notes

### v1.1.0
- Added more collection fields
- Optimized collection interface and status display
- Added summary file output
- Support for experiment notes and manual markers
- Support for target brightness, attempt to lock brightness, and keep screen on configuration
- Result files are uniformly output to a directory that is easier to export

## Main Features

- Foreground service continuous collection to reduce impact from being killed in the background
- Records experimental data in CSV format
- Automatically outputs a summary text file after collection ends
- Supports experiment notes
- Supports manual event markers
- Supports displaying current file, recent file, and latest sample snapshot
- Supports target brightness setting
- Supports attempting to lock brightness
- Supports keeping screen on
- Supports basic permission and runtime status self-checks

## Currently Collectable Fields

The CSV currently includes the following fields:

- `Timestamp`
- `ElapsedTime_S`
- `SOC_Integer`
- `BatteryPct_Float`
- `Voltage_mV`
- `Current_uA`
- `BatteryTemp_C`
- `ChargeCounter_uAh`
- `BatteryStatus`
- `PluggedType`
- `BatteryHealth`
- `BatteryPresent`
- `BatteryScale`
- `TargetBrightness`
- `Brightness`
- `BrightnessSetOk`
- `BrightnessReadOk`
- `ScreenOn`
- `ScreenOnReadOk`
- `CPU0_Freq_kHz`
- `CPUFreqReadOk`
- `NetType`
- `TxBytes_Total`
- `RxBytes_Total`
- `Tx_Rate_Bps`
- `Rx_Rate_Bps`
- `NetStatsReadOk`
- `BatteryIntentReadOk`
- `BatteryPropertyReadOk`
- `ExperimentNote`
- `EventMarker`

## Output Files

After each collection session, two types of files are typically generated:

1. **CSV Data File**
   - Saves complete per-second sample data

2. **Summary File**
   - Filename usually corresponds to the CSV file
   - Records summary information for the collection session, such as:
     - session start / end
     - duration seconds
     - sample count
     - target brightness
     - enforce brightness
     - keep screen on
     - screen off observed
     - charging observed
     - experiment note
     - results directory

## File Output Directory

- Android 10 and above:
  - `Download/BatteryExpCollector`
- Android 9 and below:
  - App's private external directory under `BatteryExpCollector`

## Runtime Environment

- Android Studio
- Android SDK:
  - `compileSdk = 36`
  - `targetSdk = 36`
  - `minSdk = 28`
- Kotlin + Jetpack Compose

## Permissions

The app involves the following capabilities during runtime:

- Notification permission (Android 13+)
- Modify system settings permission (used for brightness-related functions)
- Foreground service permission
- Network status read permission
- Wi-Fi / phone status read capabilities (used for network type and some system information)

## Usage

1. Open the project with Android Studio
2. Connect a test phone and run the app
3. Grant the required permissions when prompted on first launch
4. Set collection parameters on the main interface:
   - Sampling interval
   - Experiment notes
   - Target brightness
   - Whether to attempt to lock brightness
   - Whether to keep screen on
5. Click "Start Collection" to begin the experiment
6. Add manual markers as needed during collection
7. Click "Stop Collection" to end the experiment
8. Navigate to the output directory to view the CSV and summary files

## UI Description

The main interface currently includes the following information and operation areas:

- Device status
- Permissions and self-check information
- Current file / Recent file
- Latest sample snapshot
- Collection configuration area
- Start Collection / Stop Collection buttons
- Manual marker input and write button

## Project Structure (Core Files)

- `app/src/main/java/com/example/batteryexpcollector/MainActivity.kt`
  - Main interface and collection control
- `app/src/main/java/com/example/batteryexpcollector/BatteryCollectService.kt`
  - Foreground collection service, responsible for actual sampling, writing CSV, and outputting summary
- `app/src/main/java/com/example/batteryexpcollector/CollectionPrefs.kt`
  - Local persistence for collection status, recent files, recent samples, etc.
- `app/src/main/java/com/example/batteryexpcollector/SharedResultsStore.kt`
  - Management of results directory and output file paths

## Important Notes

- To minimize impact from system restrictions, it is recommended to:
  - Allow the app to auto-start on the test phone
  - Set battery policy to "Unrestricted"
  - Ensure notification permission and modify system settings permission are granted
- The readability of fields such as current, CPU frequency, and network statistics may vary across different manufacturers' systems
- If some fields fail to be read, fallback values and corresponding `*ReadOk` fields will be recorded in the CSV to indicate status

## License

This project is for experiment and research usage. Add an explicit license if you plan to open-source it formally.
