package com.codedad.fbmap.bench;

import com.codedad.fbmap.FileBackedMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * 单独测量「整理（compact）」的代价（性能专项）：
 * 先制造大量垃圾（反复改值长度），再触发整理，观察耗时与文件体积变化。
 * compact 是组件 private 运维方法，黑盒 API 无法调用，属于本专项按需用反射的场景
 * （见 BenchInternals）。
 */
public final class CompactBench {

    private static final long SEED_UPDATE = 0x1122334455667788L;

    public static void main(String[] args) throws Exception {
        int n = Integer.getInteger("bench.n", 300_000);
        Path dir = Path.of(args.length > 0 ? args[0] : "build/compact-data");
        Files.createDirectories(dir);
        Path file = dir.resolve("compact-test.fbmap");
        cleanGenerationFiles(dir, "compact-test.fbmap");

        try (FileBackedMap map = new FileBackedMap(file)) {
            long t = System.nanoTime();
            for (int i = 0; i < n; i++) {
                map.put(SampleGen.keyFor(i), SampleGen.valueFor(SEED_UPDATE, i));
            }
            double insertSec = (System.nanoTime() - t) / 1e9;
            long afterInsert = Files.size(BenchInternals.indexFile(map))
                    + Files.size(BenchInternals.dataFile(map));

            Random pick = new Random(0x5DEECE66DL);
            t = System.nanoTime();
            for (int i = 0; i < n; i++) {
                map.put(SampleGen.keyFor(pick.nextInt(n)), SampleGen.valueFor(SEED_UPDATE + 1, i));
            }
            double updateSec = (System.nanoTime() - t) / 1e9;
            long afterUpdate = Files.size(BenchInternals.indexFile(map))
                    + Files.size(BenchInternals.dataFile(map));

            t = System.nanoTime();
            BenchInternals.compact(map);
            double compactSec = (System.nanoTime() - t) / 1e9;
            long afterCompact = Files.size(BenchInternals.indexFile(map))
                    + Files.size(BenchInternals.dataFile(map));

            System.out.println("n = " + n);
            System.out.printf("insert  : %.3f s  (%.0f ops/s)%n", insertSec, n / insertSec);
            System.out.printf("update  : %.3f s  (%.0f ops/s)%n", updateSec, n / updateSec);
            System.out.printf("compact : %.3f s  (%.0f ops/s)%n", compactSec, n / compactSec);
            System.out.printf("file: insert=%d  update=%d  compact=%d bytes  (%d -> %d MB)%n",
                    afterInsert, afterUpdate, afterCompact,
                    afterInsert / 1048576, afterCompact / 1048576);
            System.out.println(BenchInternals.statsText(map));
            System.out.println("size = " + map.size());
        }
    }

    private static void cleanGenerationFiles(Path dir, String base) throws Exception {
        Files.deleteIfExists(dir.resolve(base));
        Files.deleteIfExists(dir.resolve(base + ".index.tmp"));
        Files.deleteIfExists(dir.resolve(base + ".data"));
        Files.deleteIfExists(dir.resolve(base + ".data.tmp"));
    }
}
