package com.codedad.fbmap.demo;

import com.codedad.fbmap.FileBackedMap;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 简单演示：如何把 FileBackedMap 当作普通 Map 使用，同时数据始终在磁盘上。
 * 纯黑盒：只用 Map 接口 + close()，不反射、不触碰任何非公开方法；
 * 文件大小只用普通文件系统 API 观察（索引路径是构造时传入的，
 * 数据文件按固定命名 <索引名>.data 直接访问）。
 */
public final class Demo {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "build/demo-data");
        Files.createDirectories(dir);
        Path file = dir.resolve("demo.fbmap");
        cleanPrevious(file);

        try (FileBackedMap map = new FileBackedMap(file)) {
            map.put("name", "张三");
            map.put("city", "杭州");
            map.put("note", "这是一条很长的值：".repeat(1000));
            map.put("empty", "");
            map.put(null, "null 键也支持");

            System.out.println("size      = " + map.size());
            System.out.println("get(name) = " + map.get("name"));
            System.out.println("get(note) 长度 = " + map.get("note").length());
            System.out.println("containsKey(null) = " + map.containsKey(null));

            System.out.println("\n遍历 entrySet:");
            for (var e : map.entrySet()) {
                String v = e.getValue();
                System.out.println("  " + e.getKey() + " -> "
                        + (v.length() > 30 ? v.substring(0, 30) + "..." : v));
            }

            map.remove("city");
            System.out.println("\nremove(city) 后 size = " + map.size());
            System.out.println("索引文件 = " + Files.size(file) + " 字节"
                    + "，数据文件 = " + dataSize(file) + " 字节");
        }

        // 重新打开，数据依然在
        try (FileBackedMap map = new FileBackedMap(file)) {
            System.out.println("\n重新打开后 size = " + map.size()
                    + "，get(name) = " + map.get("name"));
        }

        demoHeapIndependence(dir);
    }

    /** 证明键值数据没有驻留堆内存：写入约 20 MB 数据，堆占用几乎不增长。 */
    private static void demoHeapIndependence(Path dir) throws Exception {
        Path file = dir.resolve("heap.fbmap");
        cleanPrevious(file);

        Runtime rt = Runtime.getRuntime();
        long before = used(rt);

        int count = 20_000;
        String value = "x".repeat(1024);   // 每个 1 KB => 约 20 MB 数据
        try (FileBackedMap map = new FileBackedMap(file)) {
            for (int i = 0; i < count; i++) {
                map.put("key-" + i, value);
            }
            long during = used(rt);
            System.out.println("\n写入 " + count + " 条 × 1KB（约 20 MB 数据）");
            System.out.println("  索引文件   = " + Files.size(file) / 1024 / 1024 + " MB，数据文件 = "
                    + dataSize(file) / 1024 / 1024 + " MB");
            System.out.println("  堆占用增量 = " + (during - before) / 1024 + " KB  (仅临时编码缓冲区)");
            System.out.println("  map.size() = " + map.size());
        }
    }

    /** 数据文件字节数：固定命名 <索引名>.data，直接按名取，无需组件内部信息。 */
    private static long dataSize(Path indexFile) throws Exception {
        Path data = indexFile.resolveSibling(indexFile.getFileName() + ".data");
        return Files.exists(data) ? Files.size(data) : 0L;
    }

    private static long used(Runtime rt) {
        System.gc();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** 删除索引、数据文件与临时文件，避免跨次运行互相干扰。 */
    private static void cleanPrevious(Path indexFile) throws Exception {
        Path dir = indexFile.getParent();
        String base = indexFile.getFileName().toString();
        Files.deleteIfExists(indexFile);
        Files.deleteIfExists(dir.resolve(base + ".data"));
        Files.deleteIfExists(dir.resolve(base + ".index.tmp"));
        Files.deleteIfExists(dir.resolve(base + ".data.tmp"));
    }
}
