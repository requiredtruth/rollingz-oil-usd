package dev.rollingz.oilusd;

import java.time.LocalDate;
import java.util.*;

public final class SeriesTest {
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        TreeMap<Long, Double> dxy = new TreeMap<>(), brent = new TreeMap<>();
        dxy.put(100L, 99.0);
        dxy.put(400L, 100.0);
        brent.put(400L, 80.0);
        brent.put(700L, 81.0);
        List<Series.Pair> matches = Series.align(dxy, brent);
        check(matches.size() == 1 && matches.get(0).time == 400, "Align by time, not position");
        check(Series.bucket(655, Series.Frame.FIVE) == 600, "Five-minute UTC bucket");
        LocalDate today = LocalDate.of(2026, 1, 14);
        long start = Series.start(today);
        List<Series.Pair> pairs = Arrays.asList(new Series.Pair(start - 600, 1, 1),
                new Series.Pair(start - 300, Math.E, 1), new Series.Pair(start, Math.exp(2), 1));
        List<Series.Point> current = Series.rolling(pairs, 3, Series.Frame.TODAY, today);
        check(current.size() == 1, "Today uses earlier bars for warm-up");
        check(Math.abs(current.get(0).z - Math.sqrt(1.5)) < 1e-9, "Population Z score");
        check(Series.rolling(pairs, 4, Series.Frame.FIVE, today).isEmpty(), "Full window required");
        List<Series.Pair> flat = Arrays.asList(new Series.Pair(start, 10, 5),
                new Series.Pair(start + 300, 20, 10));
        check(Series.rolling(flat, 2, Series.Frame.FIVE, today).get(0).z == 0, "Flat ratio Z");
        List<Series.Pair> days = Arrays.asList(new Series.Pair(Series.start(today.minusDays(8)), 99, 80),
                new Series.Pair(Series.start(today.minusDays(7)), 100, 80),
                new Series.Pair(Series.start(today.minusDays(1)), 102, 80),
                new Series.Pair(start, 103, 80));
        List<Series.Point> weekly = Series.rolling(days, 2, Series.Frame.WEEKLY, today);
        check(weekly.size() == 1 && weekly.get(0).pair.time == start, "Last common close of each week");
        for (Series.Frame frame : Series.Frame.values()) {
            List<Series.Pair> sample = Series.sample(frame);
            LocalDate last = Series.day(sample.get(sample.size() - 1).time);
            List<Series.Point> view = Series.rolling(sample, 120, frame, last);
            check(!view.isEmpty(), "Sample supports max N: " + frame);
            for (Series.Point point : view) check(Double.isFinite(point.z), "Finite sample");
            check(sample.get(0).brent == Series.sample(frame).get(0).brent, "Sample is deterministic");
        }
        System.out.println("Rolling Z fixtures passed for all five timeframes");
    }
}
