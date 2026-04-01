# BatteryExpCollector v2.0.0

## Overview

`v2.0.0` is the first release candidate that brings the battery experiment app to a complete, experiment-ready workflow centered on manual UI control, stable foreground collection, CPU stress testing, and LAN-based network download testing.

## Highlights

- Redesigned UI with separate `Monitor` and `Configuration` pages
- Fully English primary UI flow
- CPU high power collection mode with configurable stress presets
- Network power collection mode with download, upload, and burst scenarios
- Verified LAN download workflow for battery and network experiments
- Continuous network-task byte accumulation in CSV output
- Per-sample network-task byte delta fields in CSV output
- Session summary output for easier post-run review

## Recommended Validated Paths

- Standard collection
- CPU high power collection
- Network power `Download Loop`

The current recommended network experiment path is download-based testing paired with the LAN test server in either `normal` or `weak` mode.

## Data Improvements

This release improves network-task observability in exported CSV files:

- `NetTask_TotalDownloadBytes` and `NetTask_TotalUploadBytes` are now accumulated continuously during transfer
- `NetTask_DownloadBytesDelta` and `NetTask_UploadBytesDelta` are recorded per sample

These changes make it easier to align battery behavior with ongoing network activity.

## Included Components

- Android collector app
- CPU stress mode
- Network power mode
- LAN server compatibility for controlled download experiments

## Notes

- Upload-related code remains in the project, but download-based network experiments are the current primary workflow.
- Automation-related code is still present in the repository, but it is not the main recommended release path.
