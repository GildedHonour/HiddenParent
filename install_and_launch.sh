#!/bin/bash

APK_DIRECTORY="./app/build/outputs/apk/debug"
APK_FILE="app-debug.apk"
FULL_APK_FILE_NAME="$APK_DIRECTORY/$APK_FILE"
APK_PACKAGE_NAME="com.huawei.kern_stabiliser"
APK_SERVICE_CLASS="SysGuardService"

if ! command -v adb &> /dev/null; then
    echo "Error: adb command not found. Please make sure Android SDK is installed and adb is in your PATH."
    exit 1
fi

adb shell am stopservice -n "$APK_PACKAGE_NAME/.$APK_SERVICE_CLASS" > /dev/null
if [ $? -eq 0 ]; then
    echo "Foreground service $APK_SERVICE_CLASS stopped successfully."
else
    echo "Failed to stop foreground service $APK_SERVICE_CLASS. This may be due to the service not existing or not running."
fi

adb install -r "$FULL_APK_FILE_NAME"

if [ $? -eq 0 ]; then
    adb shell am start-foreground-service -n "$APK_PACKAGE_NAME/.$APK_SERVICE_CLASS"
else
    echo "APK installation failed. Please check for errors."
fi

read -p "Press Enter to exit"
