package com.codedad.fbmap;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.zip.CRC32;

/**
 * 把 {@code Map<String, String>} 的数据全部放在磁盘文件上的实现（双文件布局）。
 *
 * <h2>索引与数据分离</h2>
 * <ol>
 *   <li><b>扩容只重写索引区</b>：槽位区独立成一个文件，扩容时只重建「槽位文件」
 *       （N 个槽位 × 16 B）。100 万条目时为 32 MB 级，而数据区约 1 GB 完全不参与。</li>
 *   <li><b>数据区只追加</b>：记录写在数据文件里，槽位中保存的是「数据文件内的偏移」，
 *       扩容时偏移不变，因此数据文件一个字节都不用动。</li>
 *   <li><b>垃圾整理是双文件重建</b>：只有在垃圾超过数据区一半时才触发，此时才需要
 *       重写数据文件（这也是真正的开销所在，属于预期行为）。</li>
 * </ol>
 *
 * <h2>落盘顺序（崩溃安全）</h2>
 * 四条不变量：
 * <ol>
 *   <li><b>记录不可变</b>：任何更新都追加一条新记录，旧记录原地留下成为垃圾。绝不原地改字节，
 *       因此不存在「写了一半的新值 / 旧值混在一起」的撕裂记录。</li>
 *   <li><b>先写记录字节，再发布槽位</b>：槽位一旦指向某偏移，该偏移之前的字节就已完整写入。
 *       崩溃时最坏情况是「记录已写、槽位未发布」——那只是一段孤儿垃圾，不影响任何键。</li>
 *   <li><b>游标 = 文件长度</b>：追加位置永远等于数据文件当前长度，文件头里的 {@code dataEnd}
 *       只是一个缓存值，打开时一律以实际文件长度为校准值。这样即使头字段落后，也不会
 *       出现「游标倒退、下次追加覆盖仍被槽位引用的活记录」。</li>
 *   <li><b>固定顺序提交线</b>：双文件重建（整理 / 清空）严格按六步执行——
 *       W1 写 {@code <path>.data.tmp} → W2 写 {@code <path>.index.tmp}（各自 fsync）→
 *       D1 删除原索引 → D2 删除原数据 → R1 改名索引 → R2 改名数据。
 *       数据先写：一次槽位扫描同时完成记录拷贝与新索引的槽位映射，无需二次扫描。
 *       每一步都是原子操作，因此任意崩溃后的文件集合只有六种，且每种都能唯一判定：</li>
 * </ol>
 * <table border="1">
 *   <caption>崩溃快照与恢复动作</caption>
 *   <tr><th>磁盘上存在的文件</th><th>成因</th><th>打开时的动作</th></tr>
 *   <tr><td>原索引 + 原数据（±任意 tmp）</td><td>提交线尚未开始 / 提交点（D1）之前崩溃</td>
 *       <td><b>回滚</b>：tmp 一律是未提交的垃圾，删除，用原始对</td></tr>
 *   <tr><td>原索引 + 原数据</td><td>未触发重建 / 全部完成</td><td>正常打开</td></tr>
 *   <tr><td>原数据 + index.tmp + data.tmp</td><td>D1 之后</td><td><b>续做</b>：补齐两个改名</td></tr>
 *   <tr><td>index.tmp + data.tmp</td><td>D2 之后</td><td><b>续做</b>：补齐两个改名</td></tr>
 *   <tr><td>原索引(新) + data.tmp</td><td>R1 之后</td><td><b>续做</b>：补齐数据改名</td></tr>
 * </table>
 * 两条恢复规则即覆盖全部快照：<b>双原始齐 → 删 tmp 回滚；原始不齐且 tmp 对齐全有效 →
 * 补齐改名续做</b>；其余组合（原始已删而 tmp 对损坏）属于外部破坏或介质故障，明确报错。
 * 注：提交点在语义上是 D1（删除），D1 之后回滚材料已销毁，只余续做一条路。
 * 仅重建走这条提交线；扩容（growIndex）数据文件原封不动，保持「写 index.tmp → 改名」单点提交。
 * <p>打开时还会做对账（脏标记 / CRC32 校验失败、数据长度与头不一致时）：重算条目数、墓碑数、
 * 垃圾字节数，并把指向越界偏移的悬空槽位改成墓碑丢弃。
 *
 * <h2>文件布局</h2>
 * <pre>
 * 索引文件 &lt;path&gt;
 * ─────────────────────────────────────────────
 * 0      64 B 文件头
 *   +0  "FBMP"   +4 版本(=4)
 *   +8  槽位数   +12 条目数   +16 墓碑数
 *   +20 flags(bit0=脏: 上次未正常关闭/多步更新未完成)
 *   +24 数据区终点（= 数据文件长度，打开时以实际长度校准）
 *   +32 垃圾字节数
 *   +40 文件头 CRC32（覆盖 0..39 字节）
 *   +44 未使用（写 0，为后续扩展留白）
 * 64    槽位区  slotCount × 16 B
 *   槽位 = [1B 状态][3B 填充][4B hash][8B 数据文件偏移]
 *
 * 数据文件 &lt;path&gt;.data（固定名，永不改名；重建产物为 &lt;path&gt;.data.tmp）
 * ─────────────────────────────────────────────
 * 0    记录区（只追加，无头）
 *   每条记录: [4B keyLen][key][4B valLen][val]
 *   len = -1 表示 null；len = 0 表示空串
 * </pre>
 *
 * <h2>行为语义</h2>
 * <ul>
 *   <li>API 与 {@code Map<String,String>} 完全一致（含 Java 8 默认方法、三类视图与迭代器）。</li>
 *   <li>允许 null 键 / null 值 / 空串；非 String 入参按“不存在”处理。</li>
 *   <li>键值长度不固定、无上限（单条记录上限约 2 GB，总量受磁盘限制）。</li>
 *   <li>不使用内存映射（无 {@code FileChannel.map} / {@code MappedByteBuffer}），
 *       也不把文件整体读入堆；只做定长定位读写。</li>
 * </ul>
 *
 * <h2>并发模型</h2>
 * <ul>
 *   <li><b>进程内</b>：读写锁（{@link ReentrantReadWriteLock}）。纯读操作
 *       （get / containsKey / containsValue / size / isEmpty / equals / hashCode / toString /
 *       迭代器扫描）取<b>读锁</b>，可并发；写操作与「读-改-写」复合方法
 *       （put / remove / compute* / merge / replace* / forEach / replaceAll）取<b>写锁</b>独占。
 *       复合方法整体在写锁内执行，其回调函数内再调用本实例的任何方法都安全（锁可重入）。
 *       共享缓冲（槽位、比较缓冲等）为线程私有，读锁下的并发读互不干扰。</li>
 *   <li><b>跨进程</b>：打开时对索引文件持有独占 {@link FileLock}，同一组文件被第二个进程
 *       打开时立即抛 {@link IOException}，进程终止时由系统自动释放。
 *       POSIX 上为建议性锁（只约束同样走本类的进程），Windows 上由系统强制。</li>
 *   <li>迭代器 fail-fast：任何写操作（含其它线程）触发
 *       {@link ConcurrentModificationException}。</li>
 * </ul>
 */
public final class FileBackedMap implements Map<String, String>, Closeable {

    // ================= 常量 =================
    private static final byte[] MAGIC = {'F', 'B', 'M', 'P'};
    private static final int FORMAT_VERSION = 4;

    /** 重建提交线的固定临时文件名（相对索引文件同目录）：先写后删、只改名，绝不复用旧名。 */
    private static final String INDEX_TMP_SUFFIX = ".index.tmp";
    private static final String DATA_TMP_SUFFIX = ".data.tmp";

    private static final int HEADER_SIZE = 64;
    private static final int SLOT_SIZE = 16;
    private static final int RECORD_OVERHEAD = 8;

    private static final byte ST_EMPTY = 0;
    private static final byte ST_OCCUPIED = 1;
    private static final byte ST_TOMBSTONE = 2;

    private static final int MIN_SLOTS = 8;
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    private static final int H_SLOTS = 8;
    private static final int H_ENTRIES = 12;
    private static final int H_TOMB = 16;
    /** bit0 = 1 表示「上次没有正常关闭，或某次多步更新尚未完成提交」，打开时需要对账修复。 */
    private static final int H_FLAGS = 20;
    private static final int H_DATA_END = 24;
    private static final int H_GARBAGE = 32;
    /** 文件头 CRC32，覆盖 0..39 字节。 */
    private static final int H_CRC = 40;
    private static final int CRC_SPAN = 40;

    private static final int FLAG_DIRTY = 1;

