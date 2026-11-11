package com.codedad.fbmap.test;

import com.codedad.fbmap.FileBackedMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileBackedMap 的行为验证（JUnit 5，与 java.util.HashMap 逐条对照）。
 *
 * 本类代表「正常使用」：只用 java.util.Map 接口 + close()，不反射、不触碰任何
 * 非公开方法。对文件的观察只用普通文件系统 API（索引路径是构造时自己传入的；
 * 数据文件按固定命名 <索引名>.data 直接访问）。
 *
 * 运行：mvn test（surefire 自动执行），或 IDE 里直接跑本类。
 * 每个用例独享一个 JUnit @TempDir，用例之间零共享状态。
 */
final class FileBackedMapTest {

    @TempDir
    Path dir;

    // ------------------------------------------------------------------
    // 辅助方法（只用公开 API 与文件系统）
    // ------------------------------------------------------------------

    private static Path fresh(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        Files.deleteIfExists(p);
        Files.deleteIfExists(dir.resolve(name + ".data"));
        Files.deleteIfExists(dir.resolve(name + ".index.tmp"));
        Files.deleteIfExists(dir.resolve(name + ".data.tmp"));
        return p;
    }

    /** 数据文件字节数：数据文件固定命名 <索引名>.data，直接按名取，无需组件内部信息。 */
    private static long dataSize(Path dir, String indexName) throws IOException {
        Path data = dir.resolve(indexName + ".data");
        return Files.exists(data) ? Files.size(data) : 0L;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("基本操作 put / get / remove / size / clear")
    void basicOperations() throws IOException {
        Path p = fresh(dir, "basic.fbmap");
        try (FileBackedMap m = new FileBackedMap(p)) {
            assertTrue(m.isEmpty(), "新建 map 应为空");
            assertEquals(0, m.size(), "size 初始值");
            assertNull(m.put("a", "1"), "首次 put 返回 null");
            assertEquals("1", m.get("a"), "get 返回值");
            assertEquals("1", m.put("a", "2"), "覆盖 put 返回旧值");
            assertEquals("2", m.get("a"), "覆盖后 get");
            assertEquals(1, m.size(), "覆盖后 size 不变");
            assertTrue(m.containsKey("a"), "containsKey 存在");
            assertFalse(m.containsKey("b"), "containsKey 不存在");
            assertTrue(m.containsValue("2"), "containsValue");
            assertFalse(m.containsValue("3"), "containsValue 不存在");
            assertNull(m.get("nope"), "get 不存在返回 null");
            assertEquals("def", m.getOrDefault("nope", "def"), "getOrDefault");
            assertEquals("2", m.remove("a"), "remove 返回旧值");
            assertNull(m.remove("a"), "重复 remove 返回 null");
            assertTrue(m.isEmpty(), "remove 后为空");

            for (int i = 0; i < 100; i++) {
                m.put("k" + i, "v" + i);
            }
            assertEquals(100, m.size(), "批量 put 后 size");
            m.clear();
            assertEquals(0, m.size(), "clear 后 size");
            assertTrue(m.isEmpty(), "clear 后 isEmpty");
            long expectedIndex = 64 + 8L * 16;   // 头 + 最小槽位区
            assertEquals(expectedIndex, Files.size(p), "clear 后索引文件被截断到最小布局");
            assertEquals(0L, dataSize(dir, "basic.fbmap"), "clear 后数据文件为空");
        }
    }

    @Test
    @DisplayName("null 键值、空串、中文 / emoji / 控制字符")
    void nullAndUnicode() throws IOException {
        try (FileBackedMap m = new FileBackedMap(fresh(dir, "unicode.fbmap"))) {
            m.put(null, "null-key-value");
            m.put("null-value", null);
            m.put("empty", "");
            m.put("中文键", "中文值");
            m.put("😀emoji🎉", "表情值🚀");
            m.put("tab\tnewline\n", "含控制字符");

            assertEquals("null-key-value", m.get(null), "null 键取值");
            assertNull(m.get("null-value"), "null 值");
            assertTrue(m.containsKey("null-value"), "null 值仍算键存在");
            assertEquals("", m.get("empty"), "空串值");
            assertEquals("中文值", m.get("中文键"), "中文键值");
            assertEquals("表情值🚀", m.get("😀emoji🎉"), "emoji 键值");
            assertEquals("含控制字符", m.get("tab\tnewline\n"), "控制字符键");
            assertEquals(6, m.size(), "unicode 场景 size");

            assertEquals("null-key-value", m.remove(null), "移除 null 键返回旧值");
            assertEquals(5, m.size(), "移除 null 键后 size");
            assertNull(m.remove(null), "再次移除 null 键返回 null");

            // null 键 / null 值的更新路径
            m.put(null, "aaaa");
            assertEquals("aaaa", m.put(null, "bbbb"), "null 键等长更新返回旧值");
            assertEquals("bbbb", m.get(null), "null 键等长更新");
            assertTrue(m.containsKey(null), "null 键仍存在");
            m.put(null, "c");
            assertEquals("c", m.get(null), "null 键变长更新");
            m.put("null-value", "filled");
            assertEquals("filled", m.get("null-value"), "null 值被覆盖");
            m.put("null-value", null);
            assertNull(m.get("null-value"), "值重新置为 null");
        }
    }

    @Test
    @DisplayName("超长键与值（长度不固定、无上限）")
    void longKeysAndValues() throws IOException {
        try (FileBackedMap m = new FileBackedMap(fresh(dir, "long.fbmap"))) {
            String longKey = "K".repeat(200_000) + "-tail";
            String longValue = "值".repeat(500_000);   // 1.5 MB 的 UTF-8
            m.put(longKey, longValue);
            assertEquals(longValue, m.get(longKey), "超长值读取一致");
            assertEquals(longValue.length(), m.get(longKey).length(), "超长值长度一致");
            assertTrue(m.containsKey(longKey), "超长键 containsKey");

            m.put(longKey, "短");
            assertEquals("短", m.get(longKey), "超长键更新（值变短）");
            assertEquals(1, m.size(), "long 场景 size");

            m.put("", "empty-key");
            assertEquals("empty-key", m.get(""), "空键");
            assertEquals(2, m.size(), "空键后 size");
        }
    }

    @Test
    @DisplayName("关闭后重新打开，数据仍在（持久化）")
    void persistence() throws IOException {
        Path p = fresh(dir, "persist.fbmap");
        Map<String, String> expected = new HashMap<>();
        try (FileBackedMap m = new FileBackedMap(p)) {
            for (int i = 0; i < 500; i++) {
                String k = "key-" + i;
                String v = "value-" + i + "-" + "x".repeat(i % 37);
                m.put(k, v);
                expected.put(k, v);
            }
            m.remove("key-0");
            expected.remove("key-0");
            m.put("key-1", "updated");
            expected.put("key-1", "updated");
        }
        try (FileBackedMap m = new FileBackedMap(p)) {
            assertEquals(expected.size(), m.size(), "重开后 size");
            for (Map.Entry<String, String> e : expected.entrySet()) {
                assertEquals(e.getValue(), m.get(e.getKey()), "重开后取值 " + e.getKey());
            }
            assertEquals(expected, new HashMap<>(m), "重开后整体内容相等");
        }
    }

    @Test
    @DisplayName("随机操作与 HashMap 逐条对照（5000 轮）")
    void randomDiffAgainstHashMap() throws IOException {
        Path p = fresh(dir, "diff.fbmap");
        Random rnd = new Random(20261106L);
        HashMap<String, String> ref = new HashMap<>();
        try (FileBackedMap m = new FileBackedMap(p)) {
            for (int round = 0; round < 5000; round++) {
                int op = rnd.nextInt(100);
                String key = "key" + rnd.nextInt(120);
                if (op < 45) {
                    String value = (rnd.nextInt(10) == 0)
                            ? "中文".repeat(1 + rnd.nextInt(50))
                            : "v" + rnd.nextInt(1000);
                    assertEquals(ref.put(key, value), m.put(key, value), "put 返回值 round " + round);
                } else if (op < 70) {
                    assertEquals(ref.remove(key), m.remove(key), "remove 返回值 round " + round);
                } else if (op < 95) {
                    assertEquals(ref.get(key), m.get(key), "get round " + round);
                } else {
                    m.clear();
                    ref.clear();
                }
            }
            assertEquals(ref.size(), m.size(), "最终 size");
            assertEquals(new HashMap<>(ref), new HashMap<>(m), "最终内容一致");
            assertEquals(ref.hashCode(), m.hashCode(), "hashCode 一致");
            assertEquals(new TreeSet<>(ref.keySet()), new TreeSet<>(m.keySet()), "keySet 一致");
            List<String> refValues = new ArrayList<>(ref.values());
            List<String> actualValues = new ArrayList<>(m.values());
            refValues.sort(null);
            actualValues.sort(null);
            assertEquals(refValues, actualValues, "values 一致");
            for (Map.Entry<String, String> e : ref.entrySet()) {
                assertTrue(m.entrySet().contains(e), "entrySet 包含 " + e);
            }
        }
    }

    @Test
    @DisplayName("Java 8 默认方法：putIfAbsent / compute / merge / replace / replaceAll / forEach / putAll")
    void bulkOperations() throws IOException {
        try (FileBackedMap m = new FileBackedMap(fresh(dir, "bulk.fbmap"))) {
            assertNull(m.putIfAbsent("a", "1"), "putIfAbsent 首次插入");
            assertEquals("1", m.putIfAbsent("a", "2"), "putIfAbsent 已存在");

            assertEquals("1+add", m.compute("a", (k, v) -> v + "+add"), "compute 更新");
            assertEquals("2", m.compute("b", (k, v) -> "2"), "compute 新建");
            assertNull(m.compute("b", (k, v) -> null), "compute 返回 null 表示删除");
            assertFalse(m.containsKey("b"), "compute 删除生效");

            assertEquals("3", m.computeIfAbsent("c", k -> "3"), "computeIfAbsent 新建");
            assertEquals("3", m.computeIfAbsent("c", k -> "其他"), "computeIfAbsent 已存在");
            assertEquals("1+add4", m.merge("a", "4", (o, n) -> o + n), "merge 已存在");
            assertEquals("x", m.merge("z", "x", (o, n) -> o + n), "merge 新建");

            assertTrue(m.replace("a", "1+add4", "replaced"), "三元 replace 匹配");
            assertFalse(m.replace("a", "wrong", "no"), "三元 replace 不匹配");
            assertEquals("replaced", m.replace("a", "again"), "二元 replace 返回旧值");

            m.replaceAll((k, v) -> v == null ? "nil" : v.toUpperCase());
            assertEquals("AGAIN", m.get("a"), "replaceAll 生效");

            StringBuilder sb = new StringBuilder();
            m.forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
            assertTrue(sb.length() > 0, "forEach 有输出");

            m.putAll(Map.of("p", "q", "r", "s"));
            assertEquals("q", m.get("p"), "putAll 写入");

            assertTrue(m.remove("a", "AGAIN"), "remove(k,v) 匹配删除");
            assertFalse(m.containsKey("a"), "remove(k,v) 后不存在");
            assertFalse(m.remove("zzz", "nope"), "remove(k,v) 不匹配");
        }
    }

    @Test
    @DisplayName("迭代器：遍历、remove、setValue、快速失败")
    void iteratorSemantics() throws IOException {
        try (FileBackedMap m = new FileBackedMap(fresh(dir, "iter.fbmap"))) {
            for (int i = 0; i < 50; i++) {
                m.put("k" + i, "v" + i);
            }
            int count = 0;
            for (String k : m.keySet()) {
                count++;
            }
            assertEquals(50, count, "keySet 遍历数量");

            Iterator<String> it = m.keySet().iterator();
            while (it.hasNext()) {
                String k = it.next();
                if (Integer.parseInt(k.substring(1)) % 2 == 1) {
                    it.remove();
                }
            }
            assertEquals(25, m.size(), "迭代删除后 size");
            assertFalse(m.containsKey("k1"), "迭代删除生效");

            // entry.setValue 写回文件
            String touchedKey = null;
            String touchedNew = null;
            for (Map.Entry<String, String> e : m.entrySet()) {
                touchedKey = e.getKey();
                touchedNew = e.getValue() + "!";
                assertEquals(e.getValue(), e.setValue(touchedNew), "setValue 返回旧值");
                break;
            }
            assertEquals(touchedNew, m.get(touchedKey), "entry.setValue 写回文件");

            boolean failed = false;
            try {
                for (String k : m.keySet()) {
                    m.put("new-" + k, "x");
                }
            } catch (ConcurrentModificationException e) {
                failed = true;
            }
            assertTrue(failed, "遍历中结构性修改应抛 ConcurrentModificationException");
        }
    }

    @Test
    @DisplayName("视图与 equals / hashCode / toString")
    void viewsAndEquality() throws IOException {
        try (FileBackedMap m = new FileBackedMap(fresh(dir, "views.fbmap"))) {
            m.put("name", "小明");
            m.put("age", "18");
            HashMap<String, String> h = new HashMap<>();
            h.put("name", "小明");
            h.put("age", "18");

            assertEquals(h, m, "equals 与 HashMap 相等");
            assertEquals(m, h, "equals 对称");
            assertEquals(h.hashCode(), m.hashCode(), "hashCode 相等");
            assertEquals(h.toString().length(), m.toString().length(), "toString 长度一致（顺序可能不同）");

            assertEquals(2, m.keySet().size(), "keySet size");
            assertTrue(m.keySet().contains("name"), "keySet contains");
            assertEquals(2, m.values().size(), "values size");
            assertTrue(m.values().contains("18"), "values contains");
            assertEquals(2, m.entrySet().size(), "entrySet size");
            assertTrue(m.entrySet().contains(Map.entry("name", "小明")), "entrySet contains");

            m.keySet().remove("age");
            assertEquals(1, m.size(), "keySet.remove 生效");
        }
    }

    @Test
    @DisplayName("大量更新 / 删除 / 再插入混合后内容仍一致（覆盖自动扩容与自动整理路径，黑盒）")
    void churnAndStress() throws IOException {
        Path p = fresh(dir, "stress.fbmap");
        HashMap<String, String> ref = new HashMap<>();
        try (FileBackedMap m = new FileBackedMap(p)) {   // 顺便验证逐次 fsync 落盘路径
            for (int i = 0; i < 2000; i++) {
                String k = "k" + i;
                String v = "x".repeat(64);
                m.put(k, v);
                ref.put(k, v);
            }

            // 反复改值长度 -> 垃圾持续增长，跨过阈值时组件内部自动整理/扩容（黑盒，不可见也不干预）
            for (int r = 0; r < 5; r++) {
                for (int i = 0; i < 2000; i++) {
                    String k = "k" + i;
                    String v = "y".repeat(10 + ((i + r * 47) % 900));
                    m.put(k, v);
                    ref.put(k, v);
                }
            }
            assertTrue(dataSize(dir, "stress.fbmap") > 0, "数据确实写在磁盘上");

            // 删除 90% 后逐个核对
            for (int i = 0; i < 2000; i++) {
                if (i % 10 != 0) {
                    m.remove("k" + i);
                    ref.remove("k" + i);
                }
            }
            assertEquals(ref.size(), m.size(), "大量删除后 size");
            assertEquals(ref, new HashMap<>(m), "大量删除后内容一致");

            // 再随机插入，验证墓碑槽位与扩容共存
            Random rnd = new Random(7);
            for (int i = 0; i < 3000; i++) {
                String k = "n" + rnd.nextInt(500);
                String v = "z" + rnd.nextInt(1000);
                m.put(k, v);
                ref.put(k, v);
            }
            assertEquals(ref, new HashMap<>(m), "删除+插入混合后内容一致");
            assertEquals(ref.hashCode(), m.hashCode(), "混合后 hashCode 一致");
        }

        // 重新打开再核对一次（fsync 落盘应完整）
        try (FileBackedMap m = new FileBackedMap(p)) {
            assertEquals(ref, new HashMap<>(m), "重开后内容一致");
            assertEquals(ref.size(), m.size(), "重开后 size");
        }
    }
}
