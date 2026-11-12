package com.codedad.fbmap.test;

import com.codedad.fbmap.FileBackedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发与独占锁专项测试（黑盒，仅用 public API + 进程外 java 子进程）。
 *
 * <ul>
 *   <li>P1 跨进程独占：第二个 JVM 进程打开同一文件必须失败；持有进程被杀后锁随句柄释放。</li>
 *   <li>P2 同进程重复打开：必须失败（同一 FileChannel 重复加锁 → 统一转 IOException）。</li>
 *   <li>P3 多读一写并发：读走读锁可并发，写走写锁独占，最终状态一致、无异常。</li>
 *   <li>P4 并发读互不干扰：共享缓冲已线程私有，多线程同时读不同键结果全部正确。</li>
 *   <li>P5 回调重入不死锁：forEach / compute 的回调里再调用本实例方法（写锁可重入）。</li>
 *   <li>P6 迭代器并发扫描：两个线程同时遍历，各自得到完整一致的快照。</li>
 *   <li>P7 close() 删除数据文件：close 后文件不存在，重开是全新空会话。</li>
 * </ul>
 */
final class ConcurrencyTest {

    @TempDir
    Path dir;

    private static Path fresh(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        Files.deleteIfExists(p);
        return p;
    }

    // ------------------------------------------------------------------
    // P1 跨进程独占锁
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P1 第二个进程打开同一文件必须失败；其被杀后锁释放、可再次打开")
    void crossProcessExclusiveLock() throws Exception {
        Path p = fresh(dir, "xproc.fbmap");

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java")
                .toAbsolutePath().toString();
        String cp = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", cp,
                LockHolder.class.getName(), p.toString(), "10");
        pb.redirectErrorStream(true);
        Process holder = pb.start();

