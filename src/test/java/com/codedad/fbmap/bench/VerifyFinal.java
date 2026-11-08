package com.codedad.fbmap.bench;

import com.codedad.fbmap.FileBackedMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * 校验基准测试留下的数据文件仍然完整可用，并测量“打开已有文件”的开销（校验专项）。
 * 对账标记 / 统计属于组件内部信息，本专项按需用反射读取（见 BenchInternals）。
 */
public final class VerifyFinal {

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "build/bench-data/fbmap-1M.fbmap");

        long t = System.nanoTime();
        try (FileBackedMap m = new FileBackedMap(file)) {
            long openMs = (System.nanoTime() - t) / 1_000_000;

            long idxBytes = Files.size(BenchInternals.indexFile(m));
            long datBytes = Files.size(BenchInternals.dataFile(m));
            System.out.println("indexFile = " + BenchInternals.indexFile(m));
            System.out.println("dataFile  = " + BenchInternals.dataFile(m));
            System.out.println("indexBytes = " + idxBytes + " (" + idxBytes / 1048576 + " MB)");
            System.out.println("dataBytes  = " + datBytes + " (" + datBytes / 1048576 + " MB)");
            System.out.println("totalBytes = " + (idxBytes + datBytes) + " (" + (idxBytes + datBytes) / 1048576 + " MB)");
            System.out.println("openTime  = " + openMs + " ms");
            System.out.println("size      = " + m.size());
            System.out.println("recovered = " + BenchInternals.recoveredFromCrash(m)
                    + "  dropped = " + BenchInternals.droppedOnRecovery(m));
            System.out.println("stats     = " + BenchInternals.statsText(m).replace("\n  ", "\n            "));

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
    }
}
