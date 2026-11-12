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

/**
 * {@code Map<String, String>} 的「磁盘内存扩展」实现——定位等同 {@code HashMap}，
 * 只是把键值字节放在磁盘文件里，让堆内存只承担一个可控大小的内存索引。
 *
 * <h2>会话作用域（不持久化）</h2>
 * 本类<b>不做数据持久化</b>：文件只是本次会话期间的「磁盘内存」，应用重启后数据由
 * 上层重新加载，因此没有崩溃恢复、对账、脏标记、提交线、fsync。生命周期：
 * <ul>
 *   <li><b>打开（构造）</b>：对 {@code <path>} 取独占 {@link FileLock}。
 *       文件已被其它进程占用 → 立即抛 {@link IOException}。
 *       打开成功即把同名旧文件清空（{@code truncate(0)}）——旧数据一律作废，
 *       语义等同于「重名就删除重建」，会话永远从空表开始。</li>
 *   <li><b>会话期间</b>：键值记录只追加写入数据文件，写完即可从同一通道读回
 *       （页缓存保证同进程可见性），不做任何 {@code force()}。</li>
 *   <li><b>close()</b>：释放独占锁、关闭通道并<b>删除数据文件</b>。幂等。</li>
 * </ul>
 *
 * <h2>索引在内存，数据在磁盘</h2>
 * 索引是纯内存开放定址表（状态 / hash / 偏移三个数组），条目规模可控
 * （每槽 13 B：1M 条目 ×2 槽 ≈ 33 MB），扩容只是内存 rehash，零 I/O。
 * 数据文件无文件头，只有一条条追加的记录；删除只把槽位标成墓碑，旧记录成为垃圾。
 * <b>没有垃圾整理 / 重建功能</b>：数据文件在会话内只增不减，容量规划由使用方负责。
 *
 * <h2>记录格式（数据文件）</h2>
 * <pre>
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
 *       共享缓冲为线程私有，读锁下的并发读互不干扰。</li>
 *   <li><b>跨进程</b>：打开时对数据文件持有独占 {@link FileLock}，第二个进程打开同一文件
 *       立即抛 {@link IOException}；进程崩溃 / 强杀时由系统随句柄自动释放。
 *       POSIX 上为建议性锁（只约束同样走本类的进程），Windows 上由系统强制。</li>
 *   <li>迭代器 fail-fast：任何结构性写操作（含其它线程）触发
 *       {@link ConcurrentModificationException}。</li>
 * </ul>
 */
public final class FileBackedMap implements Map<String, String>, Closeable {

    // ================= 常量 =================
    private static final int RECORD_OVERHEAD = 8;

    private static final byte ST_EMPTY = 0;
    private static final byte ST_OCCUPIED = 1;
    private static final byte ST_TOMBSTONE = 2;

    private static final int MIN_SLOTS = 8;
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    // ================= 实例状态 =================
    /** 数据文件（会话内充当磁盘内存；close() 时删除）。 */
    private final Path path;

    private RandomAccessFile dataRaf;
    private FileChannel data;

    /**
     * 跨进程独占锁：打开时取独占 {@link FileLock}，持有到 close()。
     * 进程崩溃 / 强杀时由操作系统随句柄一起释放，不会留下锁文件。
     */
    private FileLock dataLock;

    // ---- 内存索引（开放定址 + 墓碑）----
    private int slotCount;
    private byte[] states;     // ST_EMPTY / ST_OCCUPIED / ST_TOMBSTONE
    private int[] hashes;      // 键字节的 hash（ST_OCCUPIED 时有效）
    private long[] offsets;    // 记录在数据文件中的偏移

    private int entryCount;
    private int tombstoneCount;
    /** 追加游标 = 数据文件当前长度（内存维护）。 */
    private long dataEnd;
    /** 已被墓碑占用的记录字节数（仅统计展示：无整理功能，不触发任何重建）。 */
    private long garbageBytes;
    /** 结构性修改次数：迭代器 fail-fast 依据。 */
    private int modCount;

