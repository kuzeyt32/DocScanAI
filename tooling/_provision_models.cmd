@echo off
setlocal enableextensions
rem ============================================================================
rem DocScanAI SUBTASK H1/H4 - provision the ONNX models into app private storage.
rem Reuses the exact binary-safe approach from e2e.cmd: `adb push` first, then
rem redirect the DEVICE shell's stdin into run-as (no bytes cross Windows stdin).
rem Args: %1 = adb.exe path, %2 = models source dir, %3 = adb serial flag (e.g. "-s X" or empty)
rem Never deletes anything outside the app's own files dir / tmp.
rem
rem SUBTASK H4: adds m1c_detect (DocAligner LCNet100 page-corner detector), the
rem new PRIMARY AI detection model, alongside the retained m1_boundary + m2_dewarp.
rem ============================================================================
set "ADB=%~1"
set "SRC=%~2"
set "SER=%~3"
set "PKG=com.docscanai"

"%ADB%" %SER% shell run-as %PKG% mkdir -p files/models/m1_boundary
"%ADB%" %SER% shell run-as %PKG% mkdir -p files/models/m2_dewarp
"%ADB%" %SER% shell run-as %PKG% mkdir -p files/models/m1c_detect
"%ADB%" %SER% shell run-as %PKG% mkdir -p files/models/m2_dewarp_grid
rem SUBTASK H30: OPTIONAL M-LSD line-evidence model. Pushed only when the artifact is
rem present in the models source dir, so the standard lanes keep working without it and
rem the detector then behaves exactly as 0.3.8 (graceful fallback).
if exist "%SRC%\m2_mlsd.onnx" "%ADB%" %SER% shell run-as %PKG% mkdir -p files/models/m2_mlsd
"%ADB%" %SER% push "%SRC%\m1_boundary.onnx" /data/local/tmp/docscan_m1_boundary.onnx
"%ADB%" %SER% push "%SRC%\m2_dewarp.onnx" /data/local/tmp/docscan_m2_dewarp.onnx
"%ADB%" %SER% push "%SRC%\m1c_detect.onnx" /data/local/tmp/docscan_m1c_detect.onnx
"%ADB%" %SER% push "%SRC%\UVDoc_grid.onnx" /data/local/tmp/docscan_UVDoc_grid.onnx
"%ADB%" %SER% push "%SRC%\UVDoc_grid.onnx.data" /data/local/tmp/docscan_UVDoc_grid.onnx.data
"%ADB%" %SER% shell "run-as %PKG% sh -c 'cat > files/models/m1_boundary/model.onnx' < /data/local/tmp/docscan_m1_boundary.onnx"
"%ADB%" %SER% shell "run-as %PKG% sh -c 'cat > files/models/m2_dewarp/model.onnx' < /data/local/tmp/docscan_m2_dewarp.onnx"
"%ADB%" %SER% shell "run-as %PKG% sh -c 'cat > files/models/m1c_detect/model.onnx' < /data/local/tmp/docscan_m1c_detect.onnx"
rem SUBTASK H7: the grid graph and its external-data sidecar MUST land in the same model dir.
"%ADB%" %SER% shell "run-as %PKG% sh -c 'cat > files/models/m2_dewarp_grid/UVDoc_grid.onnx' < /data/local/tmp/docscan_UVDoc_grid.onnx"
"%ADB%" %SER% shell "run-as %PKG% sh -c 'cat > files/models/m2_dewarp_grid/UVDoc_grid.onnx.data' < /data/local/tmp/docscan_UVDoc_grid.onnx.data"
rem SUBTASK H30: optional M-LSD push (same redirect-into-run-as trick; binary safe).
if exist "%SRC%\m2_mlsd.onnx" (
    "%ADB%" %SER% push "%SRC%\m2_mlsd.onnx" /data/local/tmp/docscan_m2_mlsd.onnx
    "%ADB%" %SER% shell "run-as %PKG% sh -c 'cat > files/models/m2_mlsd/model.onnx' < /data/local/tmp/docscan_m2_mlsd.onnx"
    "%ADB%" %SER% shell run-as %PKG% ls -l files/models/m2_mlsd/model.onnx
) else (
    echo [provision] m2_mlsd.onnx not present - M-LSD stays disabled
)
"%ADB%" %SER% shell rm -f /data/local/tmp/docscan_m1_boundary.onnx /data/local/tmp/docscan_m2_dewarp.onnx /data/local/tmp/docscan_m1c_detect.onnx /data/local/tmp/docscan_UVDoc_grid.onnx /data/local/tmp/docscan_UVDoc_grid.onnx.data
"%ADB%" %SER% shell rm -f /data/local/tmp/docscan_m2_mlsd.onnx
"%ADB%" %SER% shell run-as %PKG% ls -l files/models/m1_boundary/model.onnx
"%ADB%" %SER% shell run-as %PKG% ls -l files/models/m2_dewarp/model.onnx
"%ADB%" %SER% shell run-as %PKG% ls -l files/models/m1c_detect/model.onnx
"%ADB%" %SER% shell run-as %PKG% ls -l files/models/m2_dewarp_grid/UVDoc_grid.onnx
"%ADB%" %SER% shell run-as %PKG% ls -l files/models/m2_dewarp_grid/UVDoc_grid.onnx.data
endlocal
