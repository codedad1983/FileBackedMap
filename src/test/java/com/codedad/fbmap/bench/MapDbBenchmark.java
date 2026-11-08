package com.codedad.fbmap.bench;

import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/**
 * MapDB 性能测试 —— 与 {@link Benchmark} 完全相同的场景，用作 FileBackedMap 的对照组。
 *
 * 样本：1,000,000 条键值对，键与值长度在 [100, 1000] 字符之间随机（同一个 SampleGen）。
 * 阶段：1) 100 万次插入  2) 100 万次随机修改  3) 100 万次随机查找。
 * 延迟分位数统计、堆监控与 FileBackedMap 版一致。注意：MapDB 的 mmap 数据页不进
 * Java 堆，其内存成本体现在进程物理内存上，对比时需同时看堆占用与 DB 文件大小。
 *
 * 对照组配置刻意贴近 FileBackedMap 的使用方式：单个 DB 文件、非事务（MapDB 3.1 默认）、
 * 数据全在磁盘上、不驻留内存 —— 即 MapDB 官方推荐的堆外大 Map 用法：
 *   DBMaker.fileDB(f).fileMmapEnableIfSupported().closeOnJvmShutdown()
 *
 * 运行：
 *   java -Xmx256m -cp target/classes;target/test-classes;...deps... \
 *        com.codedad.fbmap.bench.MapDbBenchmark mapdb build/bench-data
 *   （-Xmx256m 与 FileBackedMap 基准一致，同时用于证明其不需要大堆；
 *     对照组若需大堆可自行放大）
 */
public final class MapDbBenchmark {

    private static final int N = Integer.getInteger("bench.n", 1_000_000);

    private static final long SEED_INSERT = 0xA1B2C3D4E5F60718L;
    private static final long SEED_UPDATE = 0x1122334455667788L;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[1] : "build/bench-data");
        Files.createDirectories(dir);
        runMapDb(dir);
    }

    // ------------------------------------------------------------------
    // 内存测量（与 Benchmark.java 相同口径）
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

    // ------------------------------------------------------------------
    // 主测试
    // ------------------------------------------------------------------

    private static void runMapDb(Path dir) throws Exception {
        Path file = dir.resolve("mapdb-1M.dbf");
        deleteDbFiles(dir, "mapdb-1M.dbf");

        long[] lat = new long[N];
        long heapStart = heapUsed();
        long maxHeap = Runtime.getRuntime().maxMemory();

        HeapMonitor monitor = new HeapMonitor();
        monitor.start();

        System.out.println("=== MapDB 性能测试（对照组，与 FileBackedMap 相同场景） ===");
        System.out.println("N=" + N + "，键/值长度 [" + SampleGen.MIN_LEN + "," + SampleGen.MAX_LEN + "] 字符");
        System.out.println("JVM 堆上限 = " + maxHeap / 1048576 + " MB，起始堆占用 = " + heapStart / 1048576 + " MB");
        System.out.println();

        try (DB db = DBMaker.fileDB(file.toFile())
                // MapDB 3.1 默认即非事务（3.0 的 transactionDisable 已移除），
                // 与 FileBackedMap 的逐写语义对齐
                .fileMmapEnableIfSupported()     // 堆外访问（官方推荐的大 Map 用法）
                .closeOnJvmShutdown()
                .make()) {

            HTreeMap<String, String> map = db.hashMap("m", Serializer.STRING, Serializer.STRING)
                    .createOrOpen();

            // ---------- 阶段 1：插入 ----------
            long t = System.nanoTime();
            long phaseStart = t;
            for (int i = 0; i < N; i++) {
                String k = SampleGen.keyFor(i);
                String v = SampleGen.valueFor(SEED_INSERT, i);
                long s = System.nanoTime();
                map.put(k, v);
                lat[i] = System.nanoTime() - s;
                if (i % 100_000 == 99_999) {
                    System.out.printf("  insert %7d / %d   %.2fs%n", i + 1, N,
                            (System.nanoTime() - phaseStart) / 1e9);
                }
            }
            double insertSec = (System.nanoTime() - t) / 1e9;
            db.commit();
            dumpPhase("INSERT", N, insertSec, lat, heapStart, heapUsed(), monitor.peak());

            // ---------- 阶段 2：随机修改 ----------
            Random pick = new Random(0x5DEECE66DL);
            int updated = 0;
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
                    System.out.printf("  update %7d / %d   %.2fs%n", i + 1, N,
                            (System.nanoTime() - phaseStart) / 1e9);
                }
            }
            double updateSec = (System.nanoTime() - t) / 1e9;
            db.commit();
            dumpPhase("UPDATE", N, updateSec, lat, heapStart, heapUsed(), monitor.peak());
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
            dumpPhase("LOOKUP", N, lookupSec, lat, heapStart, heapUsed(), monitor.peak());
            System.out.println("  查找命中 = " + hits + " / " + N);

            System.out.println();
            System.out.println("FINAL size       = " + map.sizeLong());
            System.out.println("FINAL dbFile     = " + file);
            System.out.println("FINAL dbFileSize = " + Files.size(file) + "  ("
                    + Files.size(file) / 1048576 + " MB)");
            System.out.println("FINAL heap       = " + heapUsed() / 1048576 + " MB (start "
                    + heapStart / 1048576 + " MB)");
            System.out.println("FINAL heapPeak   = " + monitor.peak() / 1048576 + " MB");
            monitor.stopMonitor();
        }
        monitor.stopMonitor();
    }

    private static void dumpPhase(String name, int n, double sec, long[] lat,
                                  long heapStart, long heapNow, long heapPeak) {
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
        System.out.printf("  heapNow=%d MB  heapDelta=%d MB  heapPeak=%d MB%n",
                heapNow / 1048576, (heapNow - heapStart) / 1048576, heapPeak / 1048576);
    }

    /** MapDB 会伴生 .dbf.t / .dbf_wal 等文件，运行前全部清掉。 */
    private static void deleteDbFiles(Path dir, String base) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, base + "*")) {
            for (Path p : ds) {
                Files.deleteIfExists(p);
            }
        }
    }
}
