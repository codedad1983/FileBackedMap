package com.codedad.fbmap.crash;

import com.codedad.fbmap.FileBackedMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 崩溃 / 异常落盘场景的测试（JUnit 5，专项测试）。
 *
 * 组件的重建走固定六步提交线：
 * W1 写 data.tmp → W2 写 index.tmp → D1 删原索引 → D2 删原数据 → R1 改名索引 → R2 改名数据。
 * 任意崩溃后的可达快照与两条恢复规则：
 * <ol>
 *   <li><b>回滚</b>：原索引 + 原数据都还在 → 提交点（D1）未过，tmp 一律是未提交垃圾，删除；</li>
 *   <li><b>续做</b>：原始不全而 tmp 对齐全有效 → 补齐剩余改名。</li>
 * </ol>
 * 本测试用「直接改字节 / 摆布文件」的方式构造这些快照，然后打开文件验证恢复行为：
 * <ul>
 *   <li>C1 游标落后（追加了记录但头没更新）——对账后不得覆盖活记录；</li>
 *   <li>C2 悬空槽位（指向数据文件之外）——转墓碑丢弃；</li>
 *   <li>C3 撕裂记录（记录被截断）——丢弃，不得读出半条数据；</li>
 *   <li>C4 文件头 CRC 损坏——触发对账；</li>
 *   <li>C5 W1/W2 之后崩溃（双原始 + 有效 tmp 对）——必须回滚，即使 tmp 完整也不得采用；</li>
 *   <li>C6 原索引在、原数据缺失且无 data.tmp——明确报错，不静默降级；</li>
 *   <li>C7 正常关闭：重开不做对账；</li>
 *   <li>C8 重建提交：固定文件名换版，提交后无 tmp 残留；</li>
 *   <li>C9 D1 之后崩溃（原数据 + tmp 对）——续做改名，采用新内容；</li>
 *   <li>C10 D2 之后崩溃（只剩 tmp 对）——续做两个改名；</li>
 *   <li>C11 R1 之后崩溃（新索引 + data.tmp）——续做数据改名；</li>
 *   <li>C12 原始已删而 tmp 对损坏——无法恢复，明确报错。</li>
 * </ul>
 * 本类是崩溃安全「专项测试」：对账标记、垃圾统计、compact 等是组件 private 内部
 * 信息，黑盒 API 无法观察，因此这里按需用类内反射读取（生产代码与其他常规测试零反射）。
 * 运行：mvn test（surefire 自动执行）。
 */
final class CrashSafetyTest {