    /**
     * 进程内读写锁。纯读方法取读锁（并发），写方法与「读-改-写」复合方法取写锁（独占）。
     * 复合方法把回调函数也包在写锁内，回调中重入本实例任意方法安全（写锁可降级重入）。
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    // 复用缓冲：读锁下多个读者并发访问，必须线程私有（ThreadLocal），不能用共享实例字段。
    private final ThreadLocal<byte[]> intBytes = ThreadLocal.withInitial(() -> new byte[4]);
    private final ThreadLocal<byte[]> compareBuffer = ThreadLocal.withInitial(() -> new byte[1024]);

    // ==================================================================
    // 构造 / 打开
    // ==================================================================

    /**
     * 打开一次会话：取独占锁 → 清空同名旧文件（旧数据作废，会话从空表开始）。
     *
     * @throws IOException 文件被其它进程 / 本进程其它实例占用，或路径不可写
     */
    public FileBackedMap(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        this.path = path.toAbsolutePath();

        Path parent = this.path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // openDataFile 必须在 try 内：取锁失败时通道已经打开，不关闭就会泄漏句柄
        // （Windows 上泄漏的句柄等同于持有文件锁，会让后续打开一直失败）。
        try {
            openDataFile();
            acquireDataLock();
            // 会话从空开始：旧内容一律作废。持锁后 truncate(0) 与「删除重建」等效，
            // 且不经历「已删除、未重建」的窗口，天然无竞态。
            data.truncate(0);
        } catch (IOException e) {
            closeChannels();
            throw e;
        }

        slotCount = MIN_SLOTS;
        states = new byte[MIN_SLOTS];
        hashes = new int[MIN_SLOTS];
        offsets = new long[MIN_SLOTS];
        entryCount = 0;
        tombstoneCount = 0;
        dataEnd = 0;
        garbageBytes = 0;
        modCount = 0;
    }

    private void openDataFile() throws IOException {
        dataRaf = new RandomAccessFile(path.toFile(), "rw");
        data = dataRaf.getChannel();
    }

    /**
     * 对数据通道取独占锁，确立「一个文件同一时刻只有一个实例」的协议前提。
     * 同进程内重复加锁会抛 {@link OverlappingFileLockException}（FileChannel 语义），
     * 统一转成带路径的 {@link IOException}。
     */
    private void acquireDataLock() throws IOException {
        try {
            FileLock fl = data.tryLock(0, Long.MAX_VALUE, false);
            if (fl == null) {
                throw new IOException("数据文件已被其它进程占用（无法取得独占锁）: " + path);
            }
            dataLock = fl;
        } catch (OverlappingFileLockException e) {
            throw new IOException("数据文件已在本进程内被打开（无法重复取得独占锁）: " + path, e);
        }
    }

    private void closeChannels() throws IOException {
        if (dataLock != null) {
            try {
                dataLock.release();
            } catch (OverlappingFileLockException | ClosedChannelException ignore) {
                // 通道已失效时锁已随句柄释放，无需处理
            }
            dataLock = null;
        }
        if (dataRaf != null) {
            dataRaf.close();
            dataRaf = null;
            data = null;
        }
    }

    // ==================================================================
    // 底层定位读写（普通文件 I/O，不使用内存映射，全程无 fsync）
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

    private int readInt(long pos) throws IOException {
        byte[] ib = intBytes.get();
        readAt(data, pos, ib, 0, 4);
        return ((ib[0] & 0xFF) << 24)
                | ((ib[1] & 0xFF) << 16)
                | ((ib[2] & 0xFF) << 8)
                | (ib[3] & 0xFF);
    }

    // ==================================================================
    // 记录（数据文件）
    // ==================================================================