    /** 重建 / 对账时按块读写槽位区，避免逐槽位的系统调用开销。 */
    private static final int SLOT_BLOCK = 4096;

    // ================= 实例状态 =================
    private final Path path;       // 索引文件
    private Path dataPath;         // 当前使用的数据文件（固定名 &lt;path&gt;.data，永不改名）
    private final boolean durable;

    private RandomAccessFile indexRaf;
    private FileChannel index;
    private RandomAccessFile dataRaf;
    private FileChannel data;

    private int slotCount;
    private int entryCount;
    private int tombstoneCount;
    private long dataEnd;
    private long garbageBytes;
    private int flags;
    private int modCount;

    /** 本次打开是否执行过崩溃对账；对账时丢弃了多少指向越界偏移的槽位。 */
    private boolean recoveredFromCrash;
    private int droppedOnRecovery;
    /** 文件头 CRC32 是否与内容匹配（旧格式文件没有 CRC 时视为通过）。 */
    private boolean crcValid = true;

    /**
     * 跨进程独占锁：打开时对索引文件加独占 {@link FileLock}，持有到 close()。
     * 进程崩溃 / 强杀时由操作系统随句柄一起释放，因此不会留下需要清理的锁文件。
     */
    private FileLock indexLock;

    /**
     * 进程内读写锁。纯读方法取读锁（并发），写方法与「读-改-写」复合方法取写锁（独占）。
     * 复合方法把回调函数也包在写锁内，回调中重入本实例任意方法安全（写锁可降级重入）。
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    // 复用缓冲：读锁下多个读者并发访问，必须线程私有（ThreadLocal），不能用共享实例字段。
    private final ThreadLocal<byte[]> slotBytes = ThreadLocal.withInitial(() -> new byte[SLOT_SIZE]);
    private final ThreadLocal<ByteBuffer> slotBuf =
            ThreadLocal.withInitial(() -> ByteBuffer.wrap(slotBytes.get()));
    private final ThreadLocal<byte[]> headerBytes = ThreadLocal.withInitial(() -> new byte[HEADER_SIZE]);
    private final ThreadLocal<ByteBuffer> headerBuf =
            ThreadLocal.withInitial(() -> ByteBuffer.wrap(headerBytes.get()));
    private final ThreadLocal<byte[]> intBytes = ThreadLocal.withInitial(() -> new byte[4]);
    private final ThreadLocal<byte[]> compareBuffer = ThreadLocal.withInitial(() -> new byte[1024]);

    // ==================================================================
    // 构造 / 打开
    // ==================================================================

    public FileBackedMap(Path path) throws IOException {
        this(path, false);
    }

    public FileBackedMap(Path path, boolean durable) throws IOException {
        Objects.requireNonNull(path, "path");
        this.path = path.toAbsolutePath();
        this.durable = durable;

        Path parent = this.path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // 打开第一步：先占住这组文件（打开索引通道并取独占锁），再走两条恢复规则。
        // 顺序不能反：恢复会删除 / 改名临时文件与索引文件，必须先确认没有第二个进程
        // 正在使用这组文件，否则会把对方正在写的 data.tmp 当成未提交垃圾删掉。
        // openIndexFile 必须在 try 内：取锁失败时通道已经打开，不关闭就会泄漏句柄
        // （Windows 上泄漏的句柄等同于持有文件锁，会让后续打开一直失败）。
        try {
            openIndexFile();
            recoverCommitLine();

            Path dataFile = dataFileFor(this.path);
            boolean hasIndex = Files.exists(this.path) && Files.size(this.path) > 0;
            if (!hasIndex) {
                // 走到这里说明现场干净且无数据：全新创建。
                createEmpty();
                cleanupStaleTmpFiles();
                return;
            }

            loadHeader();
            dataPath = dataFile;
            if (!Files.exists(dataPath)) {
                throw new IOException("数据文件缺失: " + dataPath);
            }
            openDataFile(dataPath);

            long expectedIdx = HEADER_SIZE + (long) slotCount * SLOT_SIZE;
            if (index.size() < expectedIdx) {
                throw new IOException("索引文件被截断：需要 " + expectedIdx + "，实际 " + index.size());
            }

            long actualData = data.size();
            boolean needReconcile = (flags & FLAG_DIRTY) != 0
                    || !crcValid
                    || dataEnd < 0
                    || dataEnd != actualData;

            // 本次会话开始：先置脏标记，只有正常 close() 才清除它。
            flags |= FLAG_DIRTY;
            if (needReconcile) {
                reconcile();
            } else {
                writeHeader();
                if (durable) {
                    index.force(false);
                }
            }

            if (overloaded() || garbageFull()) {
                maintain();
            }
            cleanupStaleTmpFiles();
        } catch (IOException e) {
            closeChannels();
            throw e;
        }
    }

    // ==================================================================
    // 打开时恢复：两条规则覆盖固定六步提交线的全部崩溃快照
    //   规则 1：原索引 + 原数据都在 → 提交点（D1）未过，一切 tmp 是未提交垃圾 → 删除回滚。
    //   规则 2：原始不全而 tmp 对齐全有效 → D1 已过、只剩改名未完 → 补齐改名续做。
    //   其余（原始已删而 tmp 对缺失/损坏）→ 外部破坏或介质故障 → 明确报错。
    // 调用时索引通道已打开并持有独占锁；需要改名 / 删除索引文件的分支先让锁关通道
    // （Windows 上被占用且加锁的文件无法改名），完成后重新打开并取锁。
    // ==================================================================

    private void recoverCommitLine() throws IOException {
        Path dataFile = dataFileFor(path);
        Path iTmp = tmpIndexFor(path);
        Path dTmp = tmpDataFor(path);

        boolean hasIndex = Files.exists(path) && Files.size(path) > 0;
        boolean hasData = Files.exists(dataFile);

        if (hasIndex && hasData) {
            // 规则 1：双原始齐 → 回滚。tmp 对即使完整也不采用：其中可能含未向调用方
            // 应答过的写入，续做会违背原子性。只删 tmp，不碰原始，无需让锁。
            boolean rolledBack = Files.deleteIfExists(iTmp) | Files.deleteIfExists(dTmp);
            if (rolledBack) {
                recoveredFromCrash = true;
            }
            return;
        }

        if (!hasIndex && !hasData) {
            if (tmpPairValid(iTmp, dTmp)) {
                // 快照 {index.tmp, data.tmp}：D2 之后 → 补齐 R1 + R2。
                withIndexChannelClosed(() -> {
                    // REPLACE_EXISTING：占锁时已顺带创建 0 字节索引占位文件，改名覆盖它。
                    Files.move(iTmp, path, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(dTmp, dataFile);
                });
                recoveredFromCrash = true;
                return;
            }
            if (Files.exists(iTmp) || Files.exists(dTmp)) {
                throw new IOException("提交线中断且临时文件对无效，无法恢复: "
                        + iTmp + " / " + dTmp);
            }
            return;   // 现场干净：全新创建或仅有残缺空文件，交给上层
        }

        if (hasIndex) {
            // 快照 {原索引(新), data.tmp}：R1 之后 → 补齐 R2（只动数据文件，不碰索引）。
            if (Files.exists(dTmp)) {
                deleteQuietly(iTmp);   // 协议上此时 iTmp 已被 R1 消耗，防御性清理
                Files.move(dTmp, dataFile);
                recoveredFromCrash = true;
                return;
            }
            throw new IOException("数据文件缺失且无有效 data.tmp，无法恢复: " + dataFile);
        }

        // 索引缺失、原数据(旧)还在
        if (tmpPairValid(iTmp, dTmp)) {
            // 快照 {旧数据, index.tmp, data.tmp}：D1 之后 → 旧数据成垃圾，补齐 R1 + R2。
            withIndexChannelClosed(() -> {
                Files.delete(dataFile);
                Files.move(iTmp, path, StandardCopyOption.REPLACE_EXISTING);
                Files.move(dTmp, dataFile);
            });
            recoveredFromCrash = true;
            return;
        }
        if (Files.size(dataFile) > 0) {
            throw new IOException("数据文件存在但索引文件缺失: " + path);
        }
        if (Files.exists(iTmp) || Files.exists(dTmp)) {
            throw new IOException("提交线中断且临时文件对无效，无法恢复: "
                    + iTmp + " / " + dTmp);
        }
        // 只剩一个 0 字节的旧数据文件且无任何可引用内容：视为可重建现场。
        Files.delete(dataFile);
    }

    /** 恢复期改名 / 删除索引文件时临时让出通道与独占锁，做完立即重新打开取回。 */
    private void withIndexChannelClosed(IoOp op) throws IOException {
        closeIndexChannel();
        try {
            op.run();
        } finally {
            openIndexFile();
        }
    }

