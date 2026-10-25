#!/bin/sh
# Xcode projesini project.yml'den üretir ve sabitlenmiş paket sürümlerini (Package.resolved) yerine koyar.
set -e
cd "$(dirname "$0")"
xcodegen generate
mkdir -p iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm
cp Package.resolved iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved
