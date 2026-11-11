package com.codedad.fbmap.bench;

import com.codedad.fbmap.FileBackedMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;

/**
 * FileBackedMap 性能测试。
 *
 * 样本：1,000,000 条键值对，键与值长度在 [100, 1000] 字符之间随机。
 * 阶段：1) 100 万次插入  2) 100 万次随机修改  3) 100 万次随机查找。
 *
 * 运行：
 *   java -Xmx256m -cp build fbmap.Benchmark map      build/bench-data   # 本组件
 *   java -Xmx8g   -cp build fbmap.Benchmark baseline build/bench-data   # HashMap 对照
 */
public final class Benchmark {

    private static final int N = Integer.getInteger("bench.n", 1_000_000);

    private static final long SEED_INSERT = 0xA1B2C3D4E5F60718L;
    private static final long SEED_UPDATE = 0x1122334455667788L;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "map";
        Path dir = Path.of(args.length > 1 ? args[1] : "build/bench-data");
        Files.createDirectories(dir);

        if ("baseline".equals(mode)) {
            baseline(dir);
        } else {
            runMap(dir);
        }
    }

    // ------------------------------------------------------------------
    // 内存测量（堆内 managed memory；GC 后读取，剔除我们自己的统计数组）
    // ------------------------------------------------------------------

    private static long heapUsed() {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        try {
            Thread.sleep(80);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    /** 后台采样堆占用峰值（不触发 GC，反映运行中的真实活数据量）。 */
    private static final class HeapMonitor extends Thread {
        private volatile long peak = 0;
        private volatile boolean running = true;

        HeapMonitor() {
            setDaemon(true);
            setName("heap-monitor");
        }

        @Override
        public void run() {
            Runtime rt = Runtime.getRuntime();
            while (running) {
                long used = rt.totalMemory() - rt.freeMemory();
                if (used > peak) {
                    peak = used;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        long peak() {
            return peak;
        }

        void stopMonitor() {
            running = false;
            interrupt();
        }
    }

    private static long payloadBytes(int count) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += SampleGen.keyFor(i).getBytes(StandardCharsets.UTF_8).length;
            sum += SampleGen.valueFor(SEED_INSERT, i).getBytes(StandardCharsets.UTF_8).length;
        }
        return sum;
    }

    // ------------------------------------------------------------------
    // 主测试
    // ------------------------------------------------------------------

    private static void runMap(Path dir) throws Exception {
        Path file = dir.resolve("fbmap-1M.fbmap");
        Files.deleteIfExists(file);
        Files.deleteIfExists(dir.resolve("fbmap-1M.fbmap.data"));
        Files.deleteIfExists(dir.resolve("fbmap-1M.fbmap.index.tmp"));
        Files.deleteIfExists(dir.resolve("fbmap-1M.fbmap.data.tmp"));

        long[] lat = new long[N];          // 延迟统计数组（8 MB，不属于被测组件）
        long heapStart = heapUsed();       // 已包含统计数组，因此后续 delta 只反映组件
        long maxHeap = Runtime.getRuntime().maxMemory();

        HeapMonitor monitor = new HeapMonitor();
        monitor.start();

        System.out.println("=== FileBackedMap 性能测试 ===");
        System.out.println("N=" + N + "，键/值长度 [" + SampleGen.MIN_LEN + "," + SampleGen.MAX_LEN + "] 字符");
        System.out.println("JVM 堆上限 = " + maxHeap / 1048576 + " MB，起始堆占用 = " + heapStart / 1048576 + " MB");
        System.out.println("样本键值负载（UTF-8 原始字节，单侧合计） = " + payloadBytes(N) / 1048576 + " MB");
        System.out.println();

        try (FileBackedMap map = new FileBackedMap(file)) {
            // ---------- 阶段 1：插入 ----------
            int rebuilds = 0;
            int lastSlots = BenchInternals.slots(map);
            long t = System.nanoTime();
            long phaseStart = t;
            for (int i = 0; i < N; i++) {
                String k = SampleGen.keyFor(i);
                String v = SampleGen.valueFor(SEED_INSERT, i);
                long s = System.nanoTime();
                map.put(k, v);
                lat[i] = System.nanoTime() - s;
                if (i % 100_000 == 99_999) {
                    int slots = BenchInternals.slots(map);
                    if (slots != lastSlots) {
                        rebuilds++;
                        lastSlots = slots;
                    }
                    System.out.printf("  insert %7d / %d   %.2fs%n", i + 1, N,
                            (System.nanoTime() - phaseStart) / 1e9);
                }
            }
            double insertSec = (System.nanoTime() - t) / 1e9;
            dumpPhase("INSERT", N, insertSec, lat, map, heapStart, heapUsed(), rebuilds, monitor.peak());

            // ---------- 阶段 2：随机修改 ----------
            Random pick = new Random(0x5DEECE66DL);
            int updated = 0;
            rebuilds = 0;
            lastSlots = BenchInternals.slots(map);
            t = System.nanoTime();
            phaseStart = t;
            for (int i = 0; i < N; i++) {
                int idx = pick.nextInt(N);
                String k = SampleGen.keyFor(idx);
                String v = SampleGen.valueFor(SEED_UPDATE, i);
                long s = System.nanoTime();
                String old = map.put(k, v);
                lat[i] = System.nanoTime() - s;
                if (old != null) {
                    updated++;
                }
                if (i % 100_000 == 99_999) {
                    int slots = BenchInternals.slots(map);
                    if (slots != lastSlots) {
                        rebuilds++;
                        lastSlots = slots;
                    }
                    System.out.printf("  update %7d / %d   %.2fs%n", i + 1, N,
                            (System.nanoTime() - phaseStart) / 1e9);
                }
            }
            double updateSec = (System.nanoTime() - t) / 1e9;
            dumpPhase("UPDATE", N, updateSec, lat, map, heapStart, heapUsed(), rebuilds, monitor.peak());
            System.out.println("  改值命中（键已存在）= " + updated + " / " + N);
            System.out.println();

            // ---------- 阶段 3：随机查找 ----------
            Random pick2 = new Random(0xDEADBEEFL);
            int hits = 0;
            t = System.nanoTime();
            phaseStart = t;
            for (int i = 0; i < N; i++) {
                int idx = pick2.nextInt(N);
                String k = SampleGen.keyFor(idx);
                long s = System.nanoTime();
                String v = map.get(k);
                lat[i] = System.nanoTime() - s;
                if (v != null) {
                    hits++;
                }
                if (i % 100_000 == 99_999) {
                    System.out.printf("  lookup %7d / %d   %.2fs%n", i + 1, N,
                            (System.nanoTime() - phaseStart) / 1e9);
                }
            }
            double lookupSec = (System.nanoTime() - t) / 1e9;
            dumpPhase("LOOKUP", N, lookupSec, lat, map, heapStart, heapUsed(), 0, monitor.peak());
            System.out.println("  查找命中 = " + hits + " / " + N);

            long indexBytes = BenchInternals.indexBytes(map);
            long dataBytes = BenchInternals.dataBytes(map);
            long total = indexBytes + dataBytes;
            System.out.println();
            System.out.println("FINAL size      = " + map.size());
            System.out.println("FINAL index     = " + BenchInternals.indexFile(map));
            System.out.println("FINAL data      = " + BenchInternals.dataFile(map));
            System.out.println("FINAL indexBytes = " + indexBytes + "  (" + indexBytes / 1048576 + " MB)");
            System.out.println("FINAL dataBytes  = " + dataBytes + "  (" + dataBytes / 1048576 + " MB)");
            System.out.println("FINAL totalBytes = " + total + "  (" + total / 1048576 + " MB)");
            System.out.println("FINAL slots=" + BenchInternals.slots(map) + " entries=" + BenchInternals.entries(map)
                    + " tombstones=" + BenchInternals.tombstones(map) + " garbage=" + BenchInternals.garbageBytes(map) + " B");
            System.out.println("FINAL load      = " + String.format("%.3f", BenchInternals.loadFactor(map)));
            System.out.println("FINAL heap      = " + heapUsed() / 1048576 + " MB (start "
                    + heapStart / 1048576 + " MB)");
            System.out.println("FINAL heapPeak  = " + monitor.peak() / 1048576 + " MB");
            monitor.stopMonitor();
        }
        monitor.stopMonitor();
    }

    private static void dumpPhase(String name, int n, double sec, long[] lat,
                                  FileBackedMap map, long heapStart, long heapNow, int rebuilds, long heapPeak) {
        long[] copy = lat.clone();
        Arrays.sort(copy);
        double opsPerSec = n / sec;
        double avgUs = sec * 1e6 / n;
        System.out.printf("%s: %.3f s  ->  %,d ops/s  |  avg %.2f us  p50 %.2f us  p95 %.2f us  p99 %.2f us  p999 %.2f us  max %,.0f us%n",
                name, sec, Math.round(opsPerSec), avgUs,
                copy[(int) (n * 0.50)] / 1000.0,
                copy[(int) (n * 0.95)] / 1000.0,
                copy[(int) (n * 0.99)] / 1000.0,
                copy[(int) Math.min(n - 1, (long) (n * 0.999))] / 1000.0,
                copy[n - 1] / 1000.0);

        long indexBytes = BenchInternals.indexBytes(map);
        long dataBytes = BenchInternals.dataBytes(map);
        System.out.printf("  index=%d bytes (%.1f MB)  data=%d bytes (%.1f MB)  total=%.1f MB%n",
                indexBytes, indexBytes / 1048576.0,
                dataBytes, dataBytes / 1048576.0,
                (indexBytes + dataBytes) / 1048576.0);
        System.out.printf("  garbage=%.1f MB  slots=%d entries=%d tomb=%d load=%.3f%n",
                BenchInternals.garbageBytes(map) / 1048576.0,
                BenchInternals.slots(map), BenchInternals.entries(map), BenchInternals.tombstones(map), BenchInternals.loadFactor(map));
        System.out.printf("  heapNow=%d MB  heapDelta=%d MB  heapPeak=%d MB  rebuilds=%d%n",
                heapNow / 1048576, (heapNow - heapStart) / 1048576, heapPeak / 1048576, rebuilds);
    }

    // ------------------------------------------------------------------
    // HashMap 对照
    // ------------------------------------------------------------------

    private static void baseline(Path dir) throws Exception {
        System.out.println("=== HashMap 对照测试（同样 100 万条、同样样本） ===");
        long[] lat = new long[N];
        long heap0 = heapUsed();

        HashMap<String, String> m = new HashMap<>(N * 2);
        long t = System.nanoTime();
        for (int i = 0; i < N; i++) {
            m.put(SampleGen.keyFor(i), SampleGen.valueFor(SEED_INSERT, i));
        }
        double sec = (System.nanoTime() - t) / 1e9;
        long heapAfterInsert = heapUsed();
        System.out.printf("INSERT: %.3f s -> %,d ops/s%n", sec, Math.round(N / sec));
        System.out.printf("  heap=%d MB  heapDelta=%d MB  size=%d%n",
                heapAfterInsert / 1048576, (heapAfterInsert - heap0) / 1048576, m.size());

        Random pick = new Random(0x5DEECE66DL);
        t = System.nanoTime();
        for (int i = 0; i < N; i++) {
            m.put(SampleGen.keyFor(pick.nextInt(N)), SampleGen.valueFor(SEED_UPDATE, i));
        }
        sec = (System.nanoTime() - t) / 1e9;
        System.out.printf("UPDATE: %.3f s -> %,d ops/s  heap=%d MB%n",
                sec, Math.round(N / sec), heapUsed() / 1048576);

        Random pick2 = new Random(0xDEADBEEFL);
        int hits = 0;
        t = System.nanoTime();
        for (int i = 0; i < N; i++) {
            if (m.get(SampleGen.keyFor(pick2.nextInt(N))) != null) {
                hits++;
            }
        }
        sec = (System.nanoTime() - t) / 1e9;
        System.out.printf("LOOKUP: %.3f s -> %,d ops/s  hits=%d  heap=%d MB%n",
                sec, Math.round(N / sec), hits, heapUsed() / 1048576);

        lat[0] = 0; // 避免未使用告警
    }
}
