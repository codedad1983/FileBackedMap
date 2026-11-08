package com.codedad.fbmap;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 *   <li><b>单点提交</b>：重建（整理 / 清空）时新建下一代数据文件 {@code <path>.data.g<N>}，
 *       全部写完并 fsync 后，才写新索引临时文件；真正提交只做一件事 —— 重命名索引文件。
 *       因此崩溃后只可能落在「旧索引 + 旧数据」或「新索引 + 新数据」两种自洽状态之一，
 *       不会出现「新索引 + 旧数据」这种偏移错位、静默返回错值的情况。</li>
 * </ol>
 * 打开时还会做对账（脏标记 / CRC32 校验失败、数据长度与头不一致时）：重算条目数、墓碑数、
 * 垃圾字节数，并把指向越界偏移的悬空槽位改成墓碑丢弃。
 *
 * <h2>文件布局</h2>
 * <pre>
 * 索引文件 &lt;path&gt;
 * ─────────────────────────────────────────────
 * 0      64 B 文件头
 *   +0  "FBMP"   +4 版本(=2)
 *   +8  槽位数   +12 条目数   +16 墓碑数
 *   +20 flags(bit0=脏: 上次未正常关闭/多步更新未完成)
 *   +24 数据区终点（= 数据文件长度，打开时以实际长度校准）
 *   +32 垃圾字节数
 *   +40 数据文件世代号 gen
 *   +48 文件头 CRC32（覆盖 0..47 字节；0 表示旧格式无校验）
 * 64    槽位区  slotCount × 16 B
 *   槽位 = [1B 状态][3B 填充][4B hash][8B 数据文件偏移]
 *
 * 数据文件 &lt;path&gt;.data  (gen 0)  或  &lt;path&gt;.data.g&lt;gen&gt;  (gen &gt; 0)
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
 */
public final class FileBackedMap implements Map<String, String>, Closeable {

    // ================= 常量 =================
    private static final byte[] MAGIC = {'F', 'B', 'M', 'P'};
    private static final int FORMAT_VERSION = 2;

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
    /** 当前索引所绑定的数据文件世代；数据文件名由它决定，因此只需换索引即可完成切换。 */
    private static final int H_DATA_GEN = 40;
    /** 文件头 CRC32，覆盖 0..47 字节；0 表示没有写过校验（旧格式文件）。 */
    private static final int H_CRC = 48;
    private static final int CRC_SPAN = 48;

    private static final int FLAG_DIRTY = 1;

    /** 重建 / 对账时按块读写槽位区，避免逐槽位的系统调用开销。 */
    private static final int SLOT_BLOCK = 4096;

    // ================= 实例状态 =================
    private final Path path;       // 索引文件
    private Path dataPath;         // 当前使用的数据文件（名字由世代号决定，故可整体切换）
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
    private int dataGen;
    private int flags;
    private int modCount;

    /** 本次打开是否执行过崩溃对账；对账时丢弃了多少指向越界偏移的槽位。 */
    private boolean recoveredFromCrash;
    private int droppedOnRecovery;
    /** 文件头 CRC32 是否与内容匹配（旧格式文件没有 CRC 时视为通过）。 */
    private boolean crcValid = true;

    // 复用缓冲
    private final byte[] slotBytes = new byte[SLOT_SIZE];
    private final ByteBuffer slotBuf = ByteBuffer.wrap(slotBytes);
    private final byte[] headerBytes = new byte[HEADER_SIZE];
    private final ByteBuffer headerBuf = ByteBuffer.wrap(headerBytes);
    private final byte[] intBytes = new byte[4];
    private byte[] compareBuffer = new byte[1024];

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

        Path gen0Data = dataPathFor(0);
        boolean hasIndex = Files.exists(this.path) && Files.size(this.path) > 0;
        if (!hasIndex) {
            // 只有索引缺失时的兜底检查：gen0 数据文件如果非空，说明索引被误删。
            if (Files.exists(gen0Data) && Files.size(gen0Data) > 0) {
                throw new IOException("数据文件存在但索引文件缺失: " + this.path);
            }
            createEmpty();
            cleanupOldGenerations();
            return;
        }

        openIndexFile();
        try {
            loadHeader();
            dataPath = dataPathFor(dataGen);
            if (!Files.exists(dataPath)) {
                throw new IOException("索引声明的数据文件缺失: " + dataPath
                        + "（世代 " + dataGen + "）");
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
            cleanupOldGenerations();
        } catch (IOException e) {
            closeChannels();
            throw e;
        }
    }

    public static FileBackedMap open(Path path) throws IOException {
        return new FileBackedMap(path);
    }

