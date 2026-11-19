package dev.rollingz.oilusd;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class MarketData {
    static final class Snapshot {
        final List<Series.Pair> pairs;
        final String source, detail;
        final long fetchedAt;

        Snapshot(List<Series.Pair> pairs, String source, String detail, long fetchedAt) {
            this.pairs = pairs;
            this.source = source;
            this.detail = detail;
            this.fetchedAt = fetchedAt;
        }
    }

    private final File files;
    private final ExecutorService requests;

    MarketData(Context context, ExecutorService requests) {
        files = context.getFilesDir();
        this.requests = requests;
    }

    Snapshot cached(Series.Frame frame) {
        AtomicFile cache = cache(frame);
        try {
            JSONObject result = new JSONObject(new String(cache.readFully(), StandardCharsets.UTF_8));
            JSONArray bars = result.getJSONArray("bars");
            List<Series.Pair> pairs = new ArrayList<>();
            for (int i = 0; i < bars.length(); i++) {
                JSONArray bar = bars.getJSONArray(i);
                pairs.add(new Series.Pair(bar.getLong(0), bar.getDouble(1), bar.getDouble(2)));
            }
            if (!pairs.isEmpty()) return new Snapshot(pairs, "Cached", "", result.getLong("fetchedAt"));
        } catch (IOException | JSONException ignored) {
            // An absent or unreadable cache can be rebuilt from the feed.
        }
        return new Snapshot(Series.sample(frame), "Sample", "Synthetic data", 0);
    }

    Snapshot refresh(Series.Frame frame, Snapshot previous) {
        Future<NavigableMap<Long, Double>> dxy = requests.submit(() -> quotes("DX-Y.NYB", frame));
        Future<NavigableMap<Long, Double>> brent = requests.submit(() -> quotes("BZ%3DF", frame));
        try {
            List<Series.Pair> pairs = Series.align(dxy.get(), brent.get());
            if (pairs.size() < 10) throw new IOException("too few timestamp matches");
            long now = System.currentTimeMillis();
            String detail = "";
            try {
                write(frame, pairs, now);
            } catch (IOException | JSONException error) {
                detail = "Could not save offline cache";
            }
            return new Snapshot(pairs, "Feed", detail, now);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return previous;
        } catch (ExecutionException | IOException error) {
            Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
            return new Snapshot(previous.pairs, previous.fetchedAt == 0 ? "Sample" : "Cached",
                    "Refresh failed · " + cause.getMessage(), previous.fetchedAt);
        } finally {
            dxy.cancel(true);
            brent.cancel(true);
        }
    }

    private static NavigableMap<Long, Double> quotes(String symbol, Series.Frame frame) throws IOException {
        IOException failure = null;
        for (String host : new String[]{"query1.finance.yahoo.com", "query2.finance.yahoo.com"}) {
            try {
                return fetch(host, symbol, frame);
            } catch (IOException | JSONException error) {
                failure = new IOException(error instanceof JSONException ? "invalid feed response"
                        : error instanceof SocketTimeoutException ? "feed timed out" : error.getMessage());
            }
        }
        throw failure;
    }

    private static NavigableMap<Long, Double> fetch(String host, String symbol, Series.Frame frame)
            throws IOException, JSONException {
        URL url = URI.create("https://" + host + "/v8/finance/chart/" + symbol + "?range=" + frame.range
                + "&interval=" + frame.interval + "&includePrePost=false").toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(6500);
        connection.setReadTimeout(6500);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) RollingZ/1.0");
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status);
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (body.size() + count > 4_000_000) throw new IOException("response too large");
                    body.write(buffer, 0, count);
                }
                JSONObject result = new JSONObject(body.toString("UTF-8")).getJSONObject("chart")
                        .getJSONArray("result").getJSONObject(0);
                JSONArray times = result.getJSONArray("timestamp");
                JSONArray prices = result.getJSONObject("indicators").getJSONArray("quote")
                        .getJSONObject(0).getJSONArray("close");
                if (times.length() != prices.length()) throw new IOException("malformed candle arrays");
                NavigableMap<Long, Double> bars = new TreeMap<>();
                long now = System.currentTimeMillis() / 1000;
                for (int i = 0; i < times.length(); i++) {
                    double close = prices.optDouble(i, Double.NaN);
                    long timestamp = times.getLong(i);
                    if (!Double.isFinite(close) || close <= 0 || timestamp > now) continue;
                    long bucket = Series.bucket(timestamp, frame);
                    if (frame.seconds > 0 && bucket + frame.seconds > now) continue;
                    bars.put(bucket, close);
                }
                if (bars.isEmpty()) throw new IOException("no valid candles");
                return bars;
            }
        } finally {
            connection.disconnect();
        }
    }

    private AtomicFile cache(Series.Frame frame) {
        return new AtomicFile(new File(files, "quotes-v1-" + frame.key() + ".json"));
    }

    private void write(Series.Frame frame, List<Series.Pair> pairs, long fetchedAt)
            throws IOException, JSONException {
        JSONArray bars = new JSONArray();
        for (Series.Pair bar : pairs)
            bars.put(new JSONArray().put(bar.time).put(bar.dxy).put(bar.brent));
        byte[] bytes = new JSONObject().put("fetchedAt", fetchedAt).put("bars", bars)
                .toString().getBytes(StandardCharsets.UTF_8);
        AtomicFile cache = cache(frame);
        FileOutputStream output = cache.startWrite();
        try {
            output.write(bytes);
            cache.finishWrite(output);
        } catch (IOException error) {
            cache.failWrite(output);
            throw error;
        }
    }
}
