package com.codedad.fbmap.bench;

import com.codedad.fbmap.FileBackedMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * 专门测量「扩容」这一步的代价（性能专项）。
 *
 * 索引驻留内存：扩容 = 纯内存 rehash（三个数组重建 + 重分布），零 I/O，
 * 数据文件一个字节都不动。
 *
 * 做法：会话内先插入大量条目逼近负载阈值，继续插入新键直到负载触发一次扩容，
 * 记录触发扩容那一次 put 的耗时（它几乎全部是 rehash 开销），
 * 并对比扩容前后内存索引的载荷大小。
 *
 * 槽位数、索引/数据字节数属于组件内部信息，本专项按需用反射读取（见 BenchInternals）。
 */
public final class GrowBench {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "build/bench-data");
        Files.createDirectories(dir);
        Path file = dir.resolve("grow-bench.fbmap");
        Files.deleteIfExists(file);

        final int BASE = 900_000;

        try (FileBackedMap map = new FileBackedMap(file)) {
            long t0 = System.nanoTime();
            for (int i = 0; i < BASE; i++) {
                map.put(SampleGen.keyFor(i), SampleGen.valueFor(0xA1B2C3D4E5F60718L, i));
            }
            double loadSec = (System.nanoTime() - t0) / 1e9;

            int slotsBefore = BenchInternals.slots(map);
            int entriesBefore = BenchInternals.entries(map);
            long indexBytesBefore = BenchInternals.indexBytes(map);
            long dataBytesBefore = BenchInternals.dataBytes(map);
            System.out.println("基准文件 = " + file.toAbsolutePath());
            System.out.println("数据文件大小 = " + Files.size(BenchInternals.dataFile(map)) + " B");
            System.out.println();
            System.out.printf("先插入 %d 条（%.2f s）。初始状态: slots=%d entries=%d load=%.3f index(mem)=%d B  data=%d B%n",
                    BASE, loadSec, slotsBefore, entriesBefore,
                    BenchInternals.loadFactor(map), indexBytesBefore, dataBytesBefore);

            long growLatency = -1;
            int slotsAfter = -1;
            long indexBytesAfter = -1;
            long dataBytesAfterGrow = -1;
            int inserted = 0;
            long t = System.nanoTime();

            int i = entriesBefore;
            while (true) {
                long s = System.nanoTime();
                map.put(SampleGen.keyFor(i), SampleGen.valueFor(0x5A5A5A5AL, i));
                long d = System.nanoTime() - s;
                inserted++;
                if (BenchInternals.slots(map) != slotsBefore) {
                    growLatency = d;
                    slotsAfter = BenchInternals.slots(map);
                    indexBytesAfter = BenchInternals.indexBytes(map);
                    dataBytesAfterGrow = BenchInternals.dataBytes(map);
                    break;
                }
                i++;
            }
            double totalSec = (System.nanoTime() - t) / 1e9;

            System.out.printf("为触发扩容额外插入了 %d 条新键（总耗时 %.2f s）%n", inserted, totalSec);
            System.out.printf("触发扩容的那一次 put 耗时 = %.3f ms（纯内存 rehash，零 I/O）%n", growLatency / 1e6);
            System.out.println();
            System.out.println("扩容前: slots=" + slotsBefore + "  index(mem)=" + indexBytesBefore + " B");
            System.out.println("扩容后: slots=" + slotsAfter + "  index(mem)=" + indexBytesAfter + " B");
            System.out.println("扩容期间数据文件增长 = " + (dataBytesAfterGrow - dataBytesBefore) + " B"
                    + "  (只是本轮新增的 " + inserted + " 条记录，没有任何搬移/重写)");
            System.out.println("数据区总大小 = " + dataBytesAfterGrow + " B  ("
                    + dataBytesAfterGrow / 1048576 + " MB，扩容全程未被重写)");

            // 扩容后数据仍然正确：抽样核对新增键与原有键
            Random r = new Random(11);
            int miss = 0;
            for (int k = 0; k < 300; k++) {
                int idx = r.nextInt(map.size());
                if (map.get(SampleGen.keyFor(idx)) == null) {
                    miss++;
                }
            }
            System.out.println("扩容后抽样查找命中 = " + (300 - miss) + " / 300，size = " + map.size());
        }
    }
}
