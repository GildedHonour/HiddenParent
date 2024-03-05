#!/bin/bash

# for debug

APK_DIRECTORY="./app/build/outputs/apk/debug"
APK_FILE="app-debug.apk"
APK_PACKAGE_NAME="com.huawei.kern_stabiliser"
APK_SERVICE_CLASS="SysGuardService"

# Stop the foreground service
# mind the dot
adb shell am stopservice -n "${APK_PACKAGE_NAME}/.${APK_SERVICE_CLASS}"

# Check the result
if [ $? -eq 0 ]; then
  echo "Foreground service ${APK_SERVICE_CLASS} stopped successfully."
else
  echo "Failed to stop foreground service ${APK_SERVICE_CLASS}. Please check for errors."
fi