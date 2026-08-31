#!/usr/bin/env bash
# AssetStudioMobile 一键构建脚本(国内镜像环境)
# 用法: ./build.sh            构建 debug APK
#       ./build.sh release    构建 release APK
set -e
cd "$(dirname "$0")"

# JDK 17(构建必需;Ubuntu/Debian: apt install openjdk-17-jdk-headless)
if [ -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
fi

# Android SDK 位置(本地已有 SDK 可改这里;依赖配置见 local.properties)
if [ -z "$ANDROID_HOME" ] && [ -f local.properties ]; then
    export ANDROID_HOME=$(grep '^sdk.dir=' local.properties | cut -d= -f2)
fi

TARGET="${1:-assembleDebug}"
echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
echo "目标: $TARGET"

./gradlew "$TARGET"

echo ""
echo "APK 输出:"
ls -la app/build/outputs/apk/debug/*.apk 2>/dev/null || true
ls -la app/build/outputs/apk/release/*.apk 2>/dev/null || true
