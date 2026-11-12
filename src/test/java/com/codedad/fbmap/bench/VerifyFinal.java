package com.codedad.fbmap.bench;

import com.codedad.fbmap.FileBackedMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * 基准数据自校验（校验专项）：会话内写入 N 条，逐条抽样核对读回一致，
 * 并测量「打开（取锁 + 清空）」的开销。
 *
 * 会话语义下没有「打开已有数据文件」——close() 删除文件、重开永远从空开始。
 * 「写入后能正确读出」由本专项在同一会话内验证。
 * 统计属于组件内部信息，本专项按需用反射读取（见 BenchInternals）。
 */
public final class VerifyFinal {

    public static void main(String[] args) throws Exception {
        int n = Integer.getInteger("bench.n", 1_000_000);
        Path dir = Path.of(args.length > 0 ? args[0] : "build/bench-data");
        Files.createDirectories(dir);
        Path file = dir.resolve("verify.fbmap");

        long t = System.nanoTime();
        try (FileBackedMap m = new FileBackedMap(file)) {
            long openMs = (System.nanoTime() - t) / 1_000_000;

            long t2 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                m.put(SampleGen.keyFor(i), SampleGen.valueFor(0xA1B2C3D4E5F60718L, i));
            }
            double insertSec = (System.nanoTime() - t2) / 1e9;

            long datBytes = Files.size(BenchInternals.dataFile(m));
            System.out.println("dataFile   = " + BenchInternals.dataFile(m));
            System.out.println("dataBytes  = " + datBytes + " (" + datBytes / 1048576 + " MB)");
            System.out.println("openTime   = " + openMs + " ms（取锁 + 清空）");
            System.out.printf("insertTime = %.3f s（%,d ops/s）%n", insertSec, Math.round(n / insertSec));
            System.out.println("size       = " + m.size());
            System.out.println("stats      = " + BenchInternals.statsText(m).replace("\n  ", "\n           "));

            Random r = new Random(1);
            int hit = 0;
            int sample = 1000;
            t = System.nanoTime();
            for (int i = 0; i < sample; i++) {
                if (m.get(SampleGen.keyFor(r.nextInt(m.size()))) != null) {
                    hit++;
                }
            }
            double us = (System.nanoTime() - t) / 1000.0 / sample;
            System.out.println("sampleHits = " + hit + " / " + sample + "  (" + String.format("%.1f", us) + " us/次)");
        }
        System.out.println("close 后文件存在 = " + Files.exists(file));
    }
}