    /** gen 0 沿用历史命名 {@code <path>.data}；重建后代沿用 {@code <path>.data.g<n>}。 */
    private static String dataFileName(Path indexFile, int gen) {
        String base = indexFile.getFileName().toString();
        return gen == 0 ? base + ".data" : base + ".data.g" + gen;
    }

    private Path dataPathFor(int gen) {
        if (gen < 0) {
            gen = 0;
        }
        return path.resolveSibling(dataFileName(path, gen));
    }

    private void openIndexFile() throws IOException {
        indexRaf = new RandomAccessFile(path.toFile(), "rw");
        index = indexRaf.getChannel();
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
        if (indexRaf != null) {
            indexRaf.close();
            indexRaf = null;
            index = null;
        }
        if (dataRaf != null) {
            dataRaf.close();
            dataRaf = null;
            data = null;
        }
    }

    /** 删除除当前世代以外的数据文件，以及残留的临时索引文件（崩溃后的孤儿产物）。 */
    private void cleanupOldGenerations() throws IOException {
        Path dir = path.getParent();
        if (dir == null) {
            return;
        }
        String base = path.getFileName().toString();
        Path current = dataPath.toAbsolutePath();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, base + ".data*")) {
            for (Path f : ds) {
                String n = f.getFileName().toString();
                if (!(n.equals(base + ".data") || n.startsWith(base + ".data.g"))) {
                    continue;
                }
                if (f.toAbsolutePath().equals(current)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignore) {
                    // 删不掉就留着，不影响正确性（只是占磁盘）
                }
            }
        }
        try {
            Files.deleteIfExists(path.resolveSibling(base + ".index.tmp"));
        } catch (IOException ignore) {
            // 同上
        }
    }

    private void createEmpty() throws IOException {
        slotCount = MIN_SLOTS;
        entryCount = 0;
        tombstoneCount = 0;
        dataEnd = 0;
        garbageBytes = 0;
        dataGen = 0;
        flags = FLAG_DIRTY;
        modCount = 0;

        dataPath = dataPathFor(0);
        openIndexFile();
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
        readAt(index, 0, headerBytes, 0, HEADER_SIZE);
        headerBuf.clear();
        for (int i = 0; i < MAGIC.length; i++) {
            if (headerBytes[i] != MAGIC[i]) {
                throw new IOException("不是 FileBackedMap 索引文件: " + path);
            }
        }
        int version = headerBuf.getInt(4);
        if (version != FORMAT_VERSION) {
            throw new IOException("不支持的格式版本: " + version
                    + "（当前格式版本为 " + FORMAT_VERSION + "，索引 + 数据双文件布局）");
        }
        slotCount = headerBuf.getInt(H_SLOTS);
        entryCount = headerBuf.getInt(H_ENTRIES);
        tombstoneCount = headerBuf.getInt(H_TOMB);
        flags = headerBuf.getInt(H_FLAGS);
        dataEnd = headerBuf.getLong(H_DATA_END);
        garbageBytes = headerBuf.getLong(H_GARBAGE);
        dataGen = headerBuf.getInt(H_DATA_GEN);
        int storedCrc = headerBuf.getInt(H_CRC);

        CRC32 c = new CRC32();
        c.update(headerBytes, 0, CRC_SPAN);
        // CRC 为 0 表示旧格式文件从未写过校验，此时不做校验但按“已正常关闭”处理。
        crcValid = storedCrc == 0 || storedCrc == (int) c.getValue();

        if (slotCount < MIN_SLOTS || (slotCount & (slotCount - 1)) != 0) {
            throw new IOException("槽位数非法: " + slotCount);
        }
        if (dataGen < 0) {
            throw new IOException("数据世代号非法: " + dataGen);
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
        writeHeaderTo(index, slotCount, entryCount, tombstoneCount, dataEnd, garbageBytes, flags, dataGen);
    }

    private static ByteBuffer buildHeaderBuffer(int slots, int entries, int tomb, long dataEnd,
                                                long garbage, int flags, int gen) {
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
        hb.putInt(H_DATA_GEN, gen);
        CRC32 c = new CRC32();
        c.update(hb.array(), 0, CRC_SPAN);
        hb.putInt(H_CRC, (int) c.getValue());
        hb.clear();
        return hb;
    }

    private static void writeHeaderTo(FileChannel ch, int slots, int entries, int tomb, long dataEnd,
                                      long garbage, int flags, int gen) throws IOException {
        writeAt(ch, 0, buildHeaderBuffer(slots, entries, tomb, dataEnd, garbage, flags, gen));
    }

    /** 数据先落盘、索引后落盘：这样索引指向的记录一定已经持久化。 */
    private void syncIfDurable() throws IOException {
        if (durable) {
            data.force(false);
            index.force(false);
        }
    }

    /** 手动把两个文件都刷到磁盘（顺序：数据 → 索引）。 */
    public void flush() {
        synchronized (this) {
            try {
                data.force(false);
                index.force(false);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public Path path() {
        return path;
    }

    public Path dataPath() {
        return dataPath;
    }

    public int dataGeneration() {
        return dataGen;
    }

    /** 本次打开是否做过崩溃对账。 */
    public boolean recoveredFromCrash() {
        return recoveredFromCrash;
    }

    /** 对账时被丢弃的悬空槽位数量。 */
    public int droppedOnRecovery() {
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
        readAt(data, pos, intBytes, 0, 4);
        return ((intBytes[0] & 0xFF) << 24)
                | ((intBytes[1] & 0xFF) << 16)
                | ((intBytes[2] & 0xFF) << 8)
                | (intBytes[3] & 0xFF);
    }

    // ==================================================================
    // 槽位（索引文件）
    // ==================================================================

    private record Slot(byte state, int hash, long offset) {
    }

    private record Record(String key, String value) {
    }

    private Slot readSlot(int i) throws IOException {
        readAt(index, HEADER_SIZE + (long) i * SLOT_SIZE, slotBytes, 0, SLOT_SIZE);
        slotBuf.clear();
        byte state = slotBuf.get();
        slotBuf.get(); slotBuf.get(); slotBuf.get();
        int hash = slotBuf.getInt();
        long offset = slotBuf.getLong();
        return new Slot(state, hash, offset);
    }

    private void writeSlot(int i, byte state, int hash, long offset) throws IOException {
        slotBuf.clear();
        slotBuf.put(state);
        slotBuf.put((byte) 0).put((byte) 0).put((byte) 0);
        slotBuf.putInt(hash);
        slotBuf.putLong(offset);
        slotBuf.flip();
        writeAt(index, HEADER_SIZE + (long) i * SLOT_SIZE, slotBuf);
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
        if (compareBuffer.length < keyLen) {
            compareBuffer = new byte[keyLen];
        }
        readAt(offset + 4, compareBuffer, 0, keyLen);
        for (int i = 0; i < keyLen; i++) {
            if (compareBuffer[i] != keyBytes[i]) {
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

    /** 读取值字节；返回 {@code null} 表示 null 值，返回长度为 0 的数组表示空串。 */
    private byte[] readValueBytesAt(long offset) throws IOException {
        int keyLen = readInt(offset);
        long vp = offset + 4 + Math.max(keyLen, 0);
        int valLen = readInt(vp);
        if (valLen == -1) {
            return null;
        }
        byte[] vb = new byte[valLen];
        if (valLen > 0) {
            readAt(vp + 4, vb, 0, valLen);
        }
        return vb;
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

    public void compact() {
        synchronized (this) {
            try {
                rebuild(Math.max(MIN_SLOTS, slotCount), true);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * 扩容：只重建索引文件。数据文件不动，槽位中的偏移原样搬过去。
     * 写入量 = newSlotCount × 16 B（100 万条目时约 32 MB），与数据区大小无关。
     *
     * 提交方式是「重命名索引」，数据文件（同一世代）在提交前后都完全一致，
     * 因此崩溃只会落在旧索引或新索引上，两者指向的偏移都合法。
     */
    private void growIndex(int newSlotCount) throws IOException {
        newSlotCount = Math.max(MIN_SLOTS, newSlotCount);
        Path tmp = path.resolveSibling(path.getFileName() + ".index.tmp");
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
                    flags | FLAG_DIRTY, dataGen);
            if (durable) {
                out.force(false);
            }
        }

        // ---- 3) 单点提交：重命名索引；数据文件属于同一世代，不受影响 ----
        indexRaf.close();
        indexRaf = null;
        index = null;
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
     * 重建：把存活记录连续写进「下一代」数据文件，重建索引，然后只重命名索引完成提交。
     * {@code keepRecords=false} 用于 clear()。
     *
     * 顺序严格为：写新数据（fsync）→ 写新索引临时文件（fsync）→ 重命名索引（提交点）
     * → 重新打开 → 删除旧世代数据文件。任何一步崩溃都只会留下「旧索引 + 旧数据」
     * 或者「新索引 + 新数据」，不会错位。
     */
    private void rebuild(int newSlotCount, boolean keepRecords) throws IOException {
        newSlotCount = Math.max(MIN_SLOTS, newSlotCount);
        int newGen = dataGen + 1;
        Path newDataPath = dataPathFor(newGen);
        Path tmpIndex = path.resolveSibling(path.getFileName() + ".index.tmp");

        int newMask = newSlotCount - 1;
        byte[] dstState = new byte[newSlotCount];
        int[] dstHash = new int[newSlotCount];
        long[] dstOffset = new long[newSlotCount];

        final int BLK_BYTES = SLOT_BLOCK * SLOT_SIZE;
        byte[] inBuf = new byte[BLK_BYTES];

        long newEnd = 0;
        int newEntries = 0;

        // ---- 1) 先把存活记录写进新世代数据文件 ----
        try (RandomAccessFile rd = new RandomAccessFile(newDataPath.toFile(), "rw");
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
                        int total = (int) len;
                        byte[] rec = new byte[total];
                        readAt(data, off, rec, 0, total);
                        writeAt(outData, newEnd, ByteBuffer.wrap(rec));

                        long nOff = newEnd;
                        newEnd += total;

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
            if (durable) {
                outData.force(false);
            }
        }

        // ---- 2) 写新索引临时文件 ----
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

            writeHeaderTo(outIndex, newSlotCount, newEntries, 0, newEnd, 0,
                    flags | FLAG_DIRTY, newGen);
            if (durable) {
                outIndex.force(false);
            }
        }

        // ---- 3) 提交：只重命名索引 ----
        closeChannels();
        try {
            Files.move(tmpIndex, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            openIndexFile();
            openDataFile(dataPath);
            try {
                Files.deleteIfExists(newDataPath);
            } catch (IOException ignore) {
                // 孤儿文件，下次打开时会被清理
            }
            throw e;
        }

        openIndexFile();
        openDataFile(newDataPath);

        slotCount = newSlotCount;
        entryCount = newEntries;
        tombstoneCount = 0;
        dataEnd = newEnd;
        garbageBytes = 0;
        dataGen = newGen;
        modCount++;

        // ---- 4) 旧世代数据文件已无人引用，删除 ----
        cleanupOldGenerations();
    }

    // ==================================================================
    // Map：读取
    // ==================================================================

    @Override
    public int size() {
        synchronized (this) {
            return entryCount;
        }
    }

    @Override
    public boolean isEmpty() {
        synchronized (this) {
            return entryCount == 0;
        }
    }

    @Override
    public boolean containsKey(Object key) {
        if (key != null && !(key instanceof String)) {
            return false;
        }
        synchronized (this) {
            try {
                byte[] kb = key == null ? null : ((String) key).getBytes(UTF8);
                return findSlotIndex(hashBytes(kb), kb) >= 0;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public boolean containsValue(Object value) {
        if (value != null && !(value instanceof String)) {
            return false;
        }
        byte[] target = value == null ? null : ((String) value).getBytes(UTF8);
        synchronized (this) {
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
        }
        return false;
    }

    @Override
    public String get(Object key) {
        if (key != null && !(key instanceof String)) {
            return null;
        }
        synchronized (this) {
            try {
                byte[] kb = key == null ? null : ((String) key).getBytes(UTF8);
                int idx = findSlotIndex(hashBytes(kb), kb);
                return idx < 0 ? null : readValueAt(readSlot(idx).offset);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
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
        synchronized (this) {
            try {
                return putInternal(key == null ? null : key.getBytes(UTF8),
                        value == null ? null : value.getBytes(UTF8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public String remove(Object key) {
        if (key != null && !(key instanceof String)) {
            return null;
        }
        synchronized (this) {
            try {
                return removeInternal(key == null ? null : ((String) key).getBytes(UTF8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public boolean remove(Object key, Object value) {
        if ((key != null && !(key instanceof String)) || (value != null && !(value instanceof String))) {
            return false;
        }
        synchronized (this) {
            if (!containsKey(key)) {
                return false;
            }
            if (!Objects.equals(get(key), value)) {
                return false;
            }
            remove(key);
            return true;
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
        synchronized (this) {
            try {
                rebuild(MIN_SLOTS, false);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public String putIfAbsent(String key, String value) {
        synchronized (this) {
            String oldValue = get(key);
            if (oldValue == null) {
                put(key, value);
                return null;
            }
            return oldValue;
        }
    }

    @Override
    public boolean replace(String key, String oldValue, String newValue) {
        synchronized (this) {
            if (!containsKey(key) || !Objects.equals(get(key), oldValue)) {
                return false;
            }
            put(key, newValue);
            return true;
        }
    }

    @Override
    public String replace(String key, String value) {
        synchronized (this) {
            if (!containsKey(key)) {
                return null;
            }
            String old = get(key);
            put(key, value);
            return old;
        }
    }

    @Override
    public String compute(String key, BiFunction<? super String, ? super String, ? extends String> f) {
        Objects.requireNonNull(f, "remappingFunction");
        synchronized (this) {
            String oldValue = get(key);
            String newValue = f.apply(key, oldValue);
            if (newValue == null) {
                remove(key);
                return null;
            }
            put(key, newValue);
            return newValue;
        }
    }

    @Override
    public String computeIfAbsent(String key, Function<? super String, ? extends String> f) {
        Objects.requireNonNull(f, "mappingFunction");
        synchronized (this) {
            String oldValue = get(key);
            if (oldValue != null) {
                return oldValue;
            }
            String newValue = f.apply(key);
            if (newValue != null) {
                put(key, newValue);
            }
            return newValue;
        }
    }

    @Override
    public String computeIfPresent(String key, BiFunction<? super String, ? super String, ? extends String> f) {
        Objects.requireNonNull(f, "remappingFunction");
        synchronized (this) {
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
        }
    }

    @Override
    public String merge(String key, String value,
                        BiFunction<? super String, ? super String, ? extends String> f) {
        Objects.requireNonNull(f, "remappingFunction");
        synchronized (this) {
            String oldValue = get(key);
            String newValue = (oldValue == null) ? value : f.apply(oldValue, value);
            if (newValue == null) {
                remove(key);
                return null;
            }
            put(key, newValue);
            return newValue;
        }
    }

    // ==================================================================
    // 批量操作
    // ==================================================================

    @Override
    public void forEach(BiConsumer<? super String, ? super String> action) {
        Objects.requireNonNull(action, "action");
        synchronized (this) {
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
        }
    }

    @Override
    public void replaceAll(BiFunction<? super String, ? super String, ? extends String> f) {
        Objects.requireNonNull(f, "function");
        synchronized (this) {
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
            advance();
        }

        protected abstract T create(String key, String value);

        private void advance() {
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
            try {
                byte[] kb = lastKey == null ? null : lastKey.getBytes(UTF8);
                removeInternal(kb, false);
                expected = modCount;
                lastKey = null;
                lastReady = false;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
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
            synchronized (FileBackedMap.this) {
                boolean existed = FileBackedMap.this.containsKey(o);
                FileBackedMap.this.remove(o);
                return existed;
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
            synchronized (FileBackedMap.this) {
                if (!containsKey(k)) {
                    return false;
                }
                return Objects.equals(get(k), v);
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
            synchronized (FileBackedMap.this) {
                try {
                    String old = putInternal(key == null ? null : key.getBytes(UTF8),
                            newValue == null ? null : newValue.getBytes(UTF8), false);
                    this.value = newValue;
                    return old;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
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
        synchronized (this) {
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
        }
        return true;
    }

    @Override
    public int hashCode() {
        synchronized (this) {
            int h = 0;
            for (Map.Entry<String, String> e : entrySet()) {
                h += (e.getKey() == null ? 0 : e.getKey().hashCode())
                        ^ (e.getValue() == null ? 0 : e.getValue().hashCode());
            }
            return h;
        }
    }

    @Override
    public String toString() {
        synchronized (this) {
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
        }
    }

    // ==================================================================
    // 统计 / 关闭
    // ==================================================================

    public Stats stats() {
        synchronized (this) {
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
                    dataGen, recoveredFromCrash, droppedOnRecovery, (flags & FLAG_DIRTY) != 0);
        }
    }

    public record Stats(String file, String dataFile, int slots, int entries, int tombstones,
                        long indexBytes, long dataBytes, long garbageBytes,
                        double loadFactor, int modCount, int dataGen,
                        boolean recoveredFromCrash, int droppedOnRecovery, boolean dirty) {
        @Override
        public String toString() {
            return "indexFile  = " + file + "  (" + indexBytes + " B)"
                    + "\n  dataFile   = " + dataFile + "  (" + dataBytes + " B, gen " + dataGen + ")"
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

    /** 正常关闭：清除脏标记 → 落盘 → 关闭。下次打开即视为一致状态，不做对账。 */
    @Override
    public void close() {
        synchronized (this) {
            try {
                if (index == null) {
                    return;
                }
                if ((flags & FLAG_DIRTY) != 0) {
                    flags &= ~FLAG_DIRTY;
                    writeHeader();
                }
                if (durable) {
                    data.force(false);
                    index.force(false);
                }
                closeChannels();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