    private record Record(String key, String value) {
    }

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
     * 追加一条记录：写到当前数据文件末尾（dataEnd == 文件长度），只推进游标。
     * 同一通道随后即可读回该记录（页缓存对写进程可见），不做 force()。
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
    // 哈希与内存索引探测
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
            byte st = states[i];
            if (st == ST_EMPTY) {
                return -1;
            }
            if (st == ST_OCCUPIED && hashes[i] == hash && keyEquals(offsets[i], keyBytes)) {
                return i;
            }
            i = (i + 1) & mask;
        }
        return -1;
    }

    // ==================================================================
    // 写操作
    // ==================================================================

    /**
     * 单键写入：追加新记录 → 更新内存槽位。记录字节写入文件通道后即可见，无 fsync。
     * 值未变时不写任何字节。
     */
    private String putInternal(byte[] keyBytes, byte[] valueBytes) throws IOException {
        int hash = hashBytes(keyBytes);
        int mask = slotCount - 1;
        int i = hash & mask;
        int firstTomb = -1;
        int foundIndex = -1;

        for (int probes = 0; probes < slotCount; probes++) {
            byte st = states[i];
            if (st == ST_EMPTY) {
                break;
            }
            if (st == ST_TOMBSTONE) {
                if (firstTomb < 0) {
                    firstTomb = i;
                }
            } else if (hashes[i] == hash && keyEquals(offsets[i], keyBytes)) {
                foundIndex = i;
                break;
            }
            i = (i + 1) & mask;
        }

        if (foundIndex >= 0) {
            long offset = offsets[foundIndex];
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
                return oldValue;   // 值未变，不写任何字节
            }

            // 记录不可变，旧记录成为垃圾（无整理功能，仅计数）
            garbageBytes += RECORD_OVERHEAD + Math.max(keyLen, 0) + Math.max(oldValLen, 0);

            long newOffset = appendRecord(keyBytes, valueBytes);
            offsets[foundIndex] = newOffset;
            return oldValue;
        }

        // 新键：追加记录 → 发布内存槽位（复用第一个墓碑或探测到的空槽）
        long newOffset = appendRecord(keyBytes, valueBytes);
        int target = (firstTomb >= 0) ? firstTomb : i;
        if (firstTomb >= 0) {
            tombstoneCount--;
        }
        states[target] = ST_OCCUPIED;
        hashes[target] = hash;
        offsets[target] = newOffset;
        entryCount++;
        modCount++;
        maintain();
        return null;
    }

    private String removeInternal(byte[] keyBytes, boolean allowMaintain) throws IOException {
        int hash = hashBytes(keyBytes);
        int mask = slotCount - 1;
        int i = hash & mask;
        for (int probes = 0; probes < slotCount; probes++) {
            byte st = states[i];
            if (st == ST_EMPTY) {
                return null;
            }
            if (st == ST_OCCUPIED && hashes[i] == hash && keyEquals(offsets[i], keyBytes)) {
                String oldValue = readValueAt(offsets[i]);
                garbageBytes += recordLength(offsets[i]);
                states[i] = ST_TOMBSTONE;   // 只动内存索引，数据区不动
                hashes[i] = 0;
                offsets[i] = -1;
                tombstoneCount++;
                entryCount--;
                modCount++;
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
    // 维护：只有扩容（内存 rehash），没有垃圾整理 / 重建
    // ==================================================================

    private boolean overloaded() {
        return (long) (entryCount + tombstoneCount) * 3 >= (long) slotCount * 2;
    }

    private void maintain() throws IOException {
        if (overloaded()) {
            growIndex(slotCount * 2);
        }
    }

    /**
     * 扩容：纯内存 rehash，零 I/O，数据文件一个字节都不动（槽位里的偏移原样搬）。
     * 墓碑在扩容时丢弃。
     */
    private void growIndex(int newSlotCount) {
        newSlotCount = Math.max(MIN_SLOTS, newSlotCount);
        int newMask = newSlotCount - 1;

        byte[] newStates = new byte[newSlotCount];
        int[] newHashes = new int[newSlotCount];
        long[] newOffsets = new long[newSlotCount];

        for (int j = 0; j < slotCount; j++) {
            if (states[j] != ST_OCCUPIED) {
                continue;
            }
            int idx = hashes[j] & newMask;
            while (newStates[idx] != ST_EMPTY) {
                idx = (idx + 1) & newMask;
            }
            newStates[idx] = ST_OCCUPIED;
            newHashes[idx] = hashes[j];
            newOffsets[idx] = offsets[j];
        }

        states = newStates;
        hashes = newHashes;
        offsets = newOffsets;
        slotCount = newSlotCount;
        tombstoneCount = 0;
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
                    if (states[i] != ST_OCCUPIED) {
                        continue;
                    }
                    long offset = offsets[i];
                    long vp = valueLenPos(offset);
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
                return idx < 0 ? null : readValueAt(offsets[idx]);
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

    /**
     * 批量写入：逐条「追加记录 + 发布内存槽位」，跨越负载阈值（2/3）即时内存扩容。
     * 全程无 fsync，批量与逐写的单条成本相同（本类逐写本身就没有落盘同步）。
     */
    @Override
    public void putAll(Map<? extends String, ? extends String> m) {
        Objects.requireNonNull(m, "m");
        if (m.isEmpty()) {
            return;
        }
        writeLock.lock();
        try {
            try {
                for (Map.Entry<? extends String, ? extends String> e : m.entrySet()) {
                    putInternal(e.getKey() == null ? null : e.getKey().getBytes(UTF8),
                            e.getValue() == null ? null : e.getValue().getBytes(UTF8));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 清空：数据文件截断为 0（旧记录全部作废），索引重置为最小容量。
     * 因为独占锁在手且无持久化语义，无需任何重建提交线。
     */
    @Override
    public void clear() {
        writeLock.lock();
        try {
            try {
                data.truncate(0);
                slotCount = MIN_SLOTS;
                states = new byte[MIN_SLOTS];
                hashes = new int[MIN_SLOTS];
                offsets = new long[MIN_SLOTS];
                entryCount = 0;
                tombstoneCount = 0;
                dataEnd = 0;
                garbageBytes = 0;
                modCount++;
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
                    if (states[i] != ST_OCCUPIED) {
                        continue;
                    }
                    if (modCount != expected) {
                        throw new ConcurrentModificationException();
                    }
                    Record r = readRecordAt(offsets[i]);
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
                    if (states[i] != ST_OCCUPIED) {
                        continue;
                    }
                    if (modCount != expected) {
                        throw new ConcurrentModificationException();
                    }
                    long offset = offsets[i];
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
                    // 一律追加，不原地覆盖
                    garbageBytes += RECORD_OVERHEAD + Math.max(keyLen, 0) + Math.max(valLen, 0);
                    offsets[i] = appendRecord(kb, nb);
                }
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
                        if (states[scanIndex] == ST_OCCUPIED) {
                            Record r = readRecordAt(offsets[scanIndex]);
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
                            newValue == null ? null : newValue.getBytes(UTF8));
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

    private Path dataPath() {
        return path;
    }

    private Stats stats() {
        readLock.lock();
        try {
            double load = slotCount == 0 ? 0 : (double) (entryCount + tombstoneCount) / slotCount;
            long datBytes;
            try {
                datBytes = data.size();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            // 索引驻留内存的近似字节数（三个数组的载荷）
            long indexBytes = (long) slotCount * (1 + 4 + 8);
            return new Stats(path.toString(), slotCount, entryCount, tombstoneCount,
                    indexBytes, datBytes, garbageBytes, load, modCount);
        } finally {
            readLock.unlock();
        }
    }

    private record Stats(String dataFile, int slots, int entries, int tombstones,
                         long indexBytes, long dataBytes, long garbageBytes,
                         double loadFactor, int modCount) {
        @Override
        public String toString() {
            return "dataFile   = " + dataFile + "  (" + dataBytes + " B)"
                    + "\n  slots      = " + slots
                    + "  entries = " + entries
                    + "  tombstones = " + tombstones
                    + "  load = " + String.format("%.3f", loadFactor)
                    + "\n  index(mem) = " + indexBytes + " B"
                    + "\n  garbage    = " + garbageBytes + " B (无整理，只增不减)";
        }
    }

    /**
     * 结束会话：释放独占锁、关闭通道并删除数据文件。幂等。
     *
     * <p>数据不落盘、不保留——会话语义决定 close 后文件必不存在，
     * 下次打开永远是全新的空会话。
     */
    @Override
    public void close() {
        writeLock.lock();
        try {
            if (dataRaf == null) {
                return;
            }
            try {
                closeChannels();
                deleteWithRetry(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /** Windows 上句柄刚释放时删文件可能瞬时撞占用（杀软扫描等），做有界重试。 */
    private static void deleteWithRetry(Path p) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Files.deleteIfExists(p);
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }
}
