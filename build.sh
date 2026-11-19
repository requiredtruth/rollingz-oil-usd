#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
: "${sdk:?Set ANDROID_SDK_ROOT to an Android SDK installation}"
tools=${ANDROID_BUILD_TOOLS:-"$sdk/build-tools/35.0.0"}
android="$sdk/platforms/android-35/android.jar"
output="$PWD/build"
mkdir -p "$output"/{classes,dex}
find "$output/classes" -name '*.class' -delete
find "$output/dex" -name '*.dex' -delete

if [[ -n ${ECJ_JAR:-} ]]; then
    compiler=(java -jar "$ECJ_JAR" -proc:none)
else
    compiler=(javac)
fi
mapfile -t sources < <(find src -name '*.java' | sort)
"${compiler[@]}" -source 8 -target 8 -classpath "$android" -d "$output/classes" "${sources[@]}"
mapfile -t classes < <(find "$output/classes" -name '*.class' | sort)
"$tools/d8" --min-api 26 --lib "$android" --output "$output/dex" "${classes[@]}"
"$tools/aapt2" compile --dir res -o "$output/resources.zip"
"$tools/aapt2" link -I "$android" --manifest AndroidManifest.xml \
    -o "$output/unsigned.apk" "$output/resources.zip"
(
    cd "$output/dex"
    zip -q -0 "$output/unsigned.apk" classes.dex
)
"$tools/zipalign" -f 4 "$output/unsigned.apk" "$output/aligned.apk"

keystore=${ROLLINGZ_KEYSTORE:-"$output/rollingz-debug.jks"}
export ROLLINGZ_STORE_PASSWORD=${ROLLINGZ_STORE_PASSWORD:-android}
export ROLLINGZ_KEY_PASSWORD=${ROLLINGZ_KEY_PASSWORD:-$ROLLINGZ_STORE_PASSWORD}
alias=${ROLLINGZ_KEY_ALIAS:-rollingz-debug}
if [[ ! -e "$keystore" ]]; then
    if [[ -n ${ROLLINGZ_KEYSTORE:-} ]]; then
        echo "Signing keystore does not exist" >&2
        exit 1
    fi
    keytool -genkeypair -keystore "$keystore" -storetype JKS \
        -storepass:env ROLLINGZ_STORE_PASSWORD -keypass:env ROLLINGZ_KEY_PASSWORD \
        -alias "$alias" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Rolling Z Debug"
fi
"$tools/apksigner" sign --ks "$keystore" --ks-key-alias "$alias" \
    --ks-pass env:ROLLINGZ_STORE_PASSWORD --key-pass env:ROLLINGZ_KEY_PASSWORD \
    --out "$output/rollingz-oil-usd.apk" "$output/aligned.apk"
"$tools/apksigner" verify --verbose "$output/rollingz-oil-usd.apk"
"$tools/zipalign" -c 4 "$output/rollingz-oil-usd.apk"