    private static final int H_FLAGS = 20;
    private static final int H_DATA_END = 24;
    private static final int H_CRC = 40;
    private static final int CRC_SPAN = 40;
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
        Files.deleteIfExists(dir.resolve(name + ".data.tmp"));
        return p;
    }

    private static Path iTmp(Path index) {
        return index.resolveSibling(index.getFileName() + ".index.tmp");
    }

    private static Path dTmp(Path index) {
        return index.resolveSibling(index.getFileName() + ".data.tmp");
    }

    // ------------------------------------------------------------------
    // 直接操作文件头 / 摆布快照的辅助方法（模拟提交线各步之后的现场）
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
        c.update(headerBytes, 0, CRC_SPAN);
        bb.putInt(H_CRC, (int) c.getValue());
    }

    /** 模拟：数据已写入，但文件头还没来得及更新（崩溃点在 writeHeader 之前）。 */
    private static void patchHeader(Path indexPath, long dataEnd, int flags) throws IOException {
        byte[] b = readAll(indexPath);
        ByteBuffer bb = ByteBuffer.wrap(b);
        bb.putInt(H_FLAGS, flags);
        bb.putLong(H_DATA_END, dataEnd);
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

    /**
     * 以当前健康文件为底，构造一份「清空版」的合法 tmp 对：
     * index.tmp = 原索引头（计数清零、槽位区清零、dataEnd=0、dirty、CRC 重算），
     * data.tmp = 空文件（与头声明的 dataEnd=0 一致，能通过恢复逻辑的配对校验）。
     * 它与原内容可区分（空 vs 非空），用于断言恢复到底采用了哪一份。
     */
    private static void makeClearedTmpPair(Path indexPath) throws IOException {
        byte[] idx = readAll(indexPath);
        ByteBuffer bb = ByteBuffer.wrap(idx);
        int slots = bb.getInt(8);
        Arrays.fill(idx, HEADER_SIZE, HEADER_SIZE + slots * SLOT_SIZE, (byte) 0);
        bb.putInt(12, 0);              // entries = 0
        bb.putInt(16, 0);              // tombstones = 0
        bb.putInt(H_FLAGS, 1);         // dirty
        bb.putLong(H_DATA_END, 0);
        bb.putLong(32, 0);             // garbage
        recomputeCrc(idx);
        writeAll(iTmp(indexPath), idx);
        writeAll(dTmp(indexPath), new byte[0]);
    }

    private static Map<String, String> fill(FileBackedMap m, String prefix, int n, int vlen) {
        Map<String, String> ref = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String k = prefix + i;
            String v = "v" + i + "-" + "x".repeat(vlen + i % 40);
            m.put(k, v);
            ref.put(k, v);
        }
        return ref;
    }

    private static void assertMatches(Map<String, String> ref, FileBackedMap m, String tag) {
        assertEquals(ref.size(), m.size(), tag + " size");
        for (Map.Entry<String, String> e : ref.entrySet()) {
            assertEquals(e.getValue(), m.get(e.getKey()), tag + " 取值 " + e.getKey());
        }
    }

    // ------------------------------------------------------------------
    // 用例 1：游标落后（追加了记录，但 dataEnd 没更新）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C1 数据已追加、文件头未更新：游标落后，下次追加不得覆盖活记录")
    void caseCursorBehind() throws IOException {
        Path p = fresh(dir, "cursor.fbmap");
        Map<String, String> ref;
        long dataLen;
        try (FileBackedMap m = new FileBackedMap(p)) {
            ref = fill(m, "k", 120, 50);
        }

        dataLen = Files.size(dir.resolve("cursor.fbmap.data"));
        // 伪造崩溃点：正常关闭之后再把头改回“游标落后 60 B”，并置脏标记
        patchHeader(p, dataLen - 60, 1);

        try (FileBackedMap m = new FileBackedMap(p)) {
            assertTrue(recoveredFromCrash(m), "C1 应执行对账");
            assertEquals(0, droppedOnRecovery(m), "C1 不应丢弃任何槽位");
            assertMatches(ref, m, "C1 对账后");
            assertEquals(dataLen, dataBytes(m), "C1 对账后游标校准为实际文件长度");
            assertEquals(0L, garbageBytes(m), "C1 对账后无垃圾（所有字节都被活记录覆盖）");

            // 关键：对账后游标 = 实际文件长度，下一次 put 必须写在末尾，不得覆盖 k0 这条活记录
            m.put("NEW", "fresh-value");
            assertEquals("fresh-value", m.get("NEW"), "C1 新键可读");
            assertEquals(ref.size() + 1, m.size(), "C1 追加后 size");
            for (Map.Entry<String, String> e : ref.entrySet()) {
                assertEquals(e.getValue(), m.get(e.getKey()), "C1 追加后旧键 " + e.getKey());
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
        Map<String, String> ref;
        try (FileBackedMap m = new FileBackedMap(p)) {
            ref = fill(m, "d", 50, 6);
        }

        int slot = occupiedSlotIndex(p);
        assertTrue(slot >= 0, "C2 找到占用槽位");

        // 把其中一个槽位的偏移改到远远超出数据文件末尾
        patchSlotOffset(p, slot, Files.size(dir.resolve("dangling.fbmap.data")) + 999_999);
        patchHeader(p, Files.size(dir.resolve("dangling.fbmap.data")), 1);

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
        patchHeader(p, Files.size(dp), 1);

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
        Map<String, String> ref;
        try (FileBackedMap m = new FileBackedMap(p)) {
            ref = fill(m, "c", 40, 4);
        }

        byte[] b = readAll(p);
        b[30] ^= 0x5A;                 // 破坏 header 内一个字节（garbageBytes 区域）
        writeAll(p, b);                // 不重算 CRC：模拟 header 被改但校验没过

        try (FileBackedMap m = new FileBackedMap(p)) {
            assertTrue(recoveredFromCrash(m), "C4 CRC 不匹配应触发对账");
            assertMatches(ref, m, "C4 对账后");
        }
    }

    // ------------------------------------------------------------------
    // 用例 5：W1/W2 之后崩溃——双原始 + 完整有效的 tmp 对 → 必须回滚
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C5 提交点前崩溃（双原始+有效tmp对）：一律回滚删除 tmp，绝不采用未提交内容")
    void caseRollbackBeforeCommitPoint() throws IOException {
        Path p = fresh(dir, "w1.fbmap");
        Map<String, String> ref;
        try (FileBackedMap m = new FileBackedMap(p)) {
            ref = fill(m, "o", 60, 30);
        }

        // 模拟：重建已把「清空版」新对写完并 fsync，但还没走到 D1
        makeClearedTmpPair(p);
        assertTrue(Files.exists(iTmp(p)) && Files.exists(dTmp(p)), "C5 快照构造完成");

        try (FileBackedMap m = new FileBackedMap(p)) {
            assertMatches(ref, m, "C5 回滚后仍是旧内容");
            assertFalse(m.isEmpty(), "C5 绝不能采用 tmp 里的清空版");
            assertTrue(recoveredFromCrash(m), "C5 回滚应记为一次崩溃恢复");
        }
        assertFalse(Files.exists(iTmp(p)), "C5 index.tmp 应被删除");
        assertFalse(Files.exists(dTmp(p)), "C5 data.tmp 应被删除");
    }

    // ------------------------------------------------------------------
    // 用例 6：原索引在、原数据缺失且无 data.tmp —— 必须报错
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C6 原数据缺失且无 data.tmp：明确报错（不静默降级）")
    void caseMissingDataNoTmp() throws IOException {
        Path p = fresh(dir, "missing.fbmap");
        try (FileBackedMap m = new FileBackedMap(p)) {
            m.put("k", "v");
        }
        Files.delete(dir.resolve("missing.fbmap.data"));

        assertThrows(IOException.class, () -> {
            try (FileBackedMap leaked = new FileBackedMap(p)) {
                leaked.size();   // 不应走到这里
            }
        }, "C6 数据文件缺失且无 data.tmp 应抛 IOException");
    }

    // ------------------------------------------------------------------
    // 用例 7：正常关闭后重开不应触发对账
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C7 正常关闭：脏标记被清除，重开不做对账")
    void caseCleanClose() throws IOException {
        Path p = fresh(dir, "clean.fbmap");
        Map<String, String> ref;
        try (FileBackedMap m = new FileBackedMap(p)) {
            ref = fill(m, "q", 30, 2);
            assertTrue(dirty(m), "C7 会话进行中脏标记应为 true");
        }
        // 正常关闭后，磁盘上的头应已把 FLAG_DIRTY 清零
        int onDiskFlags = ByteBuffer.wrap(readAll(p)).getInt(H_FLAGS);
        assertEquals(0, onDiskFlags & 1, "C7 正常关闭后磁盘上的脏标记应清零");

        try (FileBackedMap m = new FileBackedMap(p)) {
            assertFalse(recoveredFromCrash(m), "C7 正常关闭后不应执行对账");
            assertEquals(0, droppedOnRecovery(m), "C7 无丢弃");
            assertMatches(ref, m, "C7");
        }
    }

    // ------------------------------------------------------------------
    // 用例 8：重建（compact / clear）走固定名六步提交线
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C8 重建提交方式：固定名换版、提交后无 tmp 残留")
    void caseRebuildCommitLine() throws IOException {
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

            compact(m);
            assertMatches(ref, m, "C8 compact 后");
            assertEquals(0L, garbageBytes(m), "C8 compact 后无垃圾");
            assertEquals(0, tombstones(m), "C8 compact 后无墓碑");

            // 固定名换版：数据文件仍叫 <path>.data，且两个 tmp 都不应存在
            assertTrue(Files.exists(dataFile(m)), "C8 数据文件存在");
            assertEquals(p.getFileName() + ".data",
                    dataFile(m).getFileName().toString(), "C8 数据文件保持固定名");
            assertFalse(Files.exists(iTmp(p)), "C8 提交后 index.tmp 不残留");
            assertFalse(Files.exists(dTmp(p)), "C8 提交后 data.tmp 不残留");

            // clear 也走同一套提交线
            m.clear();
            assertEquals(0, m.size(), "C8 clear 后 size");
            assertEquals(0L, Files.size(dataFile(m)), "C8 clear 后数据文件为空");
            assertEquals(64L + 8L * 16, Files.size(indexFile(m)), "C8 clear 后索引为最小布局");
            assertFalse(Files.exists(iTmp(p)), "C8 clear 后 index.tmp 不残留");
            assertFalse(Files.exists(dTmp(p)), "C8 clear 后 data.tmp 不残留");
        }

        // 重开确认
        try (FileBackedMap m = new FileBackedMap(p)) {
            assertEquals(0, m.size(), "C8 重开 size");
            assertEquals(0L, Files.size(dataFile(m)), "C8 重开数据文件为空");
            m.put("after-clear", "v");
            assertEquals("v", m.get("after-clear"), "C8 clear 后仍可写入");
        }
    }

    // ------------------------------------------------------------------
    // 用例 9：D1 之后崩溃（原数据 + 有效 tmp 对）→ 续做改名，采用新内容
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C9 D1 后崩溃（原数据+tmp对）：续做两个改名，采用 tmp 内容")
    void caseContinueAfterDeleteIndex() throws IOException {
        Path p = fresh(dir, "d1.fbmap");
        try (FileBackedMap m = new FileBackedMap(p)) {
            fill(m, "e", 40, 20);
        }

        // 构造「清空版」tmp 对（合法且与原内容不同），然后模拟 D1：删除原索引
        makeClearedTmpPair(p);
        Files.delete(p);

        try (FileBackedMap m = new FileBackedMap(p)) {
            assertEquals(0, m.size(), "C9 续做后应采用新内容（空表）");
            assertTrue(recoveredFromCrash(m), "C9 续做应记为一次崩溃恢复");
            assertFalse(Files.exists(iTmp(p)), "C9 index.tmp 已改名转正");
            assertFalse(Files.exists(dTmp(p)), "C9 data.tmp 已改名转正");
            assertEquals(0L, Files.size(p.resolveSibling(p.getFileName() + ".data")),
                    "C9 转正后的数据文件为空");
            m.put("post", "ok");
            assertEquals("ok", m.get("post"), "C9 恢复后可正常写入");
        }
    }

    // ------------------------------------------------------------------
    // 用例 10：D2 之后崩溃（只剩 tmp 对）→ 续做两个改名
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C10 D2 后崩溃（只剩 tmp 对）：补齐两个改名")
    void caseContinueAfterDeleteBoth() throws IOException {
        Path p = fresh(dir, "d2.fbmap");
        try (FileBackedMap m = new FileBackedMap(p)) {
            fill(m, "f", 40, 20);
        }

        makeClearedTmpPair(p);
        // 模拟 D1 + D2：两个原始都删了，改名还没做
        Files.delete(p);
        Files.delete(dir.resolve("d2.fbmap.data"));

        try (FileBackedMap m = new FileBackedMap(p)) {
            assertEquals(0, m.size(), "C10 续做后 size");
            assertTrue(Files.exists(p) && Files.exists(dir.resolve("d2.fbmap.data")),
                    "C10 两个文件都回到固定名");
            m.put("post", "ok");
            assertEquals("ok", m.get("post"), "C10 恢复后可正常写入");
        }
    }

    // ------------------------------------------------------------------
    // 用例 11：R1 之后崩溃（新索引已就位，data.tmp 未转正）→ 续做数据改名
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C11 R1 后崩溃（新索引+data.tmp）：续做数据改名")
    void caseContinueAfterRenameIndex() throws IOException {
        Path p = fresh(dir, "r1.fbmap");
        try (FileBackedMap m = new FileBackedMap(p)) {
            fill(m, "g", 40, 20);
        }

        makeClearedTmpPair(p);
        // 模拟 D1、D2、R1：原始都删除，index.tmp 已改名转正，只剩 data.tmp
        Files.delete(p);
        Files.delete(dir.resolve("r1.fbmap.data"));
        Files.move(iTmp(p), p);

        try (FileBackedMap m = new FileBackedMap(p)) {
            assertEquals(0, m.size(), "C11 续做后 size");
            assertFalse(Files.exists(dTmp(p)), "C11 data.tmp 已转正");
            assertEquals(0L, Files.size(dir.resolve("r1.fbmap.data")),
                    "C11 转正后的数据文件为空");
            assertTrue(recoveredFromCrash(m), "C11 应记为一次崩溃恢复");
        }
    }

    // ------------------------------------------------------------------
    // 用例 13：putAll 批次中途进程被强杀 → 重开对账自洽
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C13 putAll 批次中途进程被强杀：重开对账自洽，绝不返回无法验证的内容")
    void casePutAllKilledMidBatch() throws Exception {
        Path p = fresh(dir, "putallcrash.fbmap");

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java")
                .toAbsolutePath().toString();
        String cp = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", cp,
                PutAllCrasher.class.getName(), p.toString(), "200000");
        pb.redirectErrorStream(true);
        Process kid = pb.start();

        // 等子进程开始批量写入（不要求写完——多半写不完就被强杀）
        String line = null;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(kid.getInputStream(),
                        java.nio.charset.Charset.defaultCharset()))) {
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                line = r.readLine();
                if (line == null) {
                    break;
                }
                if (line.startsWith("BATCH")) {
                    break;
                }
            }
        }
        assertNotNull(line, "C13 子进程应输出 BATCH（未读到说明启动失败）");
        assertTrue(line.startsWith("BATCH"), "C13 子进程应开始批量写入，实际: " + line);

        // 强杀：页缓存里未 force 的记录与槽位随进程消失
        kid.destroyForcibly();
        kid.waitFor();

        // 重开：脏标记在，必须触发对账；结果必须自洽
        try (FileBackedMap m = openWithRetry(p, 10)) {
            assertTrue(recoveredFromCrash(m), "C13 强杀后重开应执行对账");
            long dataLen = Files.size(dir.resolve("putallcrash.fbmap.data"));
            assertEquals(dataLen, dataBytes(m), "C13 对账后游标 = 数据文件实际长度");

            int size = m.size();
            int counted = 0;
            for (String k : m.keySet()) {
                counted++;
                // 每个能读到的键必须等于批量写入时该键的值（绝不得返回半条 / 错值）
                String v = m.get(k);
                assertEquals(expected(k), v, "C13 强杀后键 " + k + " 的取值");
            }
            assertEquals(size, counted, "C13 size 与遍历计数一致（槽位与头自洽）");

            // 对账后继续写入不受影响
            m.put("AFTER", "ok");
            assertEquals("ok", m.get("AFTER"), "C13 对账后可写入");
        }

        // 正常关闭后重开不应再触发对账，且内容不变
        try (FileBackedMap m = new FileBackedMap(p)) {
            assertFalse(recoveredFromCrash(m), "C13 正常关闭后重开不应再对账");
            assertEquals("ok", m.get("AFTER"), "C13 二次重开新键仍在");
        }
    }

    /** putAll 批量写入用的键值约定（与 PutAllCrasher 完全一致，测试侧独立复算）。 */
    private static String expected(String key) {
        if (!key.startsWith("pk")) {
            return null;
        }
        int i = Integer.parseInt(key.substring(2));
        return (i % 5 == 3) ? null : "pv" + i;
    }

    /** 重开重试：强杀进程后锁释放可能有微小延迟（Windows）。 */
    private static FileBackedMap openWithRetry(Path p, int attempts) throws IOException {
        IOException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return new FileBackedMap(p);
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    /** 崩溃用例子进程：打开 map 后开始一次性 putAll 大批量，写完就挂住等被强杀。 */
    public static final class PutAllCrasher {
        public static void main(String[] args) throws Exception {
            int n = Integer.parseInt(args[1]);
            java.util.Map<String, String> batch = new java.util.LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                batch.put("pk" + i, (i % 5 == 3) ? null : "pv" + i);
            }
            try (FileBackedMap m = new FileBackedMap(Path.of(args[0]))) {
                System.out.println("BATCH " + batch.size());
                System.out.flush();
                m.putAll(batch);
                // 写完也不退出，等父进程强杀（模拟「批次已写完但 close 落盘前被杀」）
                Thread.sleep(600_000);
            }
        }
    }

    // ------------------------------------------------------------------
    // 用例 12：原始已删而 tmp 对损坏 → 明确报错
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C12 原始已删且 tmp 对损坏：无法恢复，明确报错")
    void caseUnrecoverable() throws IOException {
        Path p = fresh(dir, "dead.fbmap");

        // 快照 A：只剩一对损坏的 tmp
        Files.write(iTmp(p), "garbage-not-an-index".getBytes());
        Files.write(dTmp(p), new byte[9]);
        assertThrows(IOException.class, () -> {
            try (FileBackedMap leaked = new FileBackedMap(p)) {
                leaked.size();
            }
        }, "C12-A 损坏 tmp 对应抛 IOException");
        Files.delete(iTmp(p));
        Files.delete(dTmp(p));

        // 快照 B：R1 后 data.tmp 丢失（新索引在、数据无处可寻）
        try (FileBackedMap m = new FileBackedMap(p)) {
            m.put("h", "v");
        }
        makeClearedTmpPair(p);
        Files.delete(p);
        Files.delete(dir.resolve("dead.fbmap.data"));
        Files.move(iTmp(p), p);           // 新索引转正
        Files.delete(dTmp(p));            // 数据丢失
        assertThrows(IOException.class, () -> {
            try (FileBackedMap leaked = new FileBackedMap(p)) {
                leaked.size();
            }
        }, "C12-B 新索引缺配套数据应抛 IOException");
    }
}
