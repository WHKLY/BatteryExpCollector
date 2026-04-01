# BatteryExpCollector

BatteryExpCollector is an Android app for mobile phone power and battery experiments. It focuses on stable foreground data collection and exports detailed CSV data plus a summary file for each session.

Current app release: `v2.0.0`

The current recommended workflow is manual collection through the app UI. Automation-related code is still kept in the repository, but the primary collector path is the manual foreground collector.

## Main Features

- Foreground service collection for better stability during long experiments
- CSV export with battery, display, CPU, and network-related fields
- Summary text file output after each collection session
- Experiment notes and manual event markers
- Target brightness, brightness lock attempt, and keep-screen-on controls
- Runtime self-checks for permissions and basic experiment readiness
- CPU high power mode for simulating sustained heavy CPU load
- Configurable CPU stress levels: `Medium`, `High`, and `Extreme`
- Network power mode for download, upload, and burst-style network tasks
- Continuous network-task byte totals plus per-sample network-task byte deltas in CSV output
- Two-page English UI with separate `Monitor` and `Configuration` views

## CPU High Power Mode

The app includes a `CPU High Power` entry on the main screen. Starting this mode will:

- Start the existing battery collection service
- Start CPU stress threads in parallel
- Record the selected stress configuration in metadata and per-row CSV fields

Current stress presets:

- `Medium`: `2` threads, `65%` duty
- `High`: auto thread count, `85%` duty
- `Extreme`: auto thread count, `100%` duty

This mode is intended to approximate CPU-heavy scenarios such as sustained gaming-like load on the CPU side. It does not fully simulate GPU rendering load.

## Network Power Mode

The app also includes a `Network Power` mode for running network workloads in parallel with battery collection.

Current supported scenarios:

- `Download Loop`
- `Upload Loop`
- `Small Request Burst`

For the current workflow, `Download Loop` is the main recommended path. The app can be paired with the LAN test server so the same download URL can be measured under different server-side download conditions such as `normal` and `weak`.

Network-task statistics are written to CSV in two forms:

- continuous cumulative totals such as `NetTask_TotalDownloadBytes`
- per-sample deltas such as `NetTask_DownloadBytesDelta`

This makes it easier to align battery changes with ongoing network activity instead of only seeing step changes at operation boundaries.

## Recommended Experiment Workflow

For the current release, the recommended workflow is:

1. Start the LAN test server in either `normal` or `weak` mode.
2. Confirm the phone can access the server through the browser.
3. Open the app and configure either:
   - standard collection
   - CPU high power collection
   - network power collection
4. Start collection from the `Monitor` page.
5. Stop collection at the end of the run and export the CSV plus summary file.

For network experiments, `Download Loop` is the main validated path in this release.

## Collected CSV Fields

The CSV currently includes:

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
- `HighPowerEnabled`
- `CpuStressThreads`
- `CpuStressDutyPercent`
- `NetworkLoadEnabled`
- `NetworkScenario`
- `NetworkConnectionMode`
- `NetworkConcurrency`
- `NetTask_ActiveWorkers`
- `NetTask_TotalDownloadBytes`
- `NetTask_TotalUploadBytes`
- `NetTask_DownloadBytesDelta`
- `NetTask_UploadBytesDelta`
- `NetTask_SuccessCount`
- `NetTask_FailureCount`
- `NetTask_RetryCount`
- `NetTask_OperationCount`
- `NetTask_AvgOperationDurationMs`
- `ExperimentNote`
- `EventMarker`

## Output Files

Each collection session typically produces:

1. `CSV data file`
   Saves the full time-series sample data.

2. `Summary file`
   Saves session-level information such as:
   - start and end time
   - duration
   - sample count
   - brightness settings
   - keep-screen-on setting
   - high power mode status
   - CPU stress parameters
   - network workload configuration
   - total network-task download/upload bytes
   - charging or screen-off observations
   - experiment note

## Output Directory

- Android 10 and above: `Download/BatteryExpCollector`
- Android 9 and below: app private external directory under `BatteryExpCollector`

## Runtime Environment

- Android Studio
- Kotlin + Jetpack Compose
- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 28`
- `versionName = 2.0.0`

## Required Permissions

- Notification permission on Android 13+
- Modify system settings permission for brightness-related controls
- Foreground service permission
- Network state / Wi-Fi / phone state related reads used by status collection

## Usage

1. Open the project in Android Studio.
2. Connect a test phone and install the app.
3. Grant required permissions on first launch.
4. Configure collection parameters:
   - sampling interval
   - experiment note
   - target brightness
   - brightness lock attempt
   - keep screen on
5. Use one of the start modes:
   - normal collection
   - CPU high power collection with a selected stress level
   - network power collection for download or other network tasks
6. Add manual event markers during the run if needed.
7. Stop collection when the experiment ends.
8. Export the generated CSV and summary files from the output directory.

## Core Files

- `app/src/main/java/com/example/batteryexpcollector/MainActivity.kt`
  Main UI and collection controls
- `app/src/main/java/com/example/batteryexpcollector/BatteryCollectService.kt`
  Foreground collection service and CSV writing
- `app/src/main/java/com/example/batteryexpcollector/CpuStressController.kt`
  CPU stress worker controller for high power mode
- `app/src/main/java/com/example/batteryexpcollector/NetworkLoadController.kt`
  Network workload controller and task-side statistics
- `app/src/main/java/com/example/batteryexpcollector/CollectionPrefs.kt`
  Local persistence for session and config state
- `app/src/main/java/com/example/batteryexpcollector/SharedResultsStore.kt`
  Output file creation and storage handling

## Notes

- For longer experiments, it is recommended to allow auto-start and set the app battery policy to unrestricted on the test phone.
- Readability of current, CPU frequency, and network fields may vary by device vendor and Android build.
- If a field cannot be read, the corresponding `*ReadOk` field is recorded in the CSV where applicable.
- The repository still contains earlier automation-related code, but the released workflow is centered on the manual UI and foreground collector service.

## License

This project is currently intended for experiment and research usage. Add an explicit license if you plan to distribute it formally.
