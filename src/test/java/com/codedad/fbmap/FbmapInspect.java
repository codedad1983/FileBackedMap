package com.codedad.fbmap;

import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * 测试专用白盒门面（注意：位于 src/test/java，只编译进 target/test-classes，
 * 绝不进入发布 jar）。
 *
 * FileBackedMap 对外只暴露 {@code java.util.Map} 接口方法 + {@code close()}，
 * 其余方法（文件路径、世代号、对账标记、统计、compact、flush）全部是 private。
 * private 成员连同包都不可见，因此这里用反射读取——组件与测试同在 classpath
 * 的无名模块，setAccessible 合法。反射只存在于测试侧，生产代码零反射。
 *
 * 任何一步反射失败都直接抛 AssertionError（说明生产类签名变了，测试该同步更新）。
 */
public final class FbmapInspect {

    private final FileBackedMap map;

    public FbmapInspect(FileBackedMap map) {
        this.map = map;
    }

    // ---- 反射骨架 ----

    private static Object callNoArg(Class<?> owner, Object target, String name) {
        try {
            Method m = owner.getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("反射调用 " + owner.getSimpleName() + "#" + name + " 失败", e);
        }
    }

    private Object mapValue(String name) {
        return callNoArg(map.getClass(), map, name);
    }

    /** 每次现取一份新鲜 Stats 快照（stats() 返回瞬时值，跨 compact / 写操作不能缓存）。 */
    private Object statValue(String name) {
        Object statsObj = callNoArg(map.getClass(), map, "stats");
        return callNoArg(statsObj.getClass(), statsObj, name);
    }

    // ---- 运维信息（原 FileBackedMap 的 private 访问器） ----

    /** 索引文件路径。 */
    public Path indexFile() {
        return (Path) mapValue("path");
    }

    /** 当前世代的数据文件路径。 */
    public Path dataFile() {
        return (Path) mapValue("dataPath");
    }

    /** 当前数据文件世代号（0 = 初始 .data，重建后递增）。 */
    public int dataGeneration() {
        return (int) mapValue("dataGeneration");
    }

    /** 本次打开是否做过崩溃对账。 */
    public boolean recoveredFromCrash() {
        return (boolean) mapValue("recoveredFromCrash");
    }

    /** 对账时被丢弃的悬空槽位数量。 */
    public int droppedOnRecovery() {
        return (int) mapValue("droppedOnRecovery");
    }

    /** 手动整理（重写数据区、回收垃圾）。 */
    public void compact() {
        callNoArg(map.getClass(), map, "compact");
    }

    /** 手动落盘（数据 → 索引）。 */
    public void flush() {
        callNoArg(map.getClass(), map, "flush");
    }

    // ---- 统计字段的扁平访问器（Stats 是 private 嵌套 record） ----

    public int slots() {
        return (int) statValue("slots");
    }

    public int entries() {
        return (int) statValue("entries");
    }

    public int tombstones() {
        return (int) statValue("tombstones");
    }

    public long indexBytes() {
        return (long) statValue("indexBytes");
    }

    public long dataBytes() {
        return (long) statValue("dataBytes");
    }

    public long garbageBytes() {
        return (long) statValue("garbageBytes");
    }

    public double loadFactor() {
        return (double) statValue("loadFactor");
    }

    public boolean dirty() {
        return (boolean) statValue("dirty");
    }

    /** 统计信息的多行文本（仅用于 bench / demo 打印）。 */
    public String statsText() {
        Object statsObj = callNoArg(map.getClass(), map, "stats");
        return statsObj.toString();
    }
}