        String line;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(holder.getInputStream(),
                        java.nio.charset.Charset.defaultCharset()))) {   // Windows 控制台默认 GBK
            long deadline = System.currentTimeMillis() + 20_000;
            line = null;
            while (System.currentTimeMillis() < deadline) {
                line = r.readLine();
                if (line == null) {
                    break;
                }
                if (line.startsWith("LOCKED")) {
                    break;
                }
            }
        }
        assertNotNull(line, "子进程应输出 LOCKED（未读到说明子进程启动失败）");
        assertTrue(line.startsWith("LOCKED"), "子进程应成功打开并持锁，实际输出: " + line);

        // 持锁期间本进程再开必须失败
        IOException ioe = assertThrows(IOException.class, () -> {
            try (FileBackedMap leaked = new FileBackedMap(p)) {
                leaked.size();
            }
        }, "第二个进程持有独占锁时打开应抛 IOException");
        assertTrue(ioe.getMessage().contains("独占锁"), "异常信息应说明锁冲突: " + ioe.getMessage());

        holder.destroy();
        assertTrue(holder.waitFor(15, java.util.concurrent.TimeUnit.SECONDS), "子进程应在销毁后退出");

        // 进程被杀 → 系统随句柄释放锁 → 可正常打开。
        // 强杀不会走 close()，文件残留；但下次打开立刻清空，会话从空开始。
        // Windows 上句柄表拆除可能略晚于进程退出，给短暂重试窗口。
        FileBackedMap m = openWithRetry(p, 10);
        try {
            assertTrue(m.isEmpty(), "崩溃残留文件在重开时被清空，会话从空开始");
        } finally {
            m.close();
        }
        assertFalse(Files.exists(p), "close 后文件被删除");
    }

    /** 打开并重试：进程退出后锁释放可能有微小延迟。 */
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

    /** 跨进程锁测试用的进程外持有者：打开 map、打印 LOCKED、睡眠若干秒。 */
    public static final class LockHolder {
        public static void main(String[] args) throws Exception {
            try (FileBackedMap m = new FileBackedMap(Path.of(args[0]))) {
                System.out.println("LOCKED " + m.size());
                System.out.flush();
                Thread.sleep(Long.parseLong(args[1]) * 1000L);
            }
        }
    }

    // ------------------------------------------------------------------
    // P2 同进程重复打开
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P2 同进程内第二个实例打开同一文件必须失败（不抛底层异常）")
    void sameProcessSecondOpenFails() throws IOException {
        Path p = fresh(dir, "dupopen.fbmap");
        try (FileBackedMap m = new FileBackedMap(p)) {
            m.put("a", "1");
            IOException ioe = assertThrows(IOException.class, () -> {
                try (FileBackedMap leaked = new FileBackedMap(p)) {
                    leaked.size();
                }
            }, "同进程重复打开应抛 IOException");
            assertTrue(ioe.getMessage().contains("本进程"), "信息应指明本进程内冲突: " + ioe.getMessage());

            // 失败的那次打开不得破坏第一个实例
            assertEquals("1", m.get("a"), "失败的打开不应影响第一个实例");
        }
        // close 释放锁并删文件后可重开（全新空会话）
        try (FileBackedMap m = new FileBackedMap(p)) {
            assertTrue(m.isEmpty(), "重开是全新空会话");
            m.put("a", "2");
            assertEquals("2", m.get("a"));
        }
    }

    // ------------------------------------------------------------------
    // P3 多读一写并发
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P3 4 个读线程 + 1 个写线程并发：无异常、终态一致")
    void concurrentReadersWithOneWriter() throws Exception {
        Path p = fresh(dir, "rw.fbmap");
        final int N = 2000;
        try (FileBackedMap m = new FileBackedMap(p)) {
            for (int i = 0; i < N; i++) {
                m.put("k" + i, "v0");
            }

            int readers = 4;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(readers + 1);
            ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
            AtomicBoolean writing = new AtomicBoolean(true);

            List<Thread> ts = new ArrayList<>();
            for (int t = 0; t < readers; t++) {
                final int seed = t;
                Thread th = new Thread(() -> {
                    try {
                        start.await();
                        for (int round = 0; round < 500; round++) {
                            int i = (seed * 7919 + round * 131) % N;
                            String v = m.get("k" + i);
                            if (v == null) {
                                throw new AssertionError("读到 null: k" + i);
                            }
                            m.containsKey("k" + i);
                            m.size();
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        done.countDown();
                    }
                }, "reader-" + t);
                ts.add(th);
                th.start();
            }

            Thread writer = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < N; i++) {
                        m.put("k" + i, "v1");
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    writing.set(false);
                    done.countDown();
                }
            }, "writer");
            writer.start();

            start.countDown();
            assertTrue(done.await(120, java.util.concurrent.TimeUnit.SECONDS), "并发测试超时（可能死锁）");
            for (Thread th : ts) {
                th.join(5000);
            }
            writer.join(5000);

            assertTrue(errors.isEmpty(), "并发读写不应抛异常: " + errors);
            assertEquals(N, m.size(), "终态条目数");
            for (int i = 0; i < N; i += 97) {
                assertEquals("v1", m.get("k" + i), "写线程完成后应读到新值 k" + i);
            }
        }
    }

    // ------------------------------------------------------------------
    // P4 并发读互不干扰（共享缓冲线程私有）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P4 8 线程并发读：键长度不同，共享缓冲不能串扰")
    void concurrentReadersNoBufferTearing() throws Exception {
        Path p = fresh(dir, "buffers.fbmap");
        try (FileBackedMap m = new FileBackedMap(p)) {
            // 长度跨度大的键，专门踩 compareBuffer 扩容路径
            for (int t = 0; t < 8; t++) {
                String k = "key-t" + t + "-".repeat(50 * (t + 1));
                m.put(k, "val-" + t);
            }

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(8);
            ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
            for (int t = 0; t < 8; t++) {
                final int id = t;
                new Thread(() -> {
                    try {
                        start.await();
                        String k = "key-t" + id + "-".repeat(50 * (id + 1));
                        for (int i = 0; i < 300; i++) {
                            assertEquals("val-" + id, m.get(k), "线程" + id + " 第" + i + " 次读值");
                            assertTrue(m.containsKey(k));
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        done.countDown();
                    }
                }, "rd" + t).start();
            }
            start.countDown();
            assertTrue(done.await(60, java.util.concurrent.TimeUnit.SECONDS), "并发读超时");
            assertTrue(errors.isEmpty(), "并发读出现串扰: " + errors);
        }
    }

    // ------------------------------------------------------------------
    // P5 回调重入（写锁可重入）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P5 回调重入不死锁：get 可重入；forEach 回调内 put 按 fail-fast 抛 CME")
    void callbackReentrancy() throws Exception {
        Path p = fresh(dir, "reentrant.fbmap");
        try (FileBackedMap m = new FileBackedMap(p)) {
            for (int i = 0; i < 50; i++) {
                m.put("k" + i, "v" + i);
            }

            // forEach 回调内 get（纯读重入）：安全且全量遍历
            AtomicInteger seen = new AtomicInteger();
            Thread t = new Thread(() -> m.forEach((k, v) -> {
                assertEquals(v, m.get(k), "forEach 回调内 get 应可重入");
                seen.incrementAndGet();
            }), "reentrant-get");
            t.start();
            t.join(30_000);
            assertFalse(t.isAlive(), "回调内 get 死锁");
            assertEquals(50, seen.get());

            // forEach 回调内 put：写锁可重入所以不死锁，modCount 变化触发 fail-fast CME（与 HashMap 一致）
            AtomicBoolean cme = new AtomicBoolean();
            Thread t2 = new Thread(() -> {
                try {
                    m.forEach((k, v) -> m.put(k + "#copy", v));
                } catch (java.util.ConcurrentModificationException e) {
                    cme.set(true);
                }
            }, "reentrant-put");
            t2.start();
            t2.join(30_000);
            assertFalse(t2.isAlive(), "回调内 put 死锁");
            assertTrue(cme.get(), "forEach 回调内写应 fail-fast（CME），与 HashMap 契约一致");

            // compute 回调内 get + put：写锁可重入不死锁；语义由 compute 的最终写决定
            // （回调内的 put 已把 k1 置 null，old + "+" 因此是 "null+"——Map 契约本就不规定
            //  回调内再写同一键的结果，这里只要求不崩溃、不死锁）
            String r = m.compute("k1", (k, old) -> {
                m.get("k0");
                m.put("k2-touched", "touched");
                return old + "+";
            });
            assertEquals("v1+", r, "compute 的返回值");
            assertEquals("touched", m.get("k2-touched"), "回调内的写入已生效");
            // 50 原键 + 1 个 CME 用例写入的 copy 键 + 1 个 compute 回调新增键
            assertEquals(52, m.size());
        }
    }

    // ------------------------------------------------------------------
    // P6 迭代器并发扫描
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P6 两线程同时遍历 entrySet：各自得到完整一致快照")
    void concurrentIteration() throws Exception {
        Path p = fresh(dir, "iterate.fbmap");
        final int N = 800;
        Map<String, String> ref = new java.util.HashMap<>();
        try (FileBackedMap m = new FileBackedMap(p)) {
            for (int i = 0; i < N; i++) {
                ref.put("k" + i, "v" + i);
            }
            m.putAll(ref);

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
            for (int t = 0; t < 2; t++) {
                new Thread(() -> {
                    try {
                        start.await();
                        for (int round = 0; round < 3; round++) {
                            Set<String> keys = new HashSet<>();
                            for (Map.Entry<String, String> e : m.entrySet()) {
                                if (!ref.get(e.getKey()).equals(e.getValue())) {
                                    throw new AssertionError("值不一致: " + e);
                                }
                                keys.add(e.getKey());
                            }
                            assertEquals(N, keys.size(), "遍历应覆盖全部键");
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        done.countDown();
                    }
                }, "it" + t).start();
            }
            start.countDown();
            assertTrue(done.await(90, java.util.concurrent.TimeUnit.SECONDS), "并发遍历超时");
            assertTrue(errors.isEmpty(), "并发遍历异常: " + errors);
        }
    }

    // ------------------------------------------------------------------
    // P7 close() 删除数据文件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P7 close 删除数据文件；close 幂等；重开是全新空会话")
    void closeDeletesFile() throws IOException {
        Path p = fresh(dir, "closedel.fbmap");
        try (FileBackedMap m = new FileBackedMap(p)) {
            m.put("a", "1");
            m.put("b", "2");
            assertTrue(Files.exists(p), "会话期间文件存在");
        }
        assertFalse(Files.exists(p), "close 后数据文件必须已被删除");

        // close 幂等：重复 close 不抛
        FileBackedMap m2 = new FileBackedMap(p);
        m2.put("x", "1");
        m2.close();
        assertDoesNotThrow(m2::close);

        // 重开是全新空会话
        try (FileBackedMap m = new FileBackedMap(p)) {
            assertEquals(0, m.size(), "重开 size = 0");
            assertNull(m.get("a"), "旧键不保留");
        }
    }

    // ------------------------------------------------------------------
    // P8 迭代器写路径（迭代器 remove 取写锁）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P8 迭代器 remove() 正常工作（读扫描与写删除交替不死锁）")
    void iteratorRemoveUnderWriteLock() throws IOException {
        Path p = fresh(dir, "iterremove.fbmap");
        try (FileBackedMap m = new FileBackedMap(p)) {
            for (int i = 0; i < 100; i++) {
                m.put("k" + i, "v" + i);
            }
            int removed = 0;
            for (java.util.Iterator<Map.Entry<String, String>> it = m.entrySet().iterator(); it.hasNext(); ) {
                if (Integer.parseInt(it.next().getKey().substring(1)) % 2 == 0) {
                    it.remove();
                    removed++;
                }
            }
            assertEquals(50, removed);
            assertEquals(50, m.size());
            assertDoesNotThrow(() -> m.put("k0", "back"));
            assertEquals("back", m.get("k0"));
        }
    }
}
