package dev.rollingz.oilusd;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

final class Series {
    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    enum Frame {
        DAILY("Daily", "1d", "5y", 0, 140),
        WEEKLY("Weekly", "1d", "5y", 0, 90),
        FIVE("5m", "5m", "1mo", 300, 240),
        HOUR("1h", "60m", "3mo", 3600, 160),
        TODAY("Today", "5m", "1mo", 300, Integer.MAX_VALUE);

        final String label, interval, range;
        final int seconds, visible;
        Frame(String label, String interval, String range, int seconds, int visible) {
            this.label = label;
            this.interval = interval;
            this.range = range;
            this.seconds = seconds;
            this.visible = visible;
        }

        String key() { return interval; }
    }

    static final class Pair {
        final long time;
        final double dxy, brent;
        Pair(long time, double dxy, double brent) {
            this.time = time;
            this.dxy = dxy;
            this.brent = brent;
        }

        double differential() { return 100 * Math.log(dxy / brent); }
    }

    static final class Point {
        final Pair pair;
        final double z;
        Point(Pair pair, double z) {
            this.pair = pair;
            this.z = z;
        }
    }

    static LocalDate day(long seconds) {
        return Instant.ofEpochSecond(seconds).atZone(NEW_YORK).toLocalDate();
    }

    static long start(LocalDate date) {
        return date.atStartOfDay(NEW_YORK).toEpochSecond();
    }

    static long bucket(long timestamp, Frame frame) {
        return frame.seconds == 0 ? start(day(timestamp))
                : Math.floorDiv(timestamp, frame.seconds) * frame.seconds;
    }

    static List<Pair> align(NavigableMap<Long, Double> dxy, Map<Long, Double> brent) {
        List<Pair> aligned = new ArrayList<>();
        for (Map.Entry<Long, Double> bar : dxy.entrySet()) {
            Double oil = brent.get(bar.getKey());
            if (oil != null) aligned.add(new Pair(bar.getKey(), bar.getValue(), oil));
        }
        return aligned;
    }

    static List<Point> rolling(List<Pair> pairs, int n, Frame frame, LocalDate today) {
        if (frame == Frame.WEEKLY) {
            Map<LocalDate, Pair> weeks = new LinkedHashMap<>();
            for (Pair pair : pairs) {
                LocalDate monday = day(pair.time).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                weeks.put(monday, pair);
            }
            pairs = new ArrayList<>(weeks.values());
        }
        double[] spread = new double[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) spread[i] = pairs.get(i).differential();

        List<Point> points = new ArrayList<>();
        int first = Math.max(n - 1, pairs.size() - frame.visible);
        for (int i = first; i < pairs.size(); i++) {
            Pair pair = pairs.get(i);
            if (frame == Frame.TODAY && !day(pair.time).equals(today)) continue;
            double mean = 0;
            int from = i - n + 1;
            for (int j = from; j <= i; j++) mean += spread[j];
            mean /= n;
            double variance = 0;
            for (int j = from; j <= i; j++) {
                double delta = spread[j] - mean;
                variance += delta * delta;
            }
            double sigma = Math.sqrt(variance / n);
            points.add(new Point(pair, sigma < 1e-10 ? 0 : (spread[i] - mean) / sigma));
        }
        return points;
    }

    static List<Pair> sample(Frame frame) {
        LocalDate end = LocalDate.of(2026, 1, 14);
        int days = frame.seconds == 0 ? 1240 : 32;
        List<Pair> pairs = new ArrayList<>();
        Random random = new Random(1481);
        int index = 0;
        for (LocalDate date = end.minusDays(days); !date.isAfter(end); date = date.plusDays(1)) {
            if (date.getDayOfWeek().getValue() >= 6) continue;
            if (frame.seconds == 0) {
                pairs.add(samplePair(start(date), index++, random));
            } else {
                long open = date.atTime(8, 0).atZone(NEW_YORK).toEpochSecond();
                for (int offset = 0; offset < 9 * 3600; offset += 300) {
                    Pair pair = samplePair(open + offset, index++, random);
                    if ((offset + 300) % frame.seconds == 0)
                        pairs.add(new Pair(bucket(pair.time, frame), pair.dxy, pair.brent));
                }
            }
        }
        return pairs;
    }

    private static Pair samplePair(long time, int i, Random random) {
        double dxy = 101.4 + 2.1 * Math.sin(i / 31.0) + .58 * Math.sin(i / 6.0)
                + random.nextGaussian() * .12;
        double brent = 76 + 7 * Math.sin(i / 43.0 + .7) + 2 * Math.sin(i / 10.0)
                + random.nextGaussian() * .3;
        return new Pair(time, dxy, brent);
    }
}