    private interface IoOp {
        void run() throws IOException;
    }

    /** 校验 index.tmp + data.tmp 是否是一份写完的配对（头合法 + CRC + 数据长度与头一致）。 */
    private static boolean tmpPairValid(Path iTmp, Path dTmp) throws IOException {
        if (!Files.exists(iTmp) || !Files.exists(dTmp)) {
            return false;
        }
        long idxSize = Files.size(iTmp);
        if (idxSize < HEADER_SIZE) {
            return false;
        }
        byte[] h = new byte[HEADER_SIZE];
        try (FileChannel ch = FileChannel.open(iTmp, StandardOpenOption.READ)) {
            ByteBuffer bb = ByteBuffer.wrap(h);
            long pos = 0;
            while (bb.hasRemaining()) {
                if (ch.read(bb, pos) < 0) {
                    return false;
                }
                pos = bb.position();
            }
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (h[i] != MAGIC[i]) {
                return false;
            }
        }
        ByteBuffer hb = ByteBuffer.wrap(h);
        if (hb.getInt(4) != FORMAT_VERSION) {
            return false;
        }
        int slots = hb.getInt(H_SLOTS);
        if (slots < MIN_SLOTS || (slots & (slots - 1)) != 0) {
            return false;
        }
        if (idxSize < HEADER_SIZE + (long) slots * SLOT_SIZE) {
            return false;
        }
        int storedCrc = hb.getInt(H_CRC);
        CRC32 c = new CRC32();
        c.update(h, 0, CRC_SPAN);
        if (storedCrc != (int) c.getValue()) {
            return false;
        }
        long de = hb.getLong(H_DATA_END);
        return de >= 0 && Files.size(dTmp) == de;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignore) {
            // 删不掉不影响正确性
        }
    }

    /** 数据文件固定名 {@code <path>.data}：永不改名，重建产物只写 {@code .data.tmp}。 */
    private static Path dataFileFor(Path indexFile) {
        return indexFile.resolveSibling(indexFile.getFileName() + ".data");
    }

    private static Path tmpIndexFor(Path indexFile) {
        return indexFile.resolveSibling(indexFile.getFileName() + INDEX_TMP_SUFFIX);
    }

    private static Path tmpDataFor(Path indexFile) {
        return indexFile.resolveSibling(indexFile.getFileName() + DATA_TMP_SUFFIX);
    }

    private void openIndexFile() throws IOException {
        indexRaf = new RandomAccessFile(path.toFile(), "rw");
        index = indexRaf.getChannel();
        acquireIndexLock();
    }

    /**
     * 对索引通道取独占锁，确立「一组文件同一时刻只有一个进程实例」的协议前提。
     * 同进程内重复加锁会抛 {@link OverlappingFileLockException}（FileChannel 语义），
     * 统一转成带路径的 {@link IOException}。
     */
    private void acquireIndexLock() throws IOException {
        try {
            FileLock fl = index.tryLock(0, Long.MAX_VALUE, false);
            if (fl == null) {
                throw new IOException("这组文件已被其它进程打开（无法取得独占锁）: " + path);
            }
            indexLock = fl;
        } catch (OverlappingFileLockException e) {
            throw new IOException("这组文件已在本进程内被打开（无法重复取得独占锁）: " + path, e);
        }
    }

    /** 释放索引通道与独占锁（锁必须先于通道释放，通道关闭也会隐式释放锁）。 */
    private void closeIndexChannel() throws IOException {
        if (indexLock != null) {
            try {
                indexLock.release();
            } catch (OverlappingFileLockException | ClosedChannelException ignore) {
                // 通道已失效时锁已随句柄释放，无需处理
            }
            indexLock = null;
        }
        if (indexRaf != null) {
            indexRaf.close();
            indexRaf = null;
            index = null;
        }
    }

    private void openDataFile(Path p) throws IOException {
        if (dataRaf != null) {
            dataRaf.close();
        }
        dataRaf = new RandomAccessFile(p.toFile(), "rw");
        data = dataRaf.getChannel();
        dataPath = p;
    }

    private void closeChannels() throws IOException {
        closeIndexChannel();
        if (dataRaf != null) {
            dataRaf.close();
            dataRaf = null;
            data = null;
        }
    }

    /** 防御性清理：恢复流程走完或全新创建后，两个固定 tmp 都不应仍然存在。 */
    private void cleanupStaleTmpFiles() {
        deleteQuietly(tmpIndexFor(path));
        deleteQuietly(tmpDataFor(path));
    }

    private void createEmpty() throws IOException {
        slotCount = MIN_SLOTS;
        entryCount = 0;
        tombstoneCount = 0;
        dataEnd = 0;
        garbageBytes = 0;
        flags = FLAG_DIRTY;
        modCount = 0;

        dataPath = dataFileFor(path);
        // 索引通道已由构造器打开并取到独占锁，这里只重置内容，不重复打开（同通道重复加锁会抛）。
        openDataFile(dataPath);
        index.truncate(0);
        data.truncate(0);
        extendChannel(index, (long) HEADER_SIZE + (long) slotCount * SLOT_SIZE);
        writeHeader();
        if (durable) {
            index.force(false);
            data.force(false);
        }
    }

    // ==================================================================
    // 文件头
    // ==================================================================

    private void loadHeader() throws IOException {
        byte[] hb = headerBytes.get();
        ByteBuffer hbuf = headerBuf.get();
        readAt(index, 0, hb, 0, HEADER_SIZE);
        hbuf.clear();
        for (int i = 0; i < MAGIC.length; i++) {
            if (hb[i] != MAGIC[i]) {
                throw new IOException("不是 FileBackedMap 索引文件: " + path);
            }
        }
        int version = hbuf.getInt(4);
        if (version != FORMAT_VERSION) {
            throw new IOException("不支持的格式版本: " + version
                    + "（当前格式版本为 " + FORMAT_VERSION + "，索引 + 数据双文件布局）");
        }
        slotCount = hbuf.getInt(H_SLOTS);
        entryCount = hbuf.getInt(H_ENTRIES);
        tombstoneCount = hbuf.getInt(H_TOMB);
        flags = hbuf.getInt(H_FLAGS);
        dataEnd = hbuf.getLong(H_DATA_END);
        garbageBytes = hbuf.getLong(H_GARBAGE);
        int storedCrc = hbuf.getInt(H_CRC);

        CRC32 c = new CRC32();
        c.update(hb, 0, CRC_SPAN);
        crcValid = storedCrc == (int) c.getValue();

        if (slotCount < MIN_SLOTS || (slotCount & (slotCount - 1)) != 0) {
            throw new IOException("槽位数非法: " + slotCount);
        }
        if (entryCount < 0 || tombstoneCount < 0) {
            // 计数器可能因崩溃漂移，交给对账修复，不作为硬错误
            entryCount = Math.max(entryCount, 0);
            tombstoneCount = Math.max(tombstoneCount, 0);
        }
        if (garbageBytes < 0) {
            garbageBytes = 0;
        }
    }

    private void writeHeader() throws IOException {
        writeHeaderTo(index, slotCount, entryCount, tombstoneCount, dataEnd, garbageBytes, flags);
    }

    private static ByteBuffer buildHeaderBuffer(int slots, int entries, int tomb, long dataEnd,
                                                long garbage, int flags) {
        ByteBuffer hb = ByteBuffer.allocate(HEADER_SIZE);
        // 用绝对偏移写入，避免字段错位
        hb.put(0, MAGIC[0]).put(1, MAGIC[1]).put(2, MAGIC[2]).put(3, MAGIC[3]);
        hb.putInt(4, FORMAT_VERSION);
        hb.putInt(H_SLOTS, slots);
        hb.putInt(H_ENTRIES, entries);
        hb.putInt(H_TOMB, tomb);
        hb.putInt(H_FLAGS, flags);
        hb.putLong(H_DATA_END, dataEnd);
        hb.putLong(H_GARBAGE, garbage);
        CRC32 c = new CRC32();
        c.update(hb.array(), 0, CRC_SPAN);
        hb.putInt(H_CRC, (int) c.getValue());
        hb.clear();
        return hb;
    }

    private static void writeHeaderTo(FileChannel ch, int slots, int entries, int tomb, long dataEnd,
                                      long garbage, int flags) throws IOException {
        writeAt(ch, 0, buildHeaderBuffer(slots, entries, tomb, dataEnd, garbage, flags));
    }

    /** 数据先落盘、索引后落盘：这样索引指向的记录一定已经持久化。 */
    private void syncIfDurable() throws IOException {
        if (durable) {
            data.force(false);
            index.force(false);
        }
    }

    private Path path() {
        return path;
    }

    private Path dataPath() {
        return dataPath;
    }

    /** 本次打开是否做过崩溃对账。 */
    private boolean recoveredFromCrash() {
        return recoveredFromCrash;
    }

    /** 对账时被丢弃的悬空槽位数量。 */
    private int droppedOnRecovery() {
        return droppedOnRecovery;
    }

    // ==================================================================
    // 底层定位读写（普通文件 I/O，不使用内存映射）
    // ==================================================================

    private static void readAt(FileChannel ch, long pos, byte[] dst, int off, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(dst, off, len);
        while (bb.hasRemaining()) {
            int n = ch.read(bb, pos);
            if (n < 0) {
                throw new IOException("文件被截断：pos=" + pos + "，len=" + len);
            }
            pos += n;
        }
    }

    private void readAt(long pos, byte[] dst, int off, int len) throws IOException {
        readAt(data, pos, dst, off, len);
    }

    private static void writeAt(FileChannel ch, long pos, ByteBuffer src) throws IOException {
        while (src.hasRemaining()) {
            pos += ch.write(src, pos);
        }
    }

    private void writeAt(long pos, ByteBuffer src) throws IOException {
        writeAt(data, pos, src);
    }

    private static void extendChannel(FileChannel ch, long size) throws IOException {
        if (ch.size() >= size) {
            return;
        }
        ByteBuffer one = ByteBuffer.allocate(1);
        long pos = size - 1;
        while (one.hasRemaining()) {
            pos += ch.write(one, pos);
        }
    }

    private int readInt(long pos) throws IOException {
        byte[] ib = intBytes.get();
        readAt(data, pos, ib, 0, 4);
        return ((ib[0] & 0xFF) << 24)
                | ((ib[1] & 0xFF) << 16)
                | ((ib[2] & 0xFF) << 8)
                | (ib[3] & 0xFF);
    }

    // ==================================================================
    // 槽位（索引文件）
    // ==================================================================

    private record Slot(byte state, int hash, long offset) {
    }

    private record Record(String key, String value) {
    }

    private Slot readSlot(int i) throws IOException {
        byte[] sb = slotBytes.get();
        ByteBuffer sbuf = slotBuf.get();
        readAt(index, HEADER_SIZE + (long) i * SLOT_SIZE, sb, 0, SLOT_SIZE);
        sbuf.clear();
        byte state = sbuf.get();
        sbuf.get(); sbuf.get(); sbuf.get();
        int hash = sbuf.getInt();
        long offset = sbuf.getLong();
        return new Slot(state, hash, offset);
    }

    private void writeSlot(int i, byte state, int hash, long offset) throws IOException {
        ByteBuffer sbuf = slotBuf.get();
        sbuf.clear();
        sbuf.put(state);
        sbuf.put((byte) 0).put((byte) 0).put((byte) 0);
        sbuf.putInt(hash);
        sbuf.putLong(offset);
        sbuf.flip();
        writeAt(index, HEADER_SIZE + (long) i * SLOT_SIZE, sbuf);
    }

    // ==================================================================
    // 记录（数据文件）
    // ==================================================================

    private boolean keyEquals(long offset, byte[] keyBytes) throws IOException {
        int keyLen = readInt(offset);
        if (keyBytes == null) {
            return keyLen == -1;
        }
        if (keyLen != keyBytes.length) {
            return false;
        }
        byte[] cb = compareBuffer.get();
        if (cb.length < keyLen) {
            cb = new byte[keyLen];
            compareBuffer.set(cb);
        }
        readAt(offset + 4, cb, 0, keyLen);
        for (int i = 0; i < keyLen; i++) {
            if (cb[i] != keyBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private String readKeyAt(long offset) throws IOException {
        int keyLen = readInt(offset);
        if (keyLen == -1) {
            return null;
        }
        byte[] kb = new byte[keyLen];
        readAt(offset + 4, kb, 0, keyLen);
        return new String(kb, UTF8);
    }

    private long valueLenPos(long offset) throws IOException {
        int keyLen = readInt(offset);
        return offset + 4 + Math.max(keyLen, 0);
    }

    private String readValueAt(long offset) throws IOException {
        long vp = valueLenPos(offset);
        int valLen = readInt(vp);
        if (valLen == -1) {
            return null;
        }
        if (valLen == 0) {
            return "";
        }
        byte[] vb = new byte[valLen];
        readAt(vp + 4, vb, 0, valLen);
        return new String(vb, UTF8);
    }

    private Record readRecordAt(long offset) throws IOException {
        int keyLen = readInt(offset);
        String key = null;
        long p = offset + 4;
        if (keyLen >= 0) {
            byte[] kb = new byte[keyLen];
            readAt(p, kb, 0, keyLen);
            key = new String(kb, UTF8);
            p += keyLen;
        }
        int valLen = readInt(p);
        String value = null;
        if (valLen >= 0) {
            byte[] vb = new byte[valLen];
            readAt(p + 4, vb, 0, valLen);
            value = new String(vb, UTF8);
        }
        return new Record(key, value);
    }

    private long recordLength(long offset) throws IOException {
        int keyLen = readInt(offset);
        long vp = offset + 4 + Math.max(keyLen, 0);
        int valLen = readInt(vp);
        return RECORD_OVERHEAD + Math.max(keyLen, 0) + Math.max(valLen, 0);
    }

    /**
     * 安全的记录长度读取：记录在 {@code limit} 内不完整或长度字段非法时返回 -1。
     * 用于对账时识别「悬空槽位」（指向记录区之外）与被截断的尾巴。
     */
    private long safeRecordLength(long offset, long limit) throws IOException {
        if (offset < 0 || offset + RECORD_OVERHEAD > limit) {
            return -1;
        }
        int keyLen = readInt(offset);
        if (keyLen < -1) {
            return -1;
        }
        long vp = offset + 4 + Math.max(keyLen, 0);
        if (vp + 4 > limit) {
            return -1;
        }
        int valLen = readInt(vp);
        if (valLen < -1) {
            return -1;
        }
        long total = RECORD_OVERHEAD + Math.max(keyLen, 0) + Math.max(valLen, 0);
        if (offset + total > limit) {
            return -1;
        }
        return total;
    }

    /**
     * 追加一条记录：写到当前数据文件末尾（dataEnd == 文件长度），只改游标，不碰槽位。
     * 槽位必须在这之后才发布 —— 这是「先数据、后索引」的关键顺序。
     */
    private long appendRecord(byte[] keyBytes, byte[] valueBytes) throws IOException {
        int keyLen = keyBytes == null ? -1 : keyBytes.length;
        int valLen = valueBytes == null ? -1 : valueBytes.length;
        int total = RECORD_OVERHEAD + Math.max(keyLen, 0) + Math.max(valLen, 0);
        ByteBuffer bb = ByteBuffer.allocate(total);
        bb.putInt(keyLen);
        if (keyBytes != null) {
            bb.put(keyBytes);
        }
        bb.putInt(valLen);
        if (valueBytes != null) {
            bb.put(valueBytes);
        }
        bb.flip();
        long offset = dataEnd;
        writeAt(data, offset, bb);
        dataEnd = offset + total;
        return offset;
    }

    // ==================================================================
    // 哈希
    // ==================================================================

    private static int hashBytes(byte[] bytes) {
        if (bytes == null) {
            return 0;
        }
        int h = 0x9E3779B1;
        int i = 0;
        for (; i + 4 <= bytes.length; i += 4) {
            int k = (bytes[i] & 0xFF)
                    | ((bytes[i + 1] & 0xFF) << 8)
                    | ((bytes[i + 2] & 0xFF) << 16)
                    | ((bytes[i + 3] & 0xFF) << 24);
            k *= 0xCC9E2D51;
            k = Integer.rotateLeft(k, 15);
            k *= 0x1B873593;
            h ^= k;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xE6546B64;
        }
        int tail = 0;
        int remaining = bytes.length - i;
        if (remaining == 3) {
            tail ^= (bytes[i + 2] & 0xFF) << 16;
            tail ^= (bytes[i + 1] & 0xFF) << 8;
            tail ^= (bytes[i] & 0xFF);
        } else if (remaining == 2) {
            tail ^= (bytes[i + 1] & 0xFF) << 8;
            tail ^= (bytes[i] & 0xFF);
        } else if (remaining == 1) {
            tail ^= (bytes[i] & 0xFF);
        }
        if (remaining > 0) {
            tail *= 0xCC9E2D51;
            tail = Integer.rotateLeft(tail, 15);
            tail *= 0x1B873593;
            h ^= tail;
        }
        h ^= bytes.length;
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    private int findSlotIndex(int hash, byte[] keyBytes) throws IOException {
        int mask = slotCount - 1;
        int i = hash & mask;
        for (int probes = 0; probes < slotCount; probes++) {
            Slot s = readSlot(i);
            if (s.state == ST_EMPTY) {
                return -1;
            }
            if (s.state == ST_OCCUPIED && s.hash == hash && keyEquals(s.offset, keyBytes)) {
                return i;
            }
            i = (i + 1) & mask;
        }
        return -1;
    }

    // ==================================================================
    // 写操作
    // ==================================================================

    private String putInternal(byte[] keyBytes, byte[] valueBytes) throws IOException {
        return putInternal(keyBytes, valueBytes, true);
    }

    private String putInternal(byte[] keyBytes, byte[] valueBytes, boolean allowMaintain) throws IOException {
        int hash = hashBytes(keyBytes);
        int mask = slotCount - 1;
        int i = hash & mask;
        int firstTomb = -1;
        Slot found = null;
        int foundIndex = -1;

        for (int probes = 0; probes < slotCount; probes++) {
            Slot s = readSlot(i);
            if (s.state == ST_EMPTY) {
                break;
            }
            if (s.state == ST_TOMBSTONE) {
                if (firstTomb < 0) {
                    firstTomb = i;
                }
            } else if (s.hash == hash && keyEquals(s.offset, keyBytes)) {
                found = s;
                foundIndex = i;
                break;
            }
            i = (i + 1) & mask;
        }

        if (found != null) {
            long offset = found.offset;
            int keyLen = readInt(offset);
            long vp = offset + 4 + Math.max(keyLen, 0);
            int oldValLen = readInt(vp);
            byte[] oldBytes = new byte[Math.max(oldValLen, 0)];
            if (oldValLen > 0) {
                readAt(vp + 4, oldBytes, 0, oldValLen);
            }

            boolean sameValue;
            if (oldValLen == -1) {
                sameValue = (valueBytes == null);
            } else if (valueBytes == null) {
                sameValue = false;
            } else {
                sameValue = oldValLen == valueBytes.length && Arrays.equals(oldBytes, valueBytes);
            }
            String oldValue = (oldValLen == -1)
                    ? null
                    : (oldValLen == 0 ? "" : new String(oldBytes, UTF8));
            if (sameValue) {
                return oldValue;   // 值未变，不写任何字节（也不产生垃圾）
            }

            // 旧记录不可变，整条成为垃圾
            garbageBytes += RECORD_OVERHEAD + Math.max(keyLen, 0) + Math.max(oldValLen, 0);

            // 1) 追加新记录（只推进游标）
            long newOffset = appendRecord(keyBytes, valueBytes);
            // 2) durable 时先把记录字节刷到磁盘，再发布槽位
            if (durable) {
                data.force(false);
            }
            // 3) 发布槽位
            writeSlot(foundIndex, ST_OCCUPIED, hash, newOffset);
            // 4) 最后更新头（计数器）
            writeHeader();
            if (durable) {
                index.force(false);
            }
            if (allowMaintain) {
                maintain();
            }
            return oldValue;
        }

        // 新键：追加记录 → 发布槽位（复用第一个墓碑或探测末尾的空槽）
        long newOffset = appendRecord(keyBytes, valueBytes);
        if (durable) {
            data.force(false);
        }
        int target = (firstTomb >= 0) ? firstTomb : i;
        if (firstTomb >= 0) {
            tombstoneCount--;
        }
        writeSlot(target, ST_OCCUPIED, hash, newOffset);
        entryCount++;
        modCount++;
        writeHeader();
        if (durable) {
            index.force(false);
        }
        if (allowMaintain) {
            maintain();
        }
        return null;
    }

    private String removeInternal(byte[] keyBytes, boolean allowMaintain) throws IOException {
        int hash = hashBytes(keyBytes);
        int mask = slotCount - 1;
        int i = hash & mask;
        for (int probes = 0; probes < slotCount; probes++) {
            Slot s = readSlot(i);
            if (s.state == ST_EMPTY) {
                return null;
            }
            if (s.state == ST_OCCUPIED && s.hash == hash && keyEquals(s.offset, keyBytes)) {
                String oldValue = readValueAt(s.offset);
                garbageBytes += recordLength(s.offset);
                writeSlot(i, ST_TOMBSTONE, 0, -1);   // 只动索引，数据区不动
                tombstoneCount++;
                entryCount--;
                modCount++;
                writeHeader();
                if (durable) {
                    index.force(false);
                }
                if (allowMaintain) {
                    maintain();
                }
                return oldValue;
            }
            i = (i + 1) & mask;
        }
        return null;
    }

    private String removeInternal(byte[] keyBytes) throws IOException {
        return removeInternal(keyBytes, true);
    }

    // ==================================================================
    // 对账（崩溃恢复）
    // ==================================================================

    /**
     * 崩溃对账：以数据文件实际长度为唯一权威，重算条目数 / 墓碑数 / 垃圾字节数，
     * 并把指向越界偏移或损坏记录的槽位改成墓碑丢弃。
     *
     * 只做「丢弃不可验证的槽位」，绝不重建键值 —— 因此不会把错误数据扩散出去。
     */
    private void reconcile() throws IOException {
        long limit = data.size();          // 数据文件实际长度 = 唯一可信的游标

        int entries = 0;
        int tombs = 0;
        int dropped = 0;
        long liveBytes = 0;

        final int BLK_BYTES = SLOT_BLOCK * SLOT_SIZE;
        byte[] blk = new byte[BLK_BYTES];

        for (int base = 0; base < slotCount; base += SLOT_BLOCK) {
            int cnt = Math.min(SLOT_BLOCK, slotCount - base);
            readAt(index, HEADER_SIZE + (long) base * SLOT_SIZE, blk, 0, cnt * SLOT_SIZE);
            ByteBuffer bb = ByteBuffer.wrap(blk, 0, cnt * SLOT_SIZE);
            boolean changed = false;

            for (int j = 0; j < cnt; j++) {
                byte st = bb.get();
                bb.get(); bb.get(); bb.get();
                bb.getInt();
                long off = bb.getLong();

                if (st == ST_OCCUPIED) {
                    long len = safeRecordLength(off, limit);
                    if (len < 0) {
                        // 悬空 / 损坏：转墓碑，绝不返回无法验证的内容
                        int p = j * SLOT_SIZE;
                        blk[p] = ST_TOMBSTONE;
                        blk[p + 1] = 0; blk[p + 2] = 0; blk[p + 3] = 0;
                        blk[p + 4] = 0; blk[p + 5] = 0; blk[p + 6] = 0; blk[p + 7] = 0;
                        long o = -1;
                        for (int k = 0; k < 8; k++) {
                            blk[p + 8 + k] = (byte) (o >>> (56 - 8 * k));
                        }
                        changed = true;
                        dropped++;
                    } else {
                        entries++;
                        liveBytes += len;
                    }
                } else if (st == ST_TOMBSTONE) {
                    tombs++;
                }
            }

            if (changed) {
                writeAt(index, HEADER_SIZE + (long) base * SLOT_SIZE,
                        ByteBuffer.wrap(blk, 0, cnt * SLOT_SIZE));
            }
        }

        // 以数据文件实际长度为唯一权威游标；未被任何存活槽位引用的字节全部算垃圾。
        dataEnd = limit;
        garbageBytes = Math.max(0, limit - liveBytes);
        entryCount = entries;
        tombstoneCount = tombs;
        recoveredFromCrash = true;
        droppedOnRecovery = dropped;

        writeHeader();
        if (durable) {
            index.force(false);
        }
    }

    // ==================================================================
    // 维护
    // ==================================================================

    private boolean overloaded() {
        return (long) (entryCount + tombstoneCount) * 3 >= (long) slotCount * 2;
    }

    private boolean garbageFull() {
        return garbageBytes > Math.max(4096L, dataEnd / 2);
    }

    private void maintain() throws IOException {
        if (overloaded()) {
            growIndex(slotCount * 2);          // 只重写索引文件
        } else if (garbageFull()) {
            rebuild(slotCount, true);          // 数据 + 索引一起重建
        }
    }

    private void compact() {
        writeLock.lock();
        try {
            try {
                rebuild(Math.max(MIN_SLOTS, slotCount), true);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 扩容：只重建索引文件。数据文件不动，槽位中的偏移原样搬过去。
     * 写入量 = newSlotCount × 16 B（100 万条目时约 32 MB），与数据区大小无关。
     *
     * 提交方式是「重命名索引」，数据文件在提交前后都完全一致，
     * 因此崩溃只会落在旧索引或新索引上，两者指向的偏移都合法。
     */
    private void growIndex(int newSlotCount) throws IOException {
        newSlotCount = Math.max(MIN_SLOTS, newSlotCount);
        Path tmp = tmpIndexFor(path);
        int newMask = newSlotCount - 1;

        byte[] dstState = new byte[newSlotCount];   // 0 = EMPTY
        int[] dstHash = new int[newSlotCount];
        long[] dstOffset = new long[newSlotCount];

        final int BLK_BYTES = SLOT_BLOCK * SLOT_SIZE;
        byte[] buf = new byte[BLK_BYTES];

        // ---- 1) 块读旧索引，在内存里重新分配目标槽位 ----
        for (int base = 0; base < slotCount; base += SLOT_BLOCK) {
            int cnt = Math.min(SLOT_BLOCK, slotCount - base);
            readAt(index, HEADER_SIZE + (long) base * SLOT_SIZE, buf, 0, cnt * SLOT_SIZE);
            ByteBuffer bb = ByteBuffer.wrap(buf, 0, cnt * SLOT_SIZE);
            for (int j = 0; j < cnt; j++) {
                byte st = bb.get();
                bb.get(); bb.get(); bb.get();
                int h = bb.getInt();
                long off = bb.getLong();
                if (st != ST_OCCUPIED) {
                    continue;
                }
                int idx = h & newMask;
                while (dstState[idx] != 0) {
                    idx = (idx + 1) & newMask;
                }
                dstState[idx] = ST_OCCUPIED;
                dstHash[idx] = h;
                dstOffset[idx] = off;
            }
        }

        // ---- 2) 块写新索引（数据偏移原样保留，数据文件完全不动）----
        try (RandomAccessFile r = new RandomAccessFile(tmp.toFile(), "rw");
             FileChannel out = r.getChannel()) {
            out.truncate(0);
            extendChannel(out, (long) HEADER_SIZE + (long) newSlotCount * SLOT_SIZE);

            byte[] outBuf = new byte[BLK_BYTES];
            for (int base = 0; base < newSlotCount; base += SLOT_BLOCK) {
                int cnt = Math.min(SLOT_BLOCK, newSlotCount - base);
                ByteBuffer ob = ByteBuffer.wrap(outBuf, 0, cnt * SLOT_SIZE);
                for (int j = 0; j < cnt; j++) {
                    int idx = base + j;
                    ob.put(dstState[idx]);
                    ob.put((byte) 0).put((byte) 0).put((byte) 0);
                    ob.putInt(dstHash[idx]);
                    ob.putLong(dstOffset[idx]);
                }
                ob.flip();                       // 必须 flip，否则 hasRemaining() 为 false
                writeAt(out, HEADER_SIZE + (long) base * SLOT_SIZE, ob);
            }

            writeHeaderTo(out, newSlotCount, entryCount, 0, dataEnd, garbageBytes,
                    flags | FLAG_DIRTY);
            // 改名会覆盖旧索引、销毁回滚材料，改名前新内容必须无条件落盘（与 durable 无关）：
            // 对账只能丢弃悬空槽位，无法重建槽位→记录的映射，落盘不全的槽位区不可恢复。
            out.force(false);
        }

        // ---- 3) 单点提交：重命名索引；数据文件全程原封不动，不受影响 ----
        closeIndexChannel();
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            openIndexFile();
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
                // 保留也无害
            }
            throw e;
        }
        openIndexFile();

        slotCount = newSlotCount;
        tombstoneCount = 0;
        modCount++;
    }

    /**
     * 重建：把存活记录连续写进新数据文件，重建索引，然后走固定六步提交线换版。
     * {@code keepRecords=false} 用于 clear()。
     *
     * 六步顺序严格固定：W1 写 data.tmp（fsync）→ W2 写 index.tmp（fsync）→
     * D1 删除原索引 → D2 删除原数据 → R1 改名索引 → R2 改名数据。
     * 数据先写：唯一的槽位扫描同时完成「拷贝记录到新数据文件」与「计算新索引的槽位映射」
     * （记录写入顺序即新偏移顺序），随后写索引只是把内存映射落盘，全程只扫描一次槽位区。
     * 每步原子，崩溃后的六种可达快照全部可被打开时的两条恢复规则唯一判定：
     * 双原始齐 → 删 tmp 回滚；原始不全且 tmp 对有效 → 补齐改名续做。
     * 提交点在语义上是 D1；D1 之后本对象若再遇 I/O 异常，现场交由下次打开自动续做。
     */
    private void rebuild(int newSlotCount, boolean keepRecords) throws IOException {
        newSlotCount = Math.max(MIN_SLOTS, newSlotCount);
        Path newDataPath = dataFileFor(path);
        Path tmpIndex = tmpIndexFor(path);
        Path tmpData = tmpDataFor(path);

        int newMask = newSlotCount - 1;
        byte[] dstState = new byte[newSlotCount];
        int[] dstHash = new int[newSlotCount];
        long[] dstOffset = new long[newSlotCount];

        final int BLK_BYTES = SLOT_BLOCK * SLOT_SIZE;
        byte[] inBuf = new byte[BLK_BYTES];

        long newEnd = 0;
        int newEntries = 0;

        // ---- W1) 单遍槽位扫描：存活记录按序拷进 data.tmp，同时登记新索引的槽位映射 ----
        try (RandomAccessFile rd = new RandomAccessFile(tmpData.toFile(), "rw");
             FileChannel outData = rd.getChannel()) {
            outData.truncate(0);

            if (keepRecords) {
                for (int base = 0; base < slotCount; base += SLOT_BLOCK) {
                    int cnt = Math.min(SLOT_BLOCK, slotCount - base);
                    readAt(index, HEADER_SIZE + (long) base * SLOT_SIZE, inBuf, 0, cnt * SLOT_SIZE);
                    ByteBuffer bb = ByteBuffer.wrap(inBuf, 0, cnt * SLOT_SIZE);
                    for (int j = 0; j < cnt; j++) {
                        byte st = bb.get();
                        bb.get(); bb.get(); bb.get();
                        int h = bb.getInt();
                        long off = bb.getLong();
                        if (st != ST_OCCUPIED) {
                            continue;
                        }
                        long len = safeRecordLength(off, dataEnd);
                        if (len < 0) {
                            continue;   // 悬空记录：丢弃，重建后的文件不会引用它
                        }
                        byte[] rec = new byte[(int) len];
                        readAt(data, off, rec, 0, rec.length);
                        writeAt(outData, newEnd, ByteBuffer.wrap(rec));

                        long nOff = newEnd;
                        newEnd += len;

                        int idx = h & newMask;
                        while (dstState[idx] != 0) {
                            idx = (idx + 1) & newMask;
                        }
                        dstState[idx] = ST_OCCUPIED;
                        dstHash[idx] = h;
                        dstOffset[idx] = nOff;
                        newEntries++;
                    }
                }
            }
            // W1 必须无条件落盘，与 durable 无关：D1 一旦删掉原始对，data.tmp 就是
            // 存活记录的唯一副本；未落盘时崩溃会让「tmp 对齐全」的前提失效，恢复无法续做。
            outData.force(false);
        }

        // ---- W2) 写 index.tmp：槽位映射与头（dataEnd = 刚写完的 data.tmp 长度）落盘 ----
        try (RandomAccessFile ri = new RandomAccessFile(tmpIndex.toFile(), "rw");
             FileChannel outIndex = ri.getChannel()) {
            outIndex.truncate(0);
            extendChannel(outIndex, (long) HEADER_SIZE + (long) newSlotCount * SLOT_SIZE);

            byte[] outBuf = new byte[BLK_BYTES];
            for (int base = 0; base < newSlotCount; base += SLOT_BLOCK) {
                int cnt = Math.min(SLOT_BLOCK, newSlotCount - base);
                ByteBuffer ob = ByteBuffer.wrap(outBuf, 0, cnt * SLOT_SIZE);
                for (int j = 0; j < cnt; j++) {
                    int idx = base + j;
                    ob.put(dstState[idx]);
                    ob.put((byte) 0).put((byte) 0).put((byte) 0);
                    ob.putInt(dstHash[idx]);
                    ob.putLong(dstOffset[idx]);
                }
                ob.flip();
                writeAt(outIndex, HEADER_SIZE + (long) base * SLOT_SIZE, ob);
            }

            writeHeaderTo(outIndex, newSlotCount, newEntries, 0, newEnd, 0, flags | FLAG_DIRTY);
            // W2 同样无条件落盘：index.tmp 的头里写着 dataEnd，恢复时靠它判定 tmp 对是否
            // 写完；若头还在页缓存里就走到 D1，规则②的校验会失败而无法续做。
            outIndex.force(false);
        }

        // ---- D1 → D2 → R1 → R2：提交线，每步一个原子操作 ----
        closeChannels();
        try {
            Files.deleteIfExists(path);          // D1
            Files.deleteIfExists(newDataPath);   // D2
            Files.move(tmpIndex, path);          // R1
            Files.move(tmpData, newDataPath);    // R2
        } catch (IOException e) {
            // 现场可能停在任一快照上；下次打开由两条恢复规则续做。本实例不再可用。
            throw new IOException("重建提交线中断，请重新打开（现场可自动续做修复）", e);
        }

        openIndexFile();
        openDataFile(newDataPath);

        slotCount = newSlotCount;
        entryCount = newEntries;
        tombstoneCount = 0;
        dataEnd = newEnd;
        garbageBytes = 0;
        modCount++;
    }

    // ==================================================================
    // Map：读取
    // ==================================================================

    @Override
    public int size() {
        readLock.lock();
        try {
            return entryCount;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public boolean isEmpty() {
        readLock.lock();
        try {
            return entryCount == 0;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public boolean containsKey(Object key) {
        if (key != null && !(key instanceof String)) {
            return false;
        }
        readLock.lock();
        try {
            try {
                byte[] kb = key == null ? null : ((String) key).getBytes(UTF8);
                return findSlotIndex(hashBytes(kb), kb) >= 0;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public boolean containsValue(Object value) {
        if (value != null && !(value instanceof String)) {
            return false;
        }
        byte[] target = value == null ? null : ((String) value).getBytes(UTF8);
        readLock.lock();
        try {
            try {
                for (int i = 0; i < slotCount; i++) {
                    Slot s = readSlot(i);
                    if (s.state != ST_OCCUPIED) {
                        continue;
                    }
                    long vp = valueLenPos(s.offset);
                    int valLen = readInt(vp);
                    if (target == null) {
                        if (valLen == -1) {
                            return true;
                        }
                        continue;
                    }
                    if (valLen != target.length) {
                        continue;
                    }
                    if (valLen == 0) {
                        return true;
                    }
                    byte[] vb = new byte[valLen];
                    readAt(vp + 4, vb, 0, valLen);
                    if (Arrays.equals(vb, target)) {
                        return true;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            readLock.unlock();
        }
        return false;
    }

    @Override
    public String get(Object key) {
        if (key != null && !(key instanceof String)) {
            return null;
        }
        readLock.lock();
        try {
            try {
                byte[] kb = key == null ? null : ((String) key).getBytes(UTF8);
                int idx = findSlotIndex(hashBytes(kb), kb);
                return idx < 0 ? null : readValueAt(readSlot(idx).offset);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public String getOrDefault(Object key, String defaultValue) {
        String v = get(key);
        return v != null ? v : defaultValue;
    }

    // ==================================================================
    // Map：写入
    // ==================================================================

    @Override
    public String put(String key, String value) {
        writeLock.lock();
        try {
            try {
                return putInternal(key == null ? null : key.getBytes(UTF8),
                        value == null ? null : value.getBytes(UTF8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public String remove(Object key) {
        if (key != null && !(key instanceof String)) {
            return null;
        }
        writeLock.lock();
        try {
            try {
                return removeInternal(key == null ? null : ((String) key).getBytes(UTF8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean remove(Object key, Object value) {
        if ((key != null && !(key instanceof String)) || (value != null && !(value instanceof String))) {
            return false;
        }
        writeLock.lock();
        try {
            if (!containsKey(key)) {
                return false;
            }
            if (!Objects.equals(get(key), value)) {
                return false;
            }
            remove(key);
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void putAll(Map<? extends String, ? extends String> m) {
        Objects.requireNonNull(m, "m");
        for (Map.Entry<? extends String, ? extends String> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    @Override
    public void clear() {
        writeLock.lock();
        try {
            try {
                rebuild(MIN_SLOTS, false);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public String putIfAbsent(String key, String value) {
        writeLock.lock();
        try {
            String oldValue = get(key);
            if (oldValue == null) {
                put(key, value);
                return null;
            }
            return oldValue;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean replace(String key, String oldValue, String newValue) {
        writeLock.lock();
        try {
            if (!containsKey(key) || !Objects.equals(get(key), oldValue)) {
                return false;
            }
            put(key, newValue);
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public String replace(String key, String value) {
        writeLock.lock();
        try {
            if (!containsKey(key)) {
                return null;
            }
            String old = get(key);
            put(key, value);
            return old;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public String compute(String key, BiFunction<? super String, ? super String, ? extends String> f) {
        Objects.requireNonNull(f, "remappingFunction");
        writeLock.lock();
        try {
            String oldValue = get(key);
            String newValue = f.apply(key, oldValue);
            if (newValue == null) {
                remove(key);
                return null;
            }
            put(key, newValue);
            return newValue;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public String computeIfAbsent(String key, Function<? super String, ? extends String> f) {
        Objects.requireNonNull(f, "mappingFunction");
        writeLock.lock();
        try {
            String oldValue = get(key);
            if (oldValue != null) {
                return oldValue;
            }
            String newValue = f.apply(key);
            if (newValue != null) {
                put(key, newValue);
            }
            return newValue;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public String computeIfPresent(String key, BiFunction<? super String, ? super String, ? extends String> f) {
        Objects.requireNonNull(f, "remappingFunction");
        writeLock.lock();
        try {
            String oldValue = get(key);
            if (oldValue == null) {
                return null;
            }
            String newValue = f.apply(key, oldValue);
            if (newValue == null) {
                remove(key);
                return null;
            }
            put(key, newValue);
            return newValue;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public String merge(String key, String value,
                        BiFunction<? super String, ? super String, ? extends String> f) {
        Objects.requireNonNull(f, "remappingFunction");
        writeLock.lock();
        try {
            String oldValue = get(key);
            String newValue = (oldValue == null) ? value : f.apply(oldValue, value);
            if (newValue == null) {
                remove(key);
                return null;
            }
            put(key, newValue);
            return newValue;
        } finally {
            writeLock.unlock();
        }
    }

    // ==================================================================
    // 批量操作
    // ==================================================================

    @Override
    public void forEach(BiConsumer<? super String, ? super String> action) {
        Objects.requireNonNull(action, "action");
        writeLock.lock();
        try {
            int expected = modCount;
            try {
                for (int i = 0; i < slotCount; i++) {
                    Slot s = readSlot(i);
                    if (s.state != ST_OCCUPIED) {
                        continue;
                    }
                    if (modCount != expected) {
                        throw new ConcurrentModificationException();
                    }
                    Record r = readRecordAt(s.offset);
                    action.accept(r.key(), r.value());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void replaceAll(BiFunction<? super String, ? super String, ? extends String> f) {
        Objects.requireNonNull(f, "function");
        writeLock.lock();
        try {
            int expected = modCount;
            try {
                for (int i = 0; i < slotCount; i++) {
                    Slot s = readSlot(i);
                    if (s.state != ST_OCCUPIED) {
                        continue;
                    }
                    if (modCount != expected) {
                        throw new ConcurrentModificationException();
                    }
                    long offset = s.offset;
                    int keyLen = readInt(offset);
                    byte[] kb = keyLen >= 0 ? new byte[keyLen] : null;
                    long p = offset + 4;
                    if (kb != null) {
                        readAt(p, kb, 0, keyLen);
                        p += keyLen;
                    }
                    int valLen = readInt(p);
                    byte[] vb = valLen >= 0 ? new byte[valLen] : null;
                    if (vb != null) {
                        readAt(p + 4, vb, 0, valLen);
                    }

                    String key = kb == null ? null : new String(kb, UTF8);
                    String oldValue = vb == null ? null : new String(vb, UTF8);
                    String newValue = f.apply(key, oldValue);
                    if (newValue == null) {
                        throw new NullPointerException("replaceAll 的函数不能返回 null");
                    }
                    if (Objects.equals(newValue, oldValue)) {
                        continue;
                    }
                    byte[] nb = newValue.getBytes(UTF8);
                    // 一律追加，不原地覆盖（避免撕裂记录）
                    garbageBytes += RECORD_OVERHEAD + Math.max(keyLen, 0) + Math.max(valLen, 0);
                    long newOffset = appendRecord(kb, nb);
                    if (durable) {
                        data.force(false);
                    }
                    writeSlot(i, ST_OCCUPIED, s.hash, newOffset);
                }
                writeHeader();
                syncIfDurable();
                maintain();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    // ==================================================================
    // 视图
    // ==================================================================

    @Override
    public Set<String> keySet() {
        return new KeySetView();
    }

    @Override
    public Collection<String> values() {
        return new ValuesView();
    }

    @Override
    public Set<Map.Entry<String, String>> entrySet() {
        return new EntrySetView();
    }

    private abstract class DiskIterator<T> implements Iterator<T> {
        private int expected = modCount;
        private int scanIndex = 0;
        private int nextSlot = -1;
        private String nextKey;
        private String nextValue;
        private String lastKey;
        private boolean lastReady;

        DiskIterator() {
            readLock.lock();
            try {
                expected = modCount;
                advance();
            } finally {
                readLock.unlock();
            }
        }

        protected abstract T create(String key, String value);

        private void advance() {
            readLock.lock();
            try {
                if (modCount != expected) {
                    throw new ConcurrentModificationException();
                }
                try {
                    while (scanIndex < slotCount) {
                        Slot s = readSlot(scanIndex);
                        if (s.state == ST_OCCUPIED) {
                            Record r = readRecordAt(s.offset);
                            nextSlot = scanIndex;
                            nextKey = r.key();
                            nextValue = r.value();
                            scanIndex++;
                            return;
                        }
                        scanIndex++;
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                nextSlot = -1;
            } finally {
                readLock.unlock();
            }
        }

        @Override
        public boolean hasNext() {
            return nextSlot >= 0;
        }

        @Override
        public T next() {
            if (nextSlot < 0) {
                throw new NoSuchElementException();
            }
            T element = create(nextKey, nextValue);
            lastKey = nextKey;
            lastReady = true;
            advance();
            return element;
        }

        @Override
        public void remove() {
            if (!lastReady) {
                throw new IllegalStateException("next() 还没被调用，或当前元素已被删除");
            }
            writeLock.lock();
            try {
                byte[] kb = lastKey == null ? null : lastKey.getBytes(UTF8);
                removeInternal(kb, false);
                expected = modCount;
                lastKey = null;
                lastReady = false;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                writeLock.unlock();
            }
        }
    }

    private final class KeySetView extends AbstractSet<String> {
        @Override
        public Iterator<String> iterator() {
            return new DiskIterator<>() {
                @Override
                protected String create(String key, String value) {
                    return key;
                }
            };
        }

        @Override
        public int size() {
            return FileBackedMap.this.size();
        }

        @Override
        public boolean contains(Object o) {
            return FileBackedMap.this.containsKey(o);
        }

        @Override
        public boolean remove(Object o) {
            if (o != null && !(o instanceof String)) {
                return false;
            }
            writeLock.lock();
            try {
                boolean existed = FileBackedMap.this.containsKey(o);
                FileBackedMap.this.remove(o);
                return existed;
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public void clear() {
            FileBackedMap.this.clear();
        }
    }

    private final class ValuesView extends AbstractCollection<String> {
        @Override
        public Iterator<String> iterator() {
            return new DiskIterator<>() {
                @Override
                protected String create(String key, String value) {
                    return value;
                }
            };
        }

        @Override
        public int size() {
            return FileBackedMap.this.size();
        }

        @Override
        public boolean contains(Object o) {
            return FileBackedMap.this.containsValue(o);
        }

        @Override
        public void clear() {
            FileBackedMap.this.clear();
        }
    }

    private final class EntrySetView extends AbstractSet<Map.Entry<String, String>> {
        @Override
        public Iterator<Map.Entry<String, String>> iterator() {
            return new DiskIterator<>() {
                @Override
                protected Map.Entry<String, String> create(String key, String value) {
                    return new DiskEntry(key, value);
                }
            };
        }

        @Override
        public int size() {
            return FileBackedMap.this.size();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry<?, ?> e)) {
                return false;
            }
            Object k = e.getKey();
            Object v = e.getValue();
            if ((k != null && !(k instanceof String)) || (v != null && !(v instanceof String))) {
                return false;
            }
            writeLock.lock();
            try {
                if (!containsKey(k)) {
                    return false;
                }
                return Objects.equals(get(k), v);
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public boolean remove(Object o) {
            if (!(o instanceof Map.Entry<?, ?> e)) {
                return false;
            }
            return FileBackedMap.this.remove(e.getKey(), e.getValue());
        }

        @Override
        public void clear() {
            FileBackedMap.this.clear();
        }
    }

    private final class DiskEntry implements Map.Entry<String, String> {
        private final String key;
        private String value;

        DiskEntry(String key, String value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public String getValue() {
            return value;
        }

        @Override
        public String setValue(String newValue) {
            writeLock.lock();
            try {
                try {
                    String old = putInternal(key == null ? null : key.getBytes(UTF8),
                            newValue == null ? null : newValue.getBytes(UTF8), false);
                    this.value = newValue;
                    return old;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry<?, ?> other)) {
                return false;
            }
            return Objects.equals(key, other.getKey()) && Objects.equals(value, other.getValue());
        }

        @Override
        public int hashCode() {
            return (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }
    }

    // ==================================================================
    // Object 方法
    // ==================================================================

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Map<?, ?> other)) {
            return false;
        }
        if (other.size() != size()) {
            return false;
        }
        readLock.lock();
        try {
            for (Map.Entry<String, String> e : entrySet()) {
                String k = e.getKey();
                Object ov = other.get(k);
                if (!Objects.equals(e.getValue(), ov)) {
                    return false;
                }
                if (ov == null && !other.containsKey(k)) {
                    return false;
                }
            }
        } finally {
            readLock.unlock();
        }
        return true;
    }

    @Override
    public int hashCode() {
        readLock.lock();
        try {
            int h = 0;
            for (Map.Entry<String, String> e : entrySet()) {
                h += (e.getKey() == null ? 0 : e.getKey().hashCode())
                        ^ (e.getValue() == null ? 0 : e.getValue().hashCode());
            }
            return h;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public String toString() {
        readLock.lock();
        try {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> e : entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            return sb.append('}').toString();
        } finally {
            readLock.unlock();
        }
    }

    // ==================================================================
    // 统计 / 关闭
    // ==================================================================

    private Stats stats() {
        readLock.lock();
        try {
            double load = slotCount == 0 ? 0 : (double) (entryCount + tombstoneCount) / slotCount;
            long idxBytes;
            long datBytes;
            try {
                idxBytes = index.size();
                datBytes = data.size();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new Stats(path.toString(), dataPath.toString(), slotCount, entryCount,
                    tombstoneCount, idxBytes, datBytes, garbageBytes, load, modCount,
                    recoveredFromCrash, droppedOnRecovery, (flags & FLAG_DIRTY) != 0);
        } finally {
            readLock.unlock();
        }
    }

    private record Stats(String file, String dataFile, int slots, int entries, int tombstones,
                        long indexBytes, long dataBytes, long garbageBytes,
                        double loadFactor, int modCount,
                        boolean recoveredFromCrash, int droppedOnRecovery, boolean dirty) {
        @Override
        public String toString() {
            return "indexFile  = " + file + "  (" + indexBytes + " B)"
                    + "\n  dataFile   = " + dataFile + "  (" + dataBytes + " B)"
                    + "\n  slots      = " + slots
                    + "  entries = " + entries
                    + "  tombstones = " + tombstones
                    + "  load = " + String.format("%.3f", loadFactor)
                    + "\n  garbage    = " + garbageBytes + " B"
                    + "\n  dirty      = " + dirty
                    + (recoveredFromCrash
                    ? "  recovered = true (dropped " + droppedOnRecovery + " dangling slots)"
                    : "  recovered = false");
        }
    }

    /**
     * 正常关闭：清除脏标记 → 落盘 → 释放独占锁并关闭通道。下次打开即视为一致状态，不做对账。
     *
     * <p>这里的 force 是<b>无条件</b>的，与 {@code durable} 无关：close 是会话收尾的提交点，
     * 它落盘的「脏标记已清零」决定了下次打开是否触发对账。若受 durable 门控，非 durable 模式下
     * close 的清零可能留在页缓存里——进程退出后磁盘上仍是脏标记，下次打开被误判为崩溃现场。
     * {@code durable} 只描述运行期逐次写入的持久化档位，不适用于会话收尾。
     */
    @Override
    public void close() {
        writeLock.lock();
        try {
            if (index == null) {
                return;
            }
            try {
                if ((flags & FLAG_DIRTY) != 0) {
                    flags &= ~FLAG_DIRTY;
                    writeHeader();
                }
                data.force(false);
                index.force(false);
                closeChannels();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            writeLock.unlock();
        }
    }
}
