package com.codedad.fbmap.crash;

import com.codedad.fbmap.FileBackedMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 崩溃 / 异常落盘场景的对账测试（JUnit 5，专项测试）。
 *
 * 用「直接改字节」的方式构造出各种「只完成了一个文件的落盘」的状态，然后打开文件，
 * 验证组件不会返回错值，也不会把错误扩散出去：
 * <ol>
 *   <li>数据已追加但文件头还没更新（游标落后）——最危险的一种，旧实现会让下次追加覆盖活记录；</li>
 *   <li>槽位指向数据文件之外（悬空槽位）；</li>
 *   <li>槽位指向一条被截断的记录（撕裂记录）；</li>
 *   <li>文件头 CRC32 损坏；</li>
 *   <li>重建只写了新世代数据文件、还没来得及换索引（孤儿文件）；</li>
 *   <li>索引声明的世代数据文件缺失——必须明确报错，绝不静默降级。</li>
 * </ol>
 * 本类是崩溃安全「专项测试」：对账标记、世代号、垃圾统计、compact 等是组件 private 内部
 * 信息，黑盒 API 无法观察，因此这里按需用类内反射读取（生产代码与其他常规测试零反射）。
 * 运行：mvn test（surefire 自动执行）。
 */
final class CrashSafetyTest {

    private static final int H_FLAGS = 20;
    private static final int H_DATA_END = 24;
    private static final int H_DATA_GEN = 40;
    private static final int H_CRC = 48;
    private static final int HEADER_SIZE = 64;
    private static final int SLOT_SIZE = 16;

    @TempDir
    Path dir;

    // ------------------------------------------------------------------
    // 专项测试专用：类内反射读取 private 内部状态 / 触发 compact
    // ------------------------------------------------------------------

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

    /** 现取一份新鲜 Stats 快照并读取某个字段（stats 是瞬时值，不能缓存）。 */
    private static Object stat(FileBackedMap m, String field) {
        Object statsObj = call(m, "stats");
        return call(statsObj, field);
    }

    private static int dataGeneration(FileBackedMap m) {
        return (int) call(m, "dataGeneration");
    }

    private static boolean recoveredFromCrash(FileBackedMap m) {
        return (boolean) call(m, "recoveredFromCrash");
    }

    private static int droppedOnRecovery(FileBackedMap m) {
        return (int) call(m, "droppedOnRecovery");
    }

    private static Path dataFile(FileBackedMap m) {
        return (Path) call(m, "dataPath");
    }

    private static Path indexFile(FileBackedMap m) {
        return (Path) call(m, "path");
    }

    private static int tombstones(FileBackedMap m) {
        return (int) stat(m, "tombstones");
    }

    private static long garbageBytes(FileBackedMap m) {
        return (long) stat(m, "garbageBytes");
    }

    private static long dataBytes(FileBackedMap m) {
        return (long) stat(m, "dataBytes");
    }

    private static boolean dirty(FileBackedMap m) {
        return (boolean) stat(m, "dirty");
    }

