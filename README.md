# Rolling Z · DXY / Brent

Native Android dashboard for the spread between the US Dollar Index and Brent crude futures. Android 8.0 (API 26) or later.

- **Macro** offers Daily and Weekly views. Weekly bars use the last paired daily close of each Monday–Sunday week.
- **Intraday** offers 5m, 1h, and Today. Today uses 5-minute candles from the current New York calendar day and uses earlier observations to warm up the window. If there are no matched candles today, it shows that state.
- Tap or drag to inspect a bar, with its timestamp, Z, and both closes. The other chart follows to its most recent displayed observation at or before the selected timestamp, if available. Tap a readout to reset. Choose a lookback between 5 and 120 paired bars independently per chart.
- Both charts open with the most recent available data and refresh on launch, every two minutes while visible, and when you press Refresh.

## Indicator and alignment

For paired closes, the differential is `100 × ln(DXY / Brent)`. This measures the difference in log prices and makes Z invariant to a constant change in the quoting units. The current differential minus its trailing N-bar mean is divided by its population standard deviation over the same window, including the current bar. No score is emitted before N bars. A constant window gives zero.

Daily bars join by New York calendar date. Intraday bars join by identical UTC interval buckets, with timestamps shown in Eastern time. The app skips missing, null, and nonpositive closes and does not forward-fill. It skips still-forming intraday buckets and irregularly timed trailing quote snapshots. Daily and weekly views can include the provider's partial current period. The x-axis advances by paired observations and intraday lines break across missing intervals. Daily and weekly timestamps represent the observation's date. The active session can cross New York midnight and the Today filter uses the calendar date.

## Feed and offline behavior

Yahoo Finance chart endpoints (`query1.finance.yahoo.com`, with `query2.finance.yahoo.com` as a host fallback) provide [DX-Y.NYB](https://finance.yahoo.com/quote/DX-Y.NYB/) and [BZ=F](https://finance.yahoo.com/quote/BZ%3DF/). The app requests 5 years of daily bars, 1 month of five-minute bars, and 3 months of hourly bars. The Brent series follows Yahoo's futures roll, and roll discontinuities can affect the indicator. Quotes may be delayed. These public endpoints are best effort and may rate-limit clients or change. The build workspace observed HTTP 429 on initial probes, then successfully downloaded both symbols at all three requested intervals.

A successful refresh atomically saves matched source candles per interval. Failed refreshes keep the previous data and show the error. On a first launch without usable feed data, the app displays deterministic synthetic bars clearly labeled Sample (Today shows the final synthetic session). Samples are never written as market data or linked with a real-data chart.

## Build and install

With JDK 17+, Bash, zip, and Android SDK command-line tools installed:

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0'
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash test.sh
bash build.sh
adb install -r build/rollingz-oil-usd.apk
```

The script generates a local debug signing key when one is absent and verifies the APK signature and alignment. Set `ROLLINGZ_KEYSTORE`, `ROLLINGZ_KEY_ALIAS`, `ROLLINGZ_STORE_PASSWORD`, and optionally `ROLLINGZ_KEY_PASSWORD` to reuse a deployment key. GitHub Actions supports a stable signing key via the corresponding documented workflow secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_PASSWORD`). Android updates require the same signing key. A workflow run without configured secrets generates a fresh signing key. Increment both the manifest version name and code for each new release.
