package com.codedad.fbmap.test;

import com.codedad.fbmap.FileBackedMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeSet;

/**
 * FileBackedMap 的行为验证程序（与 java.util.HashMap 对照）。
 * 无第三方依赖：javac -d build src/fbmap/*.java && java -cp build fbmap.FileBackedMapTest
 */
public final class FileBackedMapTest {

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "build/test-data");
        Files.createDirectories(dir);

        basicOperations(dir);
        nullAndUnicode(dir);
        longKeysAndValues(dir);
        persistence(dir);
        randomDiffAgainstHashMap(dir);
        bulkOperations(dir);
        iteratorSemantics(dir);
        viewsAndEquality(dir);
        compactionAndStress(dir);

        System.out.println();
        System.out.println("checks = " + checks + ", failures = " + failures);
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("ALL TESTS PASSED");
    }

    // ------------------------------------------------------------------

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + message);
        }
    }

    private static void checkEquals(Object expected, Object actual, String message) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            failures++;
            System.out.println("FAIL: " + message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private static Path fresh(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        Files.deleteIfExists(p);
        Files.deleteIfExists(dir.resolve(name + ".data"));
        Files.deleteIfExists(dir.resolve(name + ".index.tmp"));
        // 重建会换世代（.data.gN），把历史世代一并清掉，避免跨用例污染
        try (java.nio.file.DirectoryStream<Path> ds =
                     Files.newDirectoryStream(dir, name + ".data.g*")) {
            for (Path f : ds) {
                Files.deleteIfExists(f);
            }
        }
        return p;
    }

    /** 索引文件 + 当前数据文件的总占用字节数。 */
    static long totalBytes(FileBackedMap m) throws IOException {
        return Files.size(m.path()) + Files.size(m.dataPath());
    }

    // ------------------------------------------------------------------

    private static void basicOperations(Path dir) throws IOException {
        System.out.println("[1] 基本操作 put / get / remove / size / clear");
        try (FileBackedMap m = new FileBackedMap(fresh(dir, "basic.fbmap"))) {
            check(m.isEmpty(), "新建 map 应为空");
            checkEquals(0, m.size(), "size 初始值");
            checkEquals(null, m.put("a", "1"), "首次 put 返回 null");
            checkEquals("1", m.get("a"), "get 返回值");
            checkEquals("1", m.put("a", "2"), "覆盖 put 返回旧值");
            checkEquals("2", m.get("a"), "覆盖后 get");
            checkEquals(1, m.size(), "覆盖后 size 不变");
            check(m.containsKey("a"), "containsKey 存在");
            check(!m.containsKey("b"), "containsKey 不存在");
            check(m.containsValue("2"), "containsValue");
            check(!m.containsValue("3"), "containsValue 不存在");
            checkEquals(null, m.get("nope"), "get 不存在返回 null");
            checkEquals("def", m.getOrDefault("nope", "def"), "getOrDefault");
            checkEquals("2", m.remove("a"), "remove 返回旧值");
            checkEquals(null, m.remove("a"), "重复 remove 返回 null");
            check(m.isEmpty(), "remove 后为空");

            for (int i = 0; i < 100; i++) {
                m.put("k" + i, "v" + i);
            }
            checkEquals(100, m.size(), "批量 put 后 size");
            m.clear();
            checkEquals(0, m.size(), "clear 后 size");
            check(m.isEmpty(), "clear 后 isEmpty");
            long expectedIndex = 64 + 8L * 16;   // 头 + 最小槽位区
            checkEquals(expectedIndex, Files.size(m.path()), "clear 后索引文件被截断到最小布局");
            checkEquals(0L, Files.size(m.dataPath()), "clear 后数据文件为空");
        }
        System.out.println();
    }

    private static void nullAndUnicode(Path dir) throws IOException {
        System.out.println("[2] null 键值、空串、中文 / emoji / 控制字符");
        try (FileBackedMap m = new FileBackedMap(fresh(dir, "unicode.fbmap"))) {
            m.put(null, "null-key-value");
            m.put("null-value", null);
            m.put("empty", "");
            m.put("中文键", "中文值");
            m.put("😀emoji🎉", "表情值🚀");
            m.put("tab\tnewline\n", "含控制字符");

            checkEquals("null-key-value", m.get(null), "null 键取值");
            checkEquals(null, m.get("null-value"), "null 值");
            check(m.containsKey("null-value"), "null 值仍算键存在");
            checkEquals("", m.get("empty"), "空串值");
            checkEquals("中文值", m.get("中文键"), "中文键值");
            checkEquals("表情值🚀", m.get("😀emoji🎉"), "emoji 键值");
            checkEquals("含控制字符", m.get("tab\tnewline\n"), "控制字符键");
            checkEquals(6, m.size(), "unicode 场景 size");

            checkEquals("null-key-value", m.remove(null), "移除 null 键返回旧值");
            checkEquals(5, m.size(), "移除 null 键后 size");
            checkEquals(null, m.remove(null), "再次移除 null 键返回 null");

            // null 键 / null 值的更新路径
            m.put(null, "aaaa");
            checkEquals("aaaa", m.put(null, "bbbb"), "null 键等长更新返回旧值");
            checkEquals("bbbb", m.get(null), "null 键等长更新");
            check(m.containsKey(null), "null 键仍存在");
            m.put(null, "c");
            checkEquals("c", m.get(null), "null 键变长更新");
            m.put("null-value", "filled");
            checkEquals("filled", m.get("null-value"), "null 值被覆盖");
            m.put("null-value", null);
            checkEquals(null, m.get("null-value"), "值重新置为 null");
        }
        System.out.println();
    }

    private static void longKeysAndValues(Path dir) throws IOException {
        System.out.println("[3] 超长键与值（长度不固定、无上限）");
        try (FileBackedMap m = new FileBackedMap(fresh(dir, "long.fbmap"))) {
            String longKey = "K".repeat(200_000) + "-tail";
            String longValue = "值".repeat(500_000);   // 1.5 MB 的 UTF-8
            m.put(longKey, longValue);
            checkEquals(longValue, m.get(longKey), "超长值读取一致");
            checkEquals(longValue.length(), m.get(longKey).length(), "超长值长度一致");
            check(m.containsKey(longKey), "超长键 containsKey");

            m.put(longKey, "短");
            checkEquals("短", m.get(longKey), "超长键更新（值变短）");
            checkEquals(1, m.size(), "long 场景 size");

            m.put("", "empty-key");
            checkEquals("empty-key", m.get(""), "空键");
            checkEquals(2, m.size(), "空键后 size");
        }
        System.out.println();
    }

    private static void persistence(Path dir) throws IOException {
        System.out.println("[4] 关闭后重新打开，数据仍在（持久化）");
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
            checkEquals(expected.size(), m.size(), "重开后 size");
            for (Map.Entry<String, String> e : expected.entrySet()) {
                checkEquals(e.getValue(), m.get(e.getKey()), "重开后取值 " + e.getKey());
            }
            checkEquals(expected, new HashMap<>(m), "重开后整体内容相等");
        }
        System.out.println();
    }

    private static void randomDiffAgainstHashMap(Path dir) throws IOException {
        System.out.println("[5] 随机操作与 HashMap 逐条对照（5000 轮）");
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
                    checkEquals(ref.put(key, value), m.put(key, value), "put 返回值 round " + round);
                } else if (op < 70) {
                    checkEquals(ref.remove(key), m.remove(key), "remove 返回值 round " + round);
                } else if (op < 95) {
                    checkEquals(ref.get(key), m.get(key), "get round " + round);
                } else {
                    m.clear();
                    ref.clear();
                }
            }
            checkEquals(ref.size(), m.size(), "最终 size");
            checkEquals(new HashMap<>(ref), new HashMap<>(m), "最终内容一致");
            checkEquals(ref.hashCode(), m.hashCode(), "hashCode 一致");
            checkEquals(new TreeSet<>(ref.keySet()), new TreeSet<>(m.keySet()), "keySet 一致");
            List<String> refValues = new ArrayList<>(ref.values());
            List<String> actualValues = new ArrayList<>(m.values());
            refValues.sort(null);
            actualValues.sort(null);
            checkEquals(refValues, actualValues, "values 一致");
            for (Map.Entry<String, String> e : ref.entrySet()) {
                check(m.entrySet().contains(e), "entrySet 包含 " + e);
            }
        }
        System.out.println();
    }

    private static void bulkOperations(Path dir) throws IOException {
        System.out.println("[6] Java 8 默认方法：putIfAbsent / compute / merge / replace / replaceAll / forEach / putAll");
        try (FileBackedMap m = new FileBackedMap(fresh(dir, "bulk.fbmap"))) {
            checkEquals(null, m.putIfAbsent("a", "1"), "putIfAbsent 首次插入");
            checkEquals("1", m.putIfAbsent("a", "2"), "putIfAbsent 已存在");

            checkEquals("1+add", m.compute("a", (k, v) -> v + "+add"), "compute 更新");
            checkEquals("2", m.compute("b", (k, v) -> "2"), "compute 新建");
            checkEquals(null, m.compute("b", (k, v) -> null), "compute 返回 null 表示删除");
            check(!m.containsKey("b"), "compute 删除生效");

            checkEquals("3", m.computeIfAbsent("c", k -> "3"), "computeIfAbsent 新建");
            checkEquals("3", m.computeIfAbsent("c", k -> "其他"), "computeIfAbsent 已存在");
            checkEquals("1+add4", m.merge("a", "4", (o, n) -> o + n), "merge 已存在");
            checkEquals("x", m.merge("z", "x", (o, n) -> o + n), "merge 新建");

            check(m.replace("a", "1+add4", "replaced"), "三元 replace 匹配");
            check(!m.replace("a", "wrong", "no"), "三元 replace 不匹配");
            checkEquals("replaced", m.replace("a", "again"), "二元 replace 返回旧值");

            m.replaceAll((k, v) -> v == null ? "nil" : v.toUpperCase());
            checkEquals("AGAIN", m.get("a"), "replaceAll 生效");

            StringBuilder sb = new StringBuilder();
            m.forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
            check(sb.length() > 0, "forEach 有输出");

            m.putAll(Map.of("p", "q", "r", "s"));
            checkEquals("q", m.get("p"), "putAll 写入");

            check(m.remove("a", "AGAIN"), "remove(k,v) 匹配删除");
            check(!m.containsKey("a"), "remove(k,v) 后不存在");
            check(!m.remove("zzz", "nope"), "remove(k,v) 不匹配");
        }
        System.out.println();
    }

    private static void iteratorSemantics(Path dir) throws IOException {
        System.out.println("[7] 迭代器：遍历、remove、setValue、快速失败");
        try (FileBackedMap m = new FileBackedMap(fresh(dir, "iter.fbmap"))) {
            for (int i = 0; i < 50; i++) {
                m.put("k" + i, "v" + i);
            }
            int count = 0;
            for (String k : m.keySet()) {
                count++;
            }
            checkEquals(50, count, "keySet 遍历数量");

            Iterator<String> it = m.keySet().iterator();
            while (it.hasNext()) {
                String k = it.next();
                if (Integer.parseInt(k.substring(1)) % 2 == 1) {
                    it.remove();
                }
            }
            checkEquals(25, m.size(), "迭代删除后 size");
            check(!m.containsKey("k1"), "迭代删除生效");

            // entry.setValue 写回文件
            String touchedKey = null;
            String touchedNew = null;
            for (Map.Entry<String, String> e : m.entrySet()) {
                touchedKey = e.getKey();
                touchedNew = e.getValue() + "!";
                checkEquals(e.getValue(), e.setValue(touchedNew), "setValue 返回旧值");
                break;
            }
            checkEquals(touchedNew, m.get(touchedKey), "entry.setValue 写回文件");

            boolean failed = false;
            try {
                for (String k : m.keySet()) {
                    m.put("new-" + k, "x");
                }
            } catch (java.util.ConcurrentModificationException e) {
                failed = true;
            }
            check(failed, "遍历中结构性修改应抛 ConcurrentModificationException");
        }
        System.out.println();
    }

    private static void viewsAndEquality(Path dir) throws IOException {
        System.out.println("[8] 视图与 equals / hashCode / toString");
        try (FileBackedMap m = new FileBackedMap(fresh(dir, "views.fbmap"))) {
            m.put("name", "小明");
            m.put("age", "18");
            HashMap<String, String> h = new HashMap<>();
            h.put("name", "小明");
            h.put("age", "18");

            checkEquals(h, m, "equals 与 HashMap 相等");
            checkEquals(m, h, "equals 对称");
            checkEquals(h.hashCode(), m.hashCode(), "hashCode 相等");
            checkEquals(h.toString().length(), m.toString().length(), "toString 长度一致（顺序可能不同）");

            checkEquals(2, m.keySet().size(), "keySet size");
            check(m.keySet().contains("name"), "keySet contains");
            checkEquals(2, m.values().size(), "values size");
            check(m.values().contains("18"), "values contains");
            checkEquals(2, m.entrySet().size(), "entrySet size");
            check(m.entrySet().contains(Map.entry("name", "小明")), "entrySet contains");

            m.keySet().remove("age");
            checkEquals(1, m.size(), "keySet.remove 生效");

            System.out.println("  " + m.stats().toString().replace("\n  ", "\n  "));
        }
        System.out.println();
    }

    private static void compactionAndStress(Path dir) throws IOException {
        System.out.println("[9] 自动整理 / 文件压缩 / 大量删除后仍一致");
        Path p = fresh(dir, "stress.fbmap");
        HashMap<String, String> ref = new HashMap<>();
        try (FileBackedMap m = new FileBackedMap(p, true)) {   // durable=true，顺便验证 fsync 路径
            for (int i = 0; i < 2000; i++) {
                String k = "k" + i;
                String v = "x".repeat(64);
                m.put(k, v);
                ref.put(k, v);
            }
            long sizeBefore = totalBytes(m);

            // 反复改值长度 -> 产生大量垃圾
            for (int i = 0; i < 2000; i++) {
                String k = "k" + i;
                String v = "y".repeat(10 + (i % 200));
                m.put(k, v);
                ref.put(k, v);
            }
            long sizeAfterChurn = totalBytes(m);
            m.compact();                              // 手动整理，文件应显著变小
            long sizeAfterCompact = totalBytes(m);
            check(sizeAfterCompact < sizeAfterChurn, "compact 后文件变小");
            checkEquals(64 + (long) m.stats().slots() * 16, Files.size(p), "compact 后索引区正好等于 头 + 槽位区");
            checkEquals(0L, m.stats().garbageBytes(), "compact 后垃圾字节为 0");
            check(m.stats().dataBytes() > 0, "compact 后数据文件仍有内容");

            System.out.println("  文件大小: 初始=" + sizeBefore + " 反复更新后=" + sizeAfterChurn
                    + " 整理后=" + sizeAfterCompact + " 字节");
            System.out.println("  " + m.stats().toString().replace("\n  ", "\n  "));

            // 删除 90% 后逐个核对
            for (int i = 0; i < 2000; i++) {
                if (i % 10 != 0) {
                    m.remove("k" + i);
                    ref.remove("k" + i);
                }
            }
            checkEquals(ref.size(), m.size(), "大量删除后 size");
            checkEquals(ref, new HashMap<>(m), "大量删除后内容一致");

            // 再随机插入，验证墓碑槽位与扩容共存
            Random rnd = new Random(7);
            for (int i = 0; i < 3000; i++) {
                String k = "n" + rnd.nextInt(500);
                String v = "z" + rnd.nextInt(1000);
                m.put(k, v);
                ref.put(k, v);
            }
            checkEquals(ref, new HashMap<>(m), "删除+插入混合后内容一致");
            checkEquals(ref.hashCode(), m.hashCode(), "混合后 hashCode 一致");
        }

        // 重新打开再核对一次（durable 写入应完整）
        try (FileBackedMap m = new FileBackedMap(p)) {
            checkEquals(ref, new HashMap<>(m), "重开后内容一致");
            checkEquals(ref.size(), m.size(), "重开后 size");
        }
        System.out.println();
    }
}