    private static void compact(FileBackedMap m) {
        call(m, "compact");
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private static Path fresh(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        Files.deleteIfExists(p);
        Files.deleteIfExists(dir.resolve(name + ".index.tmp"));
        Files.deleteIfExists(dir.resolve(name + ".data"));
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, name + ".data.g*")) {
            for (Path f : ds) {
                Files.deleteIfExists(f);
            }
        }
        return p;
    }

    // ------------------------------------------------------------------
    // 直接操作文件头的辅助方法（模拟“只写了一半”的状态）
    // ------------------------------------------------------------------

    private static byte[] readAll(Path p) throws IOException {
        return Files.readAllBytes(p);
    }

    private static void writeAll(Path p, byte[] b) throws IOException {
        Files.write(p, b);
    }

    private static void recomputeCrc(byte[] headerBytes) {
        ByteBuffer bb = ByteBuffer.wrap(headerBytes);
        CRC32 c = new CRC32();
        c.update(headerBytes, 0, 48);
        bb.putInt(H_CRC, (int) c.getValue());
    }

    /** 模拟：数据已写入，但文件头还没来得及更新（崩溃点在 writeHeader 之前）。 */
    private static void patchHeader(Path indexPath, long dataEnd, int flags, int gen) throws IOException {
        byte[] b = readAll(indexPath);
        ByteBuffer bb = ByteBuffer.wrap(b);
        bb.putInt(H_FLAGS, flags);
        bb.putLong(H_DATA_END, dataEnd);
        bb.putInt(H_DATA_GEN, gen);
        recomputeCrc(b);
        writeAll(indexPath, b);
    }

    private static int occupiedSlotIndex(Path indexPath) throws IOException {
        byte[] b = readAll(indexPath);
        int slots = ByteBuffer.wrap(b).getInt(8);
        for (int i = 0; i < slots; i++) {
            int p = HEADER_SIZE + i * SLOT_SIZE;
            if (b[p] == 1) {
                return i;
            }
        }
        return -1;
    }

    private static void patchSlotOffset(Path indexPath, int slot, long offset) throws IOException {
        byte[] b = readAll(indexPath);
        int p = HEADER_SIZE + slot * SLOT_SIZE;
        ByteBuffer.wrap(b).putLong(p + 8, offset);
        recomputeCrc(b);
        writeAll(indexPath, b);
    }

    // ------------------------------------------------------------------
    // 用例 1：游标落后（追加了记录，但 dataEnd 没更新）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C1 数据已追加、文件头未更新：游标落后，下次追加不得覆盖活记录")
    void caseCursorBehind() throws IOException {
        Path p = fresh(dir, "cursor.fbmap");
        Map<String, String> ref = new HashMap<>();
        for (int i = 0; i < 120; i++) {
            String k = "k" + i;
            String v = "v" + i + "-" + "x".repeat(50 + i);
            ref.put(k, v);
        }
        try (FileBackedMap m = new FileBackedMap(p)) {
            ref.forEach(m::put);
            assertEquals(0, dataGeneration(m), "C1 世代应为 0");
        }

        long dataLen = Files.size(dir.resolve("cursor.fbmap.data"));
        // 伪造崩溃点：正常关闭之后再把头改回“游标落后 60 B”，并置脏标记
        patchHeader(p, dataLen - 60, 1, 0);

        try (FileBackedMap m = new FileBackedMap(p)) {
            assertTrue(recoveredFromCrash(m), "C1 应执行对账");
            assertEquals(0, droppedOnRecovery(m), "C1 不应丢弃任何槽位");
            assertEquals(ref.size(), m.size(), "C1 对账后 size");
            for (Map.Entry<String, String> e : ref.entrySet()) {
                assertEquals(e.getValue(), m.get(e.getKey()), "C1 对账后取值 " + e.getKey());
            }
            assertEquals(dataLen, dataBytes(m), "C1 对账后游标校准为实际文件长度");
            assertEquals(0L, garbageBytes(m), "C1 对账后无垃圾（所有字节都被活记录覆盖）");

            // 关键：对账后游标 = 实际文件长度，下一次 put 必须写在末尾，不得覆盖 k0 这条活记录
            m.put("NEW", "fresh-value");
            assertEquals("fresh-value", m.get("NEW"), "C1 新键可读");
            for (int i = 0; i < 120; i++) {
                assertEquals(ref.get("k" + i), m.get("k" + i), "C1 追加后旧键仍正确 k" + i);
            }
            assertTrue(Files.size(dataFile(m)) > dataLen, "C1 追加后数据文件变长（未覆盖）");
        }

        // 再打开一次（正常关闭后）确认依然一致
        try (FileBackedMap m = new FileBackedMap(p)) {
            assertEquals(121, m.size(), "C1 重开 size");
            assertEquals("fresh-value", m.get("NEW"), "C1 重开新键");
            assertEquals(ref.get("k119"), m.get("k119"), "C1 重开旧键");
        }
    }

    // ------------------------------------------------------------------
    // 用例 2：悬空槽位（指向数据文件之外）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C2 槽位指向数据文件之外：应转墓碑丢弃，绝不返回无法验证的内容")
    void caseDanglingSlot() throws IOException {
        Path p = fresh(dir, "dangling.fbmap");
        Map<String, String> ref = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            ref.put("d" + i, "value-" + i);
        }
        try (FileBackedMap m = new FileBackedMap(p)) {
            ref.forEach(m::put);
        }

        int slot = occupiedSlotIndex(p);
        assertTrue(slot >= 0, "C2 找到占用槽位");

        // 把其中一个槽位的偏移改到远远超出数据文件末尾
        patchSlotOffset(p, slot, Files.size(dir.resolve("dangling.fbmap.data")) + 999_999);
        patchHeader(p, Files.size(dir.resolve("dangling.fbmap.data")), 1, 0);

        try (FileBackedMap m = new FileBackedMap(p)) {
            assertTrue(recoveredFromCrash(m), "C2 应执行对账");
            assertEquals(1, droppedOnRecovery(m), "C2 应丢弃 1 个悬空槽位");
            assertEquals(ref.size() - 1, m.size(), "C2 对账后 size 减 1");
            assertEquals(0, tombstones(m), "C2 丢弃后应记为墓碑而非占用");
            int missing = 0;
            for (Map.Entry<String, String> e : ref.entrySet()) {
                String v = m.get(e.getKey());
                if (v == null) {
                    missing++;
                } else {
                    assertEquals(e.getValue(), v, "C2 其余键取值 " + e.getKey());
                }
            }
            assertEquals(1, missing, "C2 恰好 1 个键变成缺失（而不是返回错值）");
        }
    }

    // ------------------------------------------------------------------
    // 用例 3：撕裂记录（槽位已发布，但记录被截断）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C3 槽位指向被截断的记录：应丢弃，不得读出半条数据")
    void caseTornRecord() throws IOException {
        Path p = fresh(dir, "torn.fbmap");
        try (FileBackedMap m = new FileBackedMap(p)) {
            m.put("only", "y".repeat(500));
        }
        Path dp = dir.resolve("torn.fbmap.data");
        long len = Files.size(dp);

        // 截掉最后 200 字节：记录长度字段仍声称有 500 字节值，但文件不够长
        try (RandomAccessFile raf = new RandomAccessFile(dp.toFile(), "rw")) {
            raf.setLength(len - 200);
        }
        patchHeader(p, Files.size(dp), 1, 0);

        try (FileBackedMap m = new FileBackedMap(p)) {
            assertTrue(recoveredFromCrash(m), "C3 应执行对账");
            assertEquals(1, droppedOnRecovery(m), "C3 应丢弃 1 个不可验证的槽位");
            assertEquals(0, m.size(), "C3 对账后 size = 0");
            assertNull(m.get("only"), "C3 不得返回半条数据");
            assertEquals(0, tombstones(m), "C3 槽位应为墓碑且 entry=0");
        }
    }

    // ------------------------------------------------------------------
    // 用例 4：文件头 CRC 损坏
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C4 文件头 CRC32 损坏：应触发对账，而不是相信计数")
    void caseBadCrc() throws IOException {
        Path p = fresh(dir, "crc.fbmap");
        Map<String, String> ref = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            ref.put("c" + i, "val-" + i);
        }
        try (FileBackedMap m = new FileBackedMap(p)) {
            ref.forEach(m::put);
        }

        byte[] b = readAll(p);
        b[30] ^= 0x5A;                 // 破坏 header 内一个字节（garbageBytes 区域）
        writeAll(p, b);                // 不重算 CRC：模拟 header 被改但校验没过

        try (FileBackedMap m = new FileBackedMap(p)) {
            assertTrue(recoveredFromCrash(m), "C4 CRC 不匹配应触发对账");
            assertEquals(ref.size(), m.size(), "C4 对账后 size");
            for (Map.Entry<String, String> e : ref.entrySet()) {
                assertEquals(e.getValue(), m.get(e.getKey()), "C4 取值 " + e.getKey());
            }
        }
    }

    // ------------------------------------------------------------------
    // 用例 5：重建只写了新世代数据文件、索引还没换（孤儿文件）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C5 重建只落盘了数据文件、索引未提交：旧索引 + 旧数据仍可用")
    void caseOrphanGeneration() throws IOException {
        Path p = fresh(dir, "orphan.fbmap");
        Map<String, String> ref = new HashMap<>();
        for (int i = 0; i < 60; i++) {
            ref.put("o" + i, "vv-" + i + "-" + "z".repeat(30));
        }
        try (FileBackedMap m = new FileBackedMap(p)) {
            ref.forEach(m::put);
            assertEquals(0, dataGeneration(m), "C5 初始世代 0");
        }

        // 模拟：rebuild 已经把新数据写进 g1 并 fsync，但进程在重命名索引之前崩溃
        Path g1 = dir.resolve("orphan.fbmap.data.g1");
        Files.write(g1, new byte[1234]);        // 内容无意义，只是“已写了一半的新数据文件”

        try (FileBackedMap m = new FileBackedMap(p)) {
            assertEquals(0, dataGeneration(m), "C5 仍使用旧世代");
            assertEquals(ref.size(), m.size(), "C5 旧数据 size");
            for (Map.Entry<String, String> e : ref.entrySet()) {
                assertEquals(e.getValue(), m.get(e.getKey()), "C5 取值 " + e.getKey());
            }
        }
        assertFalse(Files.exists(g1), "C5 孤儿世代文件应被清理");
    }

    // ------------------------------------------------------------------
    // 用例 6：索引声明的世代数据文件缺失 —— 必须报错
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C6 索引指向的数据文件缺失：明确报错（不静默降级）")
    void caseMissingGeneration() throws IOException {
        Path p = fresh(dir, "missing.fbmap");
        try (FileBackedMap m = new FileBackedMap(p)) {
            m.put("k", "v");
        }
        patchHeader(p, Files.size(dir.resolve("missing.fbmap.data")), 0, 7);

        assertThrows(IOException.class, () -> {
            try (FileBackedMap leaked = new FileBackedMap(p)) {
                leaked.size();   // 不应走到这里
            }
        }, "C6 数据文件缺失应抛 IOException");
    }

    // ------------------------------------------------------------------
    // 用例 7：正常关闭后重开不应触发对账
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C7 正常关闭：脏标记被清除，重开不做对账")
    void caseCleanClose() throws IOException {
        Path p = fresh(dir, "clean.fbmap");
        Map<String, String> ref = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            ref.put("q" + i, "w-" + i);
        }
        try (FileBackedMap m = new FileBackedMap(p)) {
            ref.forEach(m::put);
            assertTrue(dirty(m), "C7 会话进行中脏标记应为 true");
        }
        // 正常关闭后，磁盘上的头应已把 FLAG_DIRTY 清零
        int onDiskFlags = ByteBuffer.wrap(readAll(p)).getInt(H_FLAGS);
        assertEquals(0, onDiskFlags & 1, "C7 正常关闭后磁盘上的脏标记应清零");

        try (FileBackedMap m = new FileBackedMap(p)) {
            assertFalse(recoveredFromCrash(m), "C7 正常关闭后不应执行对账");
            assertEquals(0, droppedOnRecovery(m), "C7 无丢弃");
            assertEquals(ref.size(), m.size(), "C7 size");
            for (Map.Entry<String, String> e : ref.entrySet()) {
                assertEquals(e.getValue(), m.get(e.getKey()), "C7 取值 " + e.getKey());
            }
        }
    }

    // ------------------------------------------------------------------
    // 用例 8：重建（compact / clear）走世代切换 + 只重命名索引
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C8 重建提交方式：数据换世代、索引单点重命名")
    void caseRebuildCommitsByRename() throws IOException {
        Path p = fresh(dir, "rebuild.fbmap");
        Map<String, String> ref = new HashMap<>();
        try (FileBackedMap m = new FileBackedMap(p)) {
            for (int i = 0; i < 300; i++) {
                String k = "r" + i;
                String v = "x".repeat(20 + i % 40);
                m.put(k, v);
                ref.put(k, v);
            }
            for (int i = 0; i < 300; i++) {
                String v = "y".repeat(10 + i % 90);
                m.put("r" + i, v);
                ref.put("r" + i, v);
            }
            int genBefore = dataGeneration(m);
            compact(m);
            int genAfter = dataGeneration(m);
            assertEquals(genBefore + 1, genAfter, "C8 compact 后世代号 +1");
            assertEquals(ref.size(), m.size(), "C8 compact 后 size");
            assertEquals(0L, garbageBytes(m), "C8 compact 后无垃圾");
            assertEquals(0, tombstones(m), "C8 compact 后无墓碑");

            // 旧世代文件应已不存在
            Path oldData = p.resolveSibling(genBefore == 0
                    ? p.getFileName() + ".data"
                    : p.getFileName() + ".data.g" + genBefore);
            assertFalse(Files.exists(oldData), "C8 旧世代数据文件已删除");
            assertTrue(Files.exists(dataFile(m)), "C8 新世代数据文件存在");
            assertTrue(dataFile(m).getFileName().toString().endsWith("g" + genAfter),
                    "C8 新数据文件名含世代号");

            for (Map.Entry<String, String> e : ref.entrySet()) {
                assertEquals(e.getValue(), m.get(e.getKey()), "C8 取值 " + e.getKey());
            }

            // clear 也走同一套：世代 +1，数据文件归零
            m.clear();
            assertEquals(genAfter + 1, dataGeneration(m), "C8 clear 后世代号 +1");
            assertEquals(0, m.size(), "C8 clear 后 size");
            assertEquals(0L, Files.size(dataFile(m)), "C8 clear 后数据文件为空");
            assertEquals(64L + 8L * 16, Files.size(indexFile(m)), "C8 clear 后索引为最小布局");
        }

        // 重开：世代信息随索引一起持久化
        try (FileBackedMap m = new FileBackedMap(p)) {
            assertEquals(0, m.size(), "C8 重开 size");
            assertEquals(0L, Files.size(dataFile(m)), "C8 重开数据文件为空");
            m.put("after-clear", "v");
            assertEquals("v", m.get("after-clear"), "C8 clear 后仍可写入");
        }
    }
}
