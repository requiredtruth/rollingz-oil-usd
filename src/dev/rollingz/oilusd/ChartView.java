package dev.rollingz.oilusd;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class ChartView extends View {
    interface Listener { void select(Series.Point point); }

    private static final int GRID = 0xff263748, MUTED = 0xffa9bacb;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final RectF plot = new RectF();
    private final int accent;
    private final float density;
    private final float textUnit;
    private final Listener listener;
    private List<Series.Point> points = Collections.emptyList();
    private Series.Frame frame = Series.Frame.DAILY;
    private int selected = -1;
    private int extent = 2;
    private boolean outside;
    private String empty = "Loading paired candles…";

    ChartView(Context context, int accent, Listener listener) {
        super(context);
        this.accent = accent;
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        textUnit = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                1, getResources().getDisplayMetrics());
        setFocusable(true);
        setContentDescription("Rolling Z chart. Use left or right to inspect bars.");
    }

    void show(Series.Frame frame, List<Series.Point> points, String empty) {
        this.frame = frame;
        this.points = points;
        this.empty = empty;
        extent = 2;
        for (Series.Point p : points) extent = Math.max(extent, (int) Math.ceil(Math.abs(p.z)));
        setSelection(-1, false);
    }

    void synchronize(long timestamp) {
        if (points.isEmpty() || timestamp < points.get(0).pair.time) {
            setSelection(-1, true);
            return;
        }
        int low = 0, high = points.size();
        while (low < high) {
            int middle = (low + high) / 2;
            if (points.get(middle).pair.time <= timestamp) low = middle + 1;
            else high = middle;
        }
        setSelection(low - 1, false);
    }

    void clearMarker() { setSelection(-1, false); }

    Series.Point displayed() {
        return outside || points.isEmpty() ? null
                : points.get(selected < 0 ? points.size() - 1 : selected);
    }

    private void setSelection(int index, boolean outside) {
        selected = index;
        this.outside = outside;
        invalidate();
    }

    private float x(int index) {
        return points.size() <= 1 ? plot.centerX() : plot.left + plot.width() * index / (points.size() - 1);
    }

    private float y(double z) {
        return plot.centerY() - (float) z * plot.height() / (2 * extent);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        plot.set(38 * density, 14 * density, getWidth() - 12 * density, getHeight() - 30 * density);
        int[] levels = {-extent, 0, extent};
        for (int level : levels) {
            paint.setColor(level == 0 ? 0xff7790a7 : GRID);
            paint.setStrokeWidth((level == 0 ? 1.5f : 1f) * density);
            canvas.drawLine(plot.left, y(level), plot.right, y(level), paint);
            label(canvas, level > 0 ? "+" + level : Integer.toString(level), plot.left - 6 * density,
                    y(level) + 4 * density, Paint.Align.RIGHT, MUTED);
        }
        label(canvas, "Z", 4 * density, plot.top + 4 * density, Paint.Align.LEFT, MUTED);
        if (points.isEmpty()) {
            label(canvas, empty, plot.centerX(), y(0) - 12 * density, Paint.Align.CENTER, MUTED);
            return;
        }

        line.reset();
        for (int index = 0; index < points.size(); index++) {
            Series.Point point = points.get(index);
            boolean gap = index == 0 || (frame.seconds > 0
                    && point.pair.time - points.get(index - 1).pair.time > frame.seconds);
            float x = x(index), y = y(point.z);
            if (gap) {
                line.moveTo(x, y);
                paint.setColor(accent);
                canvas.drawCircle(x, y, density * 1.5f, paint);
            } else {
                line.lineTo(x, y);
            }
        }
        paint.setColor(accent);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(1.7f * density);
        canvas.drawPath(line, paint);
        paint.setStyle(Paint.Style.FILL);

        int last = points.size() - 1;
        if (last > 0) {
            label(canvas, axis(points.get(0)), plot.left, plot.bottom + 19 * density, Paint.Align.LEFT, MUTED);
            if (getWidth() > 300 * density && last > 2)
                label(canvas, axis(points.get(last / 2)), x(last / 2), plot.bottom + 19 * density,
                        Paint.Align.CENTER, MUTED);
            label(canvas, axis(points.get(last)), plot.right, plot.bottom + 19 * density, Paint.Align.RIGHT, MUTED);
        } else {
            label(canvas, axis(points.get(0)), plot.centerX(), plot.bottom + 19 * density, Paint.Align.CENTER, MUTED);
        }

        if (selected >= 0) {
            float x = x(selected), y = y(points.get(selected).z);
            paint.setColor(0xff91a8ba);
            paint.setStrokeWidth(density);
            canvas.drawLine(x, plot.top, x, plot.bottom, paint);
            paint.setColor(accent);
            canvas.drawCircle(x, y, 4 * density, paint);
        }
    }

    private String axis(Series.Point point) {
        String pattern = frame == Series.Frame.TODAY ? "HH:mm"
                : frame.seconds == 0 ? "MMM d, yy" : "MMM d HH:mm";
        return DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(Series.NEW_YORK)
                .format(Instant.ofEpochSecond(point.pair.time));
    }

    private void label(Canvas canvas, String text, float x, float y, Paint.Align align, int color) {
        paint.setColor(color);
        paint.setTextSize(10 * textUnit);
        paint.setTextAlign(align);
        canvas.drawText(text, x, y, paint);
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        if (points.isEmpty()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                requestFocus();
                // Fall through to inspect the initial touch.
            case MotionEvent.ACTION_MOVE:
                float proportion = Math.max(0, Math.min(1, (event.getX() - plot.left) / plot.width()));
                inspect(Math.round(proportion * (points.size() - 1)));
                return true;
            case MotionEvent.ACTION_UP:
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!points.isEmpty() && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            int index = selected < 0 ? points.size() - 1 : selected;
            inspect(Math.max(0, Math.min(points.size() - 1,
                    index + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1))));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void inspect(int index) {
        setSelection(index, false);
        listener.select(points.get(index));
    }
}
