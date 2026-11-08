package com.codedad.fbmap.crash;

import com.codedad.fbmap.FileBackedMap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * 崩溃 / 异常落盘场景的对账测试。
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
 * 运行：java -cp build fbmap.CrashSafetyTest build/crash-data
 */
public final class CrashSafetyTest {

    private static final int H_FLAGS = 20;
    private static final int H_DATA_END = 24;
    private static final int H_DATA_GEN = 40;
    private static final int H_CRC = 48;
    private static final int HEADER_SIZE = 64;
    private static final int SLOT_SIZE = 16;

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "build/crash-data");
        Files.createDirectories(dir);

        caseCursorBehind(dir);
        caseDanglingSlot(dir);
        caseTornRecord(dir);
        caseBadCrc(dir);
        caseOrphanGeneration(dir);
        caseMissingGeneration(dir);
        caseCleanClose(dir);
        caseRebuildCommitsByRename(dir);

        System.out.println();
        System.out.println("checks = " + checks + ", failures = " + failures);
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("ALL CRASH-SAFETY CHECKS PASSED");
    }

    private static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            failures++;
            System.out.println("FAIL: " + msg);
        }
    }

    private static void checkEquals(Object expected, Object actual, String msg) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            failures++;
            System.out.println("FAIL: " + msg + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

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

    private static void caseCursorBehind(Path dir) throws IOException {
        System.out.println("[C1] 数据已追加、文件头未更新：游标落后，下次追加不得覆盖活记录");
        Path p = fresh(dir, "cursor.fbmap");
        Map<String, String> ref = new HashMap<>();
        for (int i = 0; i < 120; i++) {
            String k = "k" + i;
            String v = "v" + i + "-" + "x".repeat(50 + i);
            ref.put(k, v);
        }
        try (FileBackedMap m = new FileBackedMap(p)) {
            ref.forEach(m::put);
            checkEquals(0, m.dataGeneration(), "C1 世代应为 0");
        }

        long dataLen = Files.size(dir.resolve("cursor.fbmap.data"));
        // 伪造崩溃点：正常关闭之后再把头改回“游标落后 60 B”，并置脏标记
        patchHeader(p, dataLen - 60, 1, 0);

        try (FileBackedMap m = new FileBackedMap(p)) {
            check(m.recoveredFromCrash(), "C1 应执行对账");
            checkEquals(0, m.droppedOnRecovery(), "C1 不应丢弃任何槽位");
            checkEquals(ref.size(), m.size(), "C1 对账后 size");
            for (Map.Entry<String, String> e : ref.entrySet()) {
                checkEquals(e.getValue(), m.get(e.getKey()), "C1 对账后取值 " + e.getKey());
            }
            checkEquals(dataLen, m.stats().dataBytes(), "C1 对账后游标校准为实际文件长度");
            checkEquals(0L, m.stats().garbageBytes(), "C1 对账后无垃圾（所有字节都被活记录覆盖）");

            // 关键：对账后游标 = 实际文件长度，下一次 put 必须写在末尾，不得覆盖 k0 这条活记录
            m.put("NEW", "fresh-value");
            checkEquals("fresh-value", m.get("NEW"), "C1 新键可读");
            for (int i = 0; i < 120; i++) {
                checkEquals(ref.get("k" + i), m.get("k" + i), "C1 追加后旧键仍正确 k" + i);
            }
            check(Files.size(m.dataPath()) > dataLen, "C1 追加后数据文件变长（未覆盖）");
        }

        // 再打开一次（正常关闭后）确认依然一致
        try (FileBackedMap m = new FileBackedMap(p)) {
            checkEquals(121, m.size(), "C1 重开 size");
            checkEquals("fresh-value", m.get("NEW"), "C1 重开新键");
            checkEquals(ref.get("k119"), m.get("k119"), "C1 重开旧键");
        }
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 用例 2：悬空槽位（指向数据文件之外）
    // ------------------------------------------------------------------

    private static void caseDanglingSlot(Path dir) throws IOException {
        System.out.println("[C2] 槽位指向数据文件之外：应转墓碑丢弃，绝不返回无法验证的内容");
        Path p = fresh(dir, "dangling.fbmap");
        Map<String, String> ref = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            ref.put("d" + i, "value-" + i);
        }
        try (FileBackedMap m = new FileBackedMap(p)) {
            ref.forEach(m::put);
        }

        byte[] hb = readAll(p);
        int slots = ByteBuffer.wrap(hb).getInt(8);
        int slot = occupiedSlotIndex(p);
        check(slot >= 0, "C2 找到占用槽位");

        // 把其中一个槽位的偏移改到远远超出数据文件末尾
        patchSlotOffset(p, slot, Files.size(dir.resolve("dangling.fbmap.data")) + 999_999);
        patchHeader(p, Files.size(dir.resolve("dangling.fbmap.data")), 1, 0);

        try (FileBackedMap m = new FileBackedMap(p)) {
            check(m.recoveredFromCrash(), "C2 应执行对账");
            checkEquals(1, m.droppedOnRecovery(), "C2 应丢弃 1 个悬空槽位");
            checkEquals(ref.size() - 1, m.size(), "C2 对账后 size 减 1");
            checkEquals(0, m.stats().tombstones(), "C2 丢弃后应记为墓碑而非占用");
            int missing = 0;
            for (Map.Entry<String, String> e : ref.entrySet()) {
                String v = m.get(e.getKey());
                if (v == null) {
                    missing++;
                } else {
                    checkEquals(e.getValue(), v, "C2 其余键取值 " + e.getKey());
                }
            }
            checkEquals(1, missing, "C2 恰好 1 个键变成缺失（而不是返回错值）");
        }
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 用例 3：撕裂记录（槽位已发布，但记录被截断）
    // ------------------------------------------------------------------

    private static void caseTornRecord(Path dir) throws IOException {
        System.out.println("[C3] 槽位指向被截断的记录：应丢弃，不得读出半条数据");
        Path p = fresh(dir, "torn.fbmap");
        try (FileBackedMap m = new FileBackedMap(p)) {
            m.put("only", "y".repeat(500));
        }
        Path dp = dir.resolve("torn.fbmap.data");
        long len = Files.size(dp);

        // 截掉最后 200 字节：记录长度字段仍声称有 500 字节值，但文件不够长
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(dp.toFile(), "rw")) {
            raf.setLength(len - 200);
        }
        patchHeader(p, Files.size(dp), 1, 0);

        try (FileBackedMap m = new FileBackedMap(p)) {
            check(m.recoveredFromCrash(), "C3 应执行对账");
            checkEquals(1, m.droppedOnRecovery(), "C3 应丢弃 1 个不可验证的槽位");
            checkEquals(0, m.size(), "C3 对账后 size = 0");
            checkEquals(null, m.get("only"), "C3 不得返回半条数据");
            checkEquals(0, m.stats().tombstones(), "C3 槽位应为墓碑且 entry=0");
        }
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 用例 4：文件头 CRC 损坏
    // ------------------------------------------------------------------

    private static void caseBadCrc(Path dir) throws IOException {
        System.out.println("[C4] 文件头 CRC32 损坏：应触发对账，而不是相信计数");
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
            check(m.recoveredFromCrash(), "C4 CRC 不匹配应触发对账");
            checkEquals(ref.size(), m.size(), "C4 对账后 size");
            for (Map.Entry<String, String> e : ref.entrySet()) {
                checkEquals(e.getValue(), m.get(e.getKey()), "C4 取值 " + e.getKey());
            }
        }
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 用例 5：重建只写了新世代数据文件、索引还没换（孤儿文件）
    // ------------------------------------------------------------------

    private static void caseOrphanGeneration(Path dir) throws IOException {
        System.out.println("[C5] 重建只落盘了数据文件、索引未提交：旧索引 + 旧数据仍可用");
        Path p = fresh(dir, "orphan.fbmap");
        Map<String, String> ref = new HashMap<>();
        for (int i = 0; i < 60; i++) {
            ref.put("o" + i, "vv-" + i + "-" + "z".repeat(30));
        }
        try (FileBackedMap m = new FileBackedMap(p)) {
            ref.forEach(m::put);
            check(m.dataGeneration() == 0, "C5 初始世代 0");
        }

        // 模拟：rebuild 已经把新数据写进 g1 并 fsync，但进程在重命名索引之前崩溃
        Path g1 = dir.resolve("orphan.fbmap.data.g1");
        Files.write(g1, new byte[1234]);        // 内容无意义，只是“已写了一半的新数据文件”

        try (FileBackedMap m = new FileBackedMap(p)) {
            check(m.dataGeneration() == 0, "C5 仍使用旧世代");
            checkEquals(ref.size(), m.size(), "C5 旧数据 size");
            for (Map.Entry<String, String> e : ref.entrySet()) {
                checkEquals(e.getValue(), m.get(e.getKey()), "C5 取值 " + e.getKey());
            }
        }
        check(!Files.exists(g1), "C5 孤儿世代文件应被清理");
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 用例 6：索引声明的世代数据文件缺失 —— 必须报错
    // ------------------------------------------------------------------

    private static void caseMissingGeneration(Path dir) throws IOException {
        System.out.println("[C6] 索引指向的数据文件缺失：明确报错（不静默降级）");
        Path p = fresh(dir, "missing.fbmap");
        try (FileBackedMap m = new FileBackedMap(p)) {
            m.put("k", "v");
        }
        patchHeader(p, Files.size(dir.resolve("missing.fbmap.data")), 0, 7);

        boolean threw = false;
        try (FileBackedMap leaked = new FileBackedMap(p)) {
            check(leaked.size() >= 0, "C6 不应走到这里");
        } catch (IOException e) {
            threw = true;
        }
        check(threw, "C6 数据文件缺失应抛 IOException");
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 用例 7：正常关闭后重开不应触发对账
    // ------------------------------------------------------------------

    private static void caseCleanClose(Path dir) throws IOException {
        System.out.println("[C7] 正常关闭：脏标记被清除，重开不做对账");
        Path p = fresh(dir, "clean.fbmap");
        Map<String, String> ref = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            ref.put("q" + i, "w-" + i);
        }
        try (FileBackedMap m = new FileBackedMap(p)) {
            ref.forEach(m::put);
            check(m.stats().dirty(), "C7 会话进行中脏标记应为 true");
        }
        // 正常关闭后，磁盘上的头应已把 FLAG_DIRTY 清零
        int onDiskFlags = ByteBuffer.wrap(readAll(p)).getInt(H_FLAGS);
        check((onDiskFlags & 1) == 0, "C7 正常关闭后磁盘上的脏标记应清零");

        try (FileBackedMap m = new FileBackedMap(p)) {
            check(!m.recoveredFromCrash(), "C7 正常关闭后不应执行对账");
            checkEquals(0, m.droppedOnRecovery(), "C7 无丢弃");
            checkEquals(ref.size(), m.size(), "C7 size");
            for (Map.Entry<String, String> e : ref.entrySet()) {
                checkEquals(e.getValue(), m.get(e.getKey()), "C7 取值 " + e.getKey());
            }
        }
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 用例 8：重建（compact / clear）走世代切换 + 只重命名索引
    // ------------------------------------------------------------------

    private static void caseRebuildCommitsByRename(Path dir) throws IOException {
        System.out.println("[C8] 重建提交方式：数据换世代、索引单点重命名");
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
            int genBefore = m.dataGeneration();
            m.compact();
            int genAfter = m.dataGeneration();
            checkEquals(genBefore + 1, genAfter, "C8 compact 后世代号 +1");
            checkEquals(ref.size(), m.size(), "C8 compact 后 size");
            checkEquals(0L, m.stats().garbageBytes(), "C8 compact 后无垃圾");
            checkEquals(0, m.stats().tombstones(), "C8 compact 后无墓碑");

            // 旧世代文件应已不存在
            Path oldData = p.resolveSibling(genBefore == 0
                    ? p.getFileName() + ".data"
                    : p.getFileName() + ".data.g" + genBefore);
            check(!Files.exists(oldData), "C8 旧世代数据文件已删除");
            check(Files.exists(m.dataPath()), "C8 新世代数据文件存在");
            check(m.dataPath().getFileName().toString().endsWith("g" + genAfter),
                    "C8 新数据文件名含世代号");

            for (Map.Entry<String, String> e : ref.entrySet()) {
                checkEquals(e.getValue(), m.get(e.getKey()), "C8 取值 " + e.getKey());
            }

            // clear 也走同一套：世代 +1，数据文件归零
            m.clear();
            checkEquals(genAfter + 1, m.dataGeneration(), "C8 clear 后世代号 +1");
            checkEquals(0, m.size(), "C8 clear 后 size");
            checkEquals(0L, Files.size(m.dataPath()), "C8 clear 后数据文件为空");
            checkEquals(64L + 8L * 16, Files.size(m.path()), "C8 clear 后索引为最小布局");
        }

        // 重开：世代信息随索引一起持久化
        try (FileBackedMap m = new FileBackedMap(p)) {
            checkEquals(0, m.size(), "C8 重开 size");
            check(Files.size(m.dataPath()) == 0, "C8 重开数据文件为空");
            m.put("after-clear", "v");
            checkEquals("v", m.get("after-clear"), "C8 clear 后仍可写入");
        }
        System.out.println();
    }
}
