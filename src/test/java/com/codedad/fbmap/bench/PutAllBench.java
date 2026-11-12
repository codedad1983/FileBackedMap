package com.codedad.fbmap.bench;

import com.codedad.fbmap.FileBackedMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * putAll 批量插入专项测量 —— 与 {@link Benchmark} 的逐条插入对照。
 *
 * 同一 SampleGen 样本、同分位数口径；插入按批执行（默认每批 1,000 条，
 * -Dbench.batch 可调）。每批的耗时均摊到该批的每条记录上，因此延迟数组
 * 与 Benchmark 的逐条口径可直接对比。
 *
 * 运行：
 *   java -Xmx256m -cp target/classes;target/test-classes;...deps... \
 *        com.codedad.fbmap.bench.PutAllBench build/bench-data
 */
public final class PutAllBench {

    private static final int N = Integer.getInteger("bench.n", 1_000_000);
    private static final int BATCH = Integer.getInteger("bench.batch", 1_000);

    /** 与 Benchmark 的插入阶段同一序列种子，保证样本逐条一致。 */
    private static final long SEED_INSERT = 0xA1B2C3D4E5F60718L;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "build/putall-data");
        Files.createDirectories(dir);
        Path file = dir.resolve("putall.fbmap");
        for (String suffix : new String[]{"", ".data", ".index.tmp", ".data.tmp"}) {
            Files.deleteIfExists(Path.of(file + suffix));
        }

        long[] lat = new long[N];
        System.out.println("=== putAll 批量插入专项（对照 Benchmark 逐条插入） ===");
        System.out.println("N=" + N + "，每批 " + BATCH + " 条，键/值长度 ["
                + SampleGen.MIN_LEN + "," + SampleGen.MAX_LEN + "] 字符");
        System.out.println();

        try (FileBackedMap map = new FileBackedMap(file)) {
            long t = System.nanoTime();
            long phaseStart = t;
            Map<String, String> batch = new LinkedHashMap<>(BATCH * 2);
            for (int i = 0; i < N; i++) {
                batch.put(SampleGen.keyFor(i), SampleGen.valueFor(SEED_INSERT, i));
                if (batch.size() == BATCH) {
                    long s = System.nanoTime();
                    map.putAll(batch);
                    long per = (System.nanoTime() - s) / BATCH;
                    for (int j = i - BATCH + 1; j <= i; j++) {
                        lat[j] = per;
                    }
                    batch.clear();
                    if ((i + 1) % 100_000 == 0) {
                        System.out.printf("  putAll %7d / %d   %.2fs%n", i + 1, N,
                                (System.nanoTime() - phaseStart) / 1e9);
                    }
                }
            }
            if (!batch.isEmpty()) {
                int rest = batch.size();
                long s = System.nanoTime();
                map.putAll(batch);
                long per = (System.nanoTime() - s) / rest;
                for (int j = N - rest; j < N; j++) {
                    lat[j] = per;
                }
            }
            double sec = (System.nanoTime() - t) / 1e9;

            long[] copy = lat.clone();
            java.util.Arrays.sort(copy);
            System.out.printf("PUTALL_INSERT: %.3f s  ->  %,d ops/s  |  avg %.2f us  p50 %.2f us  p95 %.2f us  p99 %.2f us  p999 %.2f us  max %,.0f us%n",
                    sec, Math.round(N / sec), sec * 1e6 / N,
                    copy[(int) (N * 0.50)] / 1000.0,
                    copy[(int) (N * 0.95)] / 1000.0,
                    copy[(int) (N * 0.99)] / 1000.0,
                    copy[Math.min(N - 1, (int) (N * 0.999))] / 1000.0,
                    copy[N - 1] / 1000.0);
            System.out.println("FINAL size = " + map.size());
        }
    }
}
