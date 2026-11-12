package com.codedad.fbmap.demo;

import com.codedad.fbmap.FileBackedMap;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 简单演示：如何把 FileBackedMap 当作普通 Map 使用，键值数据始终写在磁盘文件里，
 * 堆内存只承担一个可控大小的内存索引。
 *
 * 纯黑盒：只用 Map 接口 + close()，不反射、不触碰任何非公开方法；
 * 文件大小只用普通文件系统 API 观察（数据文件就是构造时传入的路径）。
 *
 * 会话语义：打开即清空同名旧文件，close() 删除文件——演示会展示这两点。
 */
public final class Demo {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "build/demo-data");
        Files.createDirectories(dir);
        Path file = dir.resolve("demo.fbmap");
        Files.deleteIfExists(file);

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
            System.out.println("数据文件 = " + Files.size(file) + " 字节");
        }

        // close() 已删除数据文件；重开是全新空会话（组件不做持久化）
        System.out.println("\nclose 后文件存在 = " + Files.exists(file));
        try (FileBackedMap map = new FileBackedMap(file)) {
            System.out.println("重新打开后 size = " + map.size() + "（会话作用域：旧数据不保留）");
        }

        demoHeapIndependence(dir);
    }

    /** 证明键值数据没有驻留堆内存：写入约 20 MB 数据，堆占用几乎不增长。 */
    private static void demoHeapIndependence(Path dir) throws Exception {
        Path file = dir.resolve("heap.fbmap");
        Files.deleteIfExists(file);

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
            System.out.println("  数据文件   = " + Files.size(file) / 1024 / 1024 + " MB");
            System.out.println("  堆占用增量 = " + (during - before) / 1024 + " KB  (仅索引数组与临时编码缓冲区)");
            System.out.println("  map.size() = " + map.size());
        }
    }

    private static long used(Runtime rt) {
        System.gc();
        return rt.totalMemory() - rt.freeMemory();
    }
}
