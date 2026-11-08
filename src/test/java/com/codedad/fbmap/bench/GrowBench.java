package com.codedad.fbmap.bench;

import com.codedad.fbmap.FileBackedMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * 专门测量「扩容」这一步的代价（性能专项），验证索引与数据分离的设计效果：
 * 扩容只重写槽位区（N × 16 B），不碰数据区（约 1 GB）。
 *
 * 做法：在已有 100 万条的数据文件上继续插入新键，直到负载触发一次扩容，
 * 记录触发扩容那一次 put 的耗时（它几乎全部是扩容开销），
 * 并对比扩容前后索引文件 / 数据文件的大小。
 *
 * 槽位数、索引/数据字节数属于组件内部信息，本专项按需用反射读取（见 BenchInternals）。
 */
public final class GrowBench {

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "build/bench-data/fbmap-1M.fbmap");

        try (FileBackedMap map = new FileBackedMap(file)) {
            int slotsBefore = BenchInternals.slots(map);
            int entriesBefore = BenchInternals.entries(map);
            long indexBytesBefore = BenchInternals.indexBytes(map);
            long dataBytesBefore = BenchInternals.dataBytes(map);
            System.out.println("基准文件 = " + file.toAbsolutePath());
            System.out.println("索引大小 = " + Files.size(BenchInternals.indexFile(map)) + " B");
            System.out.println("数据大小 = " + Files.size(BenchInternals.dataFile(map))
                    + " B  (gen " + BenchInternals.dataGeneration(map) + ")");
            System.out.println();
            System.out.println("初始状态: slots=" + slotsBefore + " entries=" + entriesBefore
                    + " load=" + String.format("%.3f", BenchInternals.loadFactor(map))
                    + " index=" + indexBytesBefore + " B  data=" + dataBytesBefore + " B");

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
            System.out.printf("触发扩容的那一次 put 耗时 = %.3f s %n", growLatency / 1e9);
            System.out.println();
            System.out.println("扩容前: slots=" + slotsBefore + "  index=" + indexBytesBefore + " B");
            System.out.println("扩容后: slots=" + slotsAfter + "  index=" + indexBytesAfter + " B");
            System.out.println("索引重写字节数 = " + (indexBytesAfter - indexBytesBefore) + " B"
                    + "  (" + (indexBytesAfter - indexBytesBefore) / 1048576 + " MB)");
            System.out.println("扩容期间数据文件增长 = " + (dataBytesAfterGrow - dataBytesBefore) + " B"
                    + "  (只是本轮新增的 " + inserted + " 条记录，没有任何搬移/重写)");
            System.out.println("数据区总大小 = " + dataBytesAfterGrow + " B  ("
                    + dataBytesAfterGrow / 1048576 + " MB，扩容全程未被重写)");
            System.out.printf("索引重写吞吐 = %.1f MB/s%n",
                    (indexBytesAfter - indexBytesBefore) / 1048576.0 / (growLatency / 1e9));

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
