package com.codedad.fbmap.bench;

import com.codedad.fbmap.FbmapInspect;
import com.codedad.fbmap.FileBackedMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * 单独测量 "整理（compact）" 的代价：
 * 先制造大量垃圾（反复改值长度），再手动整理，观察耗时与文件体积变化。
 * compact 是组件的 private 运维方法，测试侧经 FbmapInspect（反射门面）调用。
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
            FbmapInspect in = new FbmapInspect(map);
            long t = System.nanoTime();
            for (int i = 0; i < n; i++) {
                map.put(SampleGen.keyFor(i), SampleGen.valueFor(SEED_UPDATE, i));
            }
            double insertSec = (System.nanoTime() - t) / 1e9;
            long afterInsert = Files.size(in.indexFile()) + Files.size(in.dataFile());

            Random pick = new Random(0x5DEECE66DL);
            t = System.nanoTime();
            for (int i = 0; i < n; i++) {
                map.put(SampleGen.keyFor(pick.nextInt(n)), SampleGen.valueFor(SEED_UPDATE + 1, i));
            }
            double updateSec = (System.nanoTime() - t) / 1e9;
            long afterUpdate = Files.size(in.indexFile()) + Files.size(in.dataFile());

            t = System.nanoTime();
            in.compact();
            double compactSec = (System.nanoTime() - t) / 1e9;
            long afterCompact = Files.size(in.indexFile()) + Files.size(in.dataFile());

            System.out.println("n = " + n);
            System.out.printf("insert  : %.3f s  (%.0f ops/s)%n", insertSec, n / insertSec);
            System.out.printf("update  : %.3f s  (%.0f ops/s)%n", updateSec, n / updateSec);
            System.out.printf("compact : %.3f s  (%.0f ops/s)%n", compactSec, n / compactSec);
            System.out.printf("file: insert=%d  update=%d  compact=%d bytes  (%d -> %d MB)%n",
                    afterInsert, afterUpdate, afterCompact,
                    afterInsert / 1048576, afterCompact / 1048576);
            System.out.println(in.statsText());
            System.out.println("size = " + map.size());
        }
    }

    private static void cleanGenerationFiles(Path dir, String base) throws Exception {
        Files.deleteIfExists(dir.resolve(base));
        Files.deleteIfExists(dir.resolve(base + ".index.tmp"));
        Files.deleteIfExists(dir.resolve(base + ".data"));
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir, base + ".data.g*")) {
            for (Path f : ds) {
                Files.deleteIfExists(f);
            }
        }
    }
}
