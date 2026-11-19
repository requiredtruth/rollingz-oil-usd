package dev.rollingz.oilusd;

import android.app.*;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff0a121c, CARD = 0xff101c29, TEXT = 0xffeef5fb,
            MUTED = 0xffb0c0cf, MINT = 0xff62e6c2, AMBER = 0xffffc478;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
            .withZone(Series.NEW_YORK);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm z", Locale.US)
            .withZone(Series.NEW_YORK);
    private static final DateTimeFormatter FETCHED = DateTimeFormatter.ofPattern("MMM d HH:mm z", Locale.US)
            .withZone(Series.NEW_YORK);

    private final ExecutorService loaders = Executors.newFixedThreadPool(3);
    private final ExecutorService requests = Executors.newFixedThreadPool(6);
    private final ExecutorService calculation = Executors.newSingleThreadExecutor();
    private final Map<String, MarketData.Snapshot> snapshots = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            refreshVisible();
            handler.postDelayed(this, 120_000);
        }
    };
    private MarketData market;
    private Panel macro, intraday;
    private SharedPreferences preferences;
    private Panel inspectedPanel;
    private long inspectedTime;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        preferences = getSharedPreferences("dashboard", MODE_PRIVATE);
        market = new MarketData(this, requests);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout body = column();
        body.setPadding(dp(16), dp(12), dp(16), dp(20));
        scroll.addView(body);
        setContentView(scroll);

        LinearLayout heading = row();
        LinearLayout titles = column();
        heading.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        titles.addView(text("Rolling Z", 29, TEXT, true));
        titles.addView(text("DXY / Brent crude futures", 14, MUTED, false));
        Button refresh = button("Refresh");
        refresh.setContentDescription("Refresh both market charts");
        refresh.setOnClickListener(view -> refreshVisible());
        heading.addView(refresh);
        body.addView(heading);

        macro = new Panel("Macro divergence", "macro", MINT, Series.Frame.DAILY, Series.Frame.WEEKLY);
        intraday = new Panel("Intraday divergence", "intraday", AMBER,
                Series.Frame.FIVE, Series.Frame.HOUR, Series.Frame.TODAY);
        body.addView(macro.card, panelLayout());
        body.addView(intraday.card, panelLayout());

        TextView method = text("Eastern time · Bar spacing follows paired observations. "
                + "Positive Z indicates a DXY/Brent ratio above its rolling baseline. "
                + "Feed quotes may be delayed.", 12, MUTED, false);
        method.setPadding(dp(3), dp(12), dp(3), 0);
        body.addView(method);
        render(macro);
        render(intraday);
    }

    @Override protected void onStart() {
        super.onStart();
        heartbeat.run();
    }

    @Override protected void onStop() {
        handler.removeCallbacks(heartbeat);
        super.onStop();
    }

    @Override protected void onDestroy() {
        loaders.shutdownNow();
        requests.shutdownNow();
        calculation.shutdownNow();
        super.onDestroy();
    }

    private void refreshVisible() {
        load(macro.frame);
        load(intraday.frame);
    }

    private void load(Series.Frame frame) {
        String key = frame.key();
        if (!loading.add(key)) return;
        updateStatus(macro);
        updateStatus(intraday);
        MarketData.Snapshot previous = snapshots.get(key);
        loaders.execute(() -> {
            MarketData.Snapshot initial = previous == null ? market.cached(frame) : previous;
            if (previous == null) runOnUiThread(() -> accept(key, initial, false));
            if (Thread.currentThread().isInterrupted()) return;
            MarketData.Snapshot fresh = market.refresh(frame, initial);
            runOnUiThread(() -> accept(key, fresh, true));
        });
    }

    private void accept(String key, MarketData.Snapshot snapshot, boolean complete) {
        if (isDestroyed()) return;
        if (complete) loading.remove(key);
        snapshots.put(key, snapshot);
        if (macro.frame.key().equals(key)) render(macro);
        if (intraday.frame.key().equals(key)) render(intraday);
    }

    private void render(Panel panel) {
        Series.Frame frame = panel.frame;
        int n = panel.lookback;
        int revision = ++panel.revision;
        MarketData.Snapshot snapshot = snapshots.get(frame.key());
        boolean sameFrame = panel.drawnFrame == frame;
        if (!sameFrame || snapshot == null) {
            panel.drawnSnapshot = null;
            panel.chart.show(frame, Collections.emptyList(), "Loading paired candles…");
            panel.value.setText("—");
            panel.readout.setText("Waiting for paired candles");
        }
        updateStatus(panel);
        if (snapshot == null) return;
        calculation.execute(() -> {
            boolean sample = snapshot.fetchedAt == 0;
            LocalDate day = sample ? Series.day(snapshot.pairs.get(snapshot.pairs.size() - 1).time)
                    : LocalDate.now(Series.NEW_YORK);
            List<Series.Point> points = Series.rolling(snapshot.pairs, n, frame, day);
            String empty = frame == Series.Frame.TODAY && !Series.day(
                    snapshot.pairs.get(snapshot.pairs.size() - 1).time).equals(day)
                    ? "No paired bars today" : "Insufficient history for N = " + n;
            runOnUiThread(() -> {
                if (isDestroyed() || panel.revision != revision) return;
                panel.drawnSnapshot = snapshot;
                panel.drawnFrame = frame;
                panel.chart.show(frame, points, empty);
                sync();
                updateStatus(panel);
            });
        });
    }

    private void updateStatus(Panel panel) {
        MarketData.Snapshot snapshot = snapshots.get(panel.frame.key());
        MarketData.Snapshot displayed = panel.drawnSnapshot;
        boolean isLoading = loading.contains(panel.frame.key()) || displayed != snapshot;
        if (displayed == null) {
            panel.status.setText("Loading market data…");
            panel.status.setTextColor(MUTED);
            return;
        }
        String detail = displayed.detail.isEmpty() ? "" : " · " + displayed.detail;
        String fetched = displayed.fetchedAt == 0 ? "" : " · checked "
                + FETCHED.format(Instant.ofEpochMilli(displayed.fetchedAt));
        panel.status.setText(displayed.source + fetched + detail + (isLoading ? " · Refreshing…" : ""));
        panel.status.setTextColor(displayed.fetchedAt == 0 || !displayed.detail.isEmpty() ? AMBER : MUTED);
    }

    private void sync() {
        boolean comparable = macro.drawnSnapshot != null && intraday.drawnSnapshot != null
                && (macro.drawnSnapshot.fetchedAt == 0) == (intraday.drawnSnapshot.fetchedAt == 0);
        for (Panel panel : new Panel[]{macro, intraday}) {
            boolean linked = inspectedPanel != null && inspectedPanel != panel && comparable;
            boolean selected = inspectedPanel == panel || linked;
            if (selected) panel.chart.synchronize(inspectedTime);
            else panel.chart.clearMarker();
            Series.Point point = panel.chart.displayed();
            if (point == null) {
                panel.value.setText("—");
                panel.readout.setText(selected ? "No displayed bar at or before selection" : "No Z reading");
            } else {
                String stamp = (panel.frame.seconds == 0 ? DATE : TIME)
                        .format(Instant.ofEpochSecond(point.pair.time));
                panel.value.setText(String.format(Locale.US, "%+.2f Z", point.z));
                panel.readout.setText(String.format(Locale.US, "%s  %s\nDXY %.2f   ·   Brent $%.2f",
                        linked ? "At/before" : selected ? "Selected" : "Latest",
                        stamp, point.pair.dxy, point.pair.brent));
            }
        }
    }

    private LinearLayout.LayoutParams panelLayout() {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(12);
        return layout;
    }

    private final class Panel {
        final LinearLayout card;
        final ChartView chart;
        final TextView value, status, readout;
        final Button window;
        final Button[] tabs;
        final int accent;
        final String id;
        Series.Frame frame, drawnFrame;
        int lookback, revision;
        MarketData.Snapshot drawnSnapshot;

        Panel(String title, String id, int accent, Series.Frame... frames) {
            this.id = id;
            this.accent = accent;
            int index = preferences.getInt(id + "_frame", 0);
            frame = frames[Math.max(0, Math.min(frames.length - 1, index))];
            lookback = Math.max(5, Math.min(120, preferences.getInt(id + "_lookback", 30)));
            card = column();
            card.setPadding(dp(12), dp(10), dp(12), dp(9));
            card.setBackground(shape(CARD, 13));

            LinearLayout top = row();
            top.addView(text(title, 17, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
            value = text("—", 19, accent, true);
            value.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            top.addView(value);
            card.addView(top);
            LinearLayout controls = row();
            tabs = new Button[frames.length];
            for (int i = 0; i < frames.length; i++) {
                final int position = i;
                tabs[i] = button(frames[i].label);
                controls.addView(tabs[i]);
                tabs[i].setOnClickListener(view -> {
                    Series.Frame next = frames[position];
                    if (next == frame) return;
                    frame = next;
                    preferences.edit().putInt(id + "_frame", position).apply();
                    styleTabs(frames);
                    render(this);
                    if (!snapshots.containsKey(frame.key())) load(frame);
                });
            }
            controls.addView(new View(MainActivity.this), new LinearLayout.LayoutParams(0, 1, 1));
            window = button("N " + lookback);
            window.setContentDescription("Change lookback for " + title);
            window.setOnClickListener(view -> chooseWindow(this));
            controls.addView(window);
            card.addView(controls);
            styleTabs(frames);

            chart = new ChartView(MainActivity.this, accent, point -> {
                inspectedPanel = this;
                inspectedTime = point.pair.time;
                sync();
            });
            card.addView(chart, new LinearLayout.LayoutParams(-1, dp(134)));
            readout = text("Waiting for paired candles", 12, TEXT, false);
            readout.setMinLines(2);
            readout.setOnClickListener(view -> {
                inspectedPanel = null;
                sync();
            });
            card.addView(readout);
            status = text("Loading market data…", 11, MUTED, false);
            card.addView(status);
        }

        void styleTabs(Series.Frame[] frames) {
            for (int i = 0; i < tabs.length; i++) {
                boolean active = frame == frames[i];
                tabs[i].setSelected(active);
                tabs[i].setTextColor(active ? accent : MUTED);
                tabs[i].setBackgroundTintList(ColorStateList.valueOf(active ? 0xff263c48 : 0xff192836));
            }
        }
    }

    private void chooseWindow(Panel panel) {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(Integer.toString(panel.lookback));
        input.setSelectAllOnFocus(true);
        LinearLayout wrap = column();
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Rolling lookback")
                .setMessage("Use 5–120 paired bars of the selected timeframe.")
                .setView(wrap).setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int n;
            try {
                n = Integer.parseInt(input.getText().toString());
            } catch (NumberFormatException error) {
                n = 0;
            }
            if (n < 5 || n > 120) {
                input.setError("Enter an integer from 5 to 120");
                return;
            }
            panel.lookback = n;
            panel.window.setText("N " + n);
            preferences.edit().putInt(panel.id + "_lookback", n).apply();
            render(panel);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setTextColor(TEXT);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackgroundTintList(ColorStateList.valueOf(0xff192836));
        return button;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }
}
