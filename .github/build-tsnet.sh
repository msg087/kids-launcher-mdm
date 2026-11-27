#!/usr/bin/env bash
set -euo pipefail
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.1.13356709"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;28.1.13356709"
export GOTOOLCHAIN=go1.27.4
export PATH="$(go env GOPATH)/bin:$PATH"
mobile=v0.0.0-20261117202452-47699113848a
go install "golang.org/x/mobile/cmd/gomobile@$mobile"
go install "golang.org/x/mobile/cmd/gobind@$mobile"
mkdir -p app/libs
cd mobile
go get tailscale.com/tsnet@v1.104.7 github.com/wlynxg/anet@v0.0.5
go get -tool "golang.org/x/mobile/cmd/gobind@$mobile"
gomobile init
gomobile bind -o ../app/libs/tsnet.aar -target=android/arm,android/arm64,android/amd64 \
  -androidapi 34 -ldflags="-checklinkname=0" ./tsembed
