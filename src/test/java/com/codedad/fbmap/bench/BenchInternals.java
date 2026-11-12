package com.codedad.fbmap.bench;

import com.codedad.fbmap.FileBackedMap;

import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * bench 性能专项测试专用的内部信息读取器（package-private，只服务本包基准程序）。
 *
 * FileBackedMap 对外只有 Map 接口 + close()，其余全是 private。性能报告需要展示
 * 槽位数、内存索引字节数等内部指标，黑盒 API 观察不到，
 * 因此这一「专项」按需用反射读取。它不是通用门面：常规正确性测试、
 * Demo 与任何正常使用都不使用它。反射失败直接抛 AssertionError（签名变化即报错）。
 */
final class BenchInternals {

    private BenchInternals() {
    }

    private static Object call(Object target, String name) {
        try {
            Method m = target.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("反射调用 " + target.getClass().getSimpleName()
                    + "#" + name + " 失败（生产类签名可能已变化）", e);
        }
    }

    /** 现取一份新鲜 Stats 快照并读字段（stats 是瞬时值，不能缓存）。 */
    private static Object stat(FileBackedMap m, String field) {
        Object statsObj = call(m, "stats");
        return call(statsObj, field);
    }

    static Path dataFile(FileBackedMap m) {
        return (Path) call(m, "dataPath");
    }

    static int slots(FileBackedMap m) {
        return (int) stat(m, "slots");
    }

    static int entries(FileBackedMap m) {
        return (int) stat(m, "entries");
    }

    static int tombstones(FileBackedMap m) {
        return (int) stat(m, "tombstones");
    }

    /** 内存索引载荷字节数（槽数 × 13 B），不是磁盘文件。 */
    static long indexBytes(FileBackedMap m) {
        return (long) stat(m, "indexBytes");
    }

    static long dataBytes(FileBackedMap m) {
        return (long) stat(m, "dataBytes");
    }

    static long garbageBytes(FileBackedMap m) {
        return (long) stat(m, "garbageBytes");
    }

    static double loadFactor(FileBackedMap m) {
        return (double) stat(m, "loadFactor");
    }

    static String statsText(FileBackedMap m) {
        return call(m, "stats").toString();
    }
}
