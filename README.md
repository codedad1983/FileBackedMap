# FileBackedMap —— 数据放磁盘文件的 Java Map 组件（会话作用域）

一个 `Map<String, String>` 的实现，**定位等同 `HashMap`，只是把键值数据放在磁盘文件里**：堆内存只承担一个可控大小的内存索引，键值字节全部写进数据文件。对外 API 与 `java.util.HashMap` 完全一致，可以直接替换使用。

适用于「把海量业务基础数据临时加载进内存做快速查询，但堆内存装不下」的场景：数据在会话期间驻留磁盘、随取随读，JVM 不会因海量 String 撑爆堆。**组件不做数据持久化**——应用重启后数据由上层重新加载，因此文件只是本次会话的「磁盘内存」，close() 后即删除。

- 仓库：<https://github.com/codedad1983/FileBackedMap>
- 发布页：<https://github.com/codedad1983/FileBackedMap/releases>（推送 `v*` 标签自动构建发布 `filebackedmap-<版本>.jar` 与 `-sources.jar`）
- 坐标：`com.codedad:filebackedmap:0.0.1`（Java 17+；生产代码零第三方依赖，测试用 JUnit 5，另有 MapDB 仅作性能对照组）

## 一、定位与会话生命周期

组件被刻意定位为 **HashMap 的磁盘扩展**，不是嵌入式数据库：

| 特性 | 本组件 | 嵌入式 DB（如 MapDB） |
|---|---|---|
| 数据存哪 | 数据文件（磁盘），索引在内存 | 文件 + 页缓存 / mmap |
| 关闭后文件 | **删除**，数据不保留 | 保留，可重开 |
| 崩溃后 | 残留文件在下次打开时清空，不恢复 | 恢复 / 事务回滚 |
| fsync | **无**（写完页缓存即可见） | 事务模式逐写 fsync |
| 垃圾整理 / 压缩 | **无**（数据文件只增不减） | 有 |

会话生命周期就三条规则：

1. **打开（构造）**：对目标文件取独占 `FileLock`——**文件被其它进程占用就抛 `IOException`**；取锁成功后**立即清空同名旧文件**（`truncate(0)`，与「删除重建」等效），会话永远从空表开始。
2. **会话期间**：键值记录只追加写入数据文件；写完即可从同一通道读回（页缓存对写进程可见），**全程无 fsync**。
3. **close()**：释放独占锁、关闭通道，并**删除数据文件**。幂等。进程崩溃 / 强杀时锁由系统随句柄自动释放，残留文件在下次打开时被清空——无需任何恢复逻辑。

## 二、需求对照

| 要求 | 实现方式 |
|---|---|
| 功能与内置 Map 一致 | `implements java.util.Map<String, String>`，实现了 `put/get/remove/clear/putAll/size/containsKey/containsValue/keySet/values/entrySet/forEach/replaceAll/putIfAbsent/replace/compute/computeIfAbsent/computeIfPresent/merge/equals/hashCode`，语义与 `HashMap` 一致（含 null 键、null 值、空串，非 String 入参按“不存在”处理） |
| 键值数据不占堆内存 | 键值字节全部写入数据文件；堆内只有内存索引（三个数组，100 万条目约 26 MB）与单条记录解码的临时 String。每次 `get/put/remove/遍历` 都用 `FileChannel.read/write` 定长定位读写 |
| 索引在内存、大小可控 | 开放定址索引 = `states[]`（1 B/槽）+ `hashes[]`（4 B/槽）+ `offsets[]`（8 B/槽），共 13 B/槽；槽数始终是 2 的幂，负载 2/3 时翻倍。索引不落盘、无独立文件 |
| 数据文件充当内存 | 单文件 `<path>`：无头、无校验字段，只有追加的记录字节；写完立即可读回 |
| 不需要持久化 / 崩溃恢复 | 打开清空同名旧文件、close 删除文件；无头 / CRC / 脏标记 / 对账 / 提交线 / fsync |
| 重名删除、被占报错 | 打开时取独占 `FileLock`，被占抛 `IOException`；成功即清空旧内容 |
| 键值都是 String，长度不固定、无限制 | 记录编码为 `[int len][UTF-8 bytes]`，`-1` 表示 null。任意字符（中文、emoji、控制字符）与任意长度都支持（单条记录上限约 2 GB，总容量受磁盘限制） |
| 不使用内存映射 | 没有 `FileChannel.map`、`MappedByteBuffer`，也没有一次性把文件读进堆 |

## 三、数据结构

### 3.1 内存索引 = 开放定址哈希表（三个数组）

- 定位：`idx = hash & (slotCount - 1)`，冲突线性探测；平均 1 次探测。
- 槽位 = 状态（EMPTY / OCCUPIED / TOMBSTONE）+ 键 hash + 记录在数据文件中的偏移。
- 判断键相等：先比 `hash`，哈希相同才去数据文件读键字节逐字节比较——避免为每个候选槽解码 String。
- 删除：槽位标成墓碑（探测链不断），旧记录成为垃圾；后续 `put` 优先复用探测路径上的第一个墓碑。
- 负载控制：`(条目 + 墓碑) / 槽位数 ≥ 2/3` 时**纯内存 rehash 扩容**（重建三个数组），零 I/O，扩容时墓碑丢弃。
- 槽位数始终是 2 的幂（初始 8），保证 `hash & mask` 均匀。

### 3.2 数据文件 = 无头追加日志

数据文件**没有任何头字段**，偏移 0 就是第一条记录。每条记录自描述、变长：

```
[4 B keyLen][keyLen 个字节的 UTF-8 键][4 B valLen][valLen 个字节的 UTF-8 值]
```

| 要素 | 编码 |
|---|---|
| 键长度 | `int keyLen`，`-1` 表示 **null 键**（此时没有键字节） |
| 键内容 | 紧随其后的 `keyLen` 个 UTF-8 字节，不做长度上限（仅受单记录 ≤ 2 GB 限制） |
| 值长度 | `int valLen`，`-1` 表示 **null 值**；`0` 表示**空串**（合法值，与 null 区分） |
| 值内容 | 紧随其后的 `valLen` 个 UTF-8 字节 |
| 记录总长度 | `8 + max(keyLen,0) + max(valLen,0)` 字节 |

**只追加**：更新一律追加新记录 + 改内存槽位指向，旧记录原地成为垃圾。**没有垃圾整理 / 重建功能**（会话语义下文件 close 即删，会话内文件只增不减，容量规划由使用方负责）。`clear()` 把文件 `truncate(0)` 并把索引重置回最小容量。

### 3.3 哈希函数

自写的 **MurmurHash3_x86_32 变体**，作用于键的 UTF-8 字节（null 键哈希为 0）。不用 `String.hashCode()`，保持跨版本稳定的分布质量。

## 四、扩容

| 触发条件 | 动作 | 代价 |
|---|---|---|
| `(条目 + 墓碑) / 槽位数 ≥ 2/3` | 纯内存 rehash：按新槽数重建三个数组、重分布所有槽位 | 只重建 13 B/槽 的内存数组；**数据文件零访问**，偏移原样搬 |

- 与旧「索引落盘」模型不同，扩容不再有任何文件写入：2,097,152 → 4,194,304 槽的一次扩容实测 **23.8 ms**（见 9.8）。
- 扩容是写锁内的内存操作，无 I/O、无 fsync、无改名提交，天然无崩溃窗口。

## 五、并发与会话

### 5.1 并发

- **进程内是读写锁**（`ReentrantReadWriteLock`）：纯读操作（`get` / `containsKey` / `containsValue` / `size` / `isEmpty` / `equals` / `hashCode` / `toString` / 迭代器扫描）取**读锁**，多线程可并发执行；写操作与「读-改-写」复合方法（`put` / `remove` / `compute*` / `merge` / `replace*` / `forEach` / `replaceAll`）取**写锁**独占。比较缓冲等复用缓冲都是 `ThreadLocal` 线程私有，读锁下的并发读互不串扰。
- **回调重入安全**：复合方法把回调函数整体包在写锁内，回调里再调用本实例的任意方法都安全（写锁可重入，不会自锁）。语义与 `HashMap` 一致——`forEach` 回调内结构性修改会抛 `ConcurrentModificationException`（fail-fast），`compute` 回调内的写入以最终写为准。
- **跨进程独占锁**：打开时对数据文件取独占 `FileLock`，持有到 `close()`。第二个进程（或同进程的第二个实例）打开同一文件会立即抛 `IOException`；进程崩溃 / 被强杀时锁由操作系统随句柄自动释放，不留需要清理的锁文件。这把「一个文件同一时刻只有一个实例」从文档约定变成运行期强制。POSIX 上为建议性锁（只约束同样走本类的进程），Windows 上由系统强制。
- 迭代期间的结构性修改抛 `ConcurrentModificationException`（与 `HashMap` 一致）；迭代器自身的 `remove()` 允许（取写锁），`entry.setValue()` 也允许。

### 5.2 读写正确性（无 fsync 的边界）

组件保证的是**同进程写后读正确**，不保证跨进程 / 跨重启持久：

- 写入走 `FileChannel.write` 进页缓存，同一通道（乃至同进程任何句柄）随即可 `read` 到——这是操作系统页缓存的强保证，因此「写入后能正确读出」始终成立。
- 不做 `force()`：崩溃 / 断电时页缓存未刷盘的部分会丢，但本组件的数据本来就只要求会话内有效，丢了也无所谓——下次打开永远从空表开始。
- 删除记录 = 内存槽位标墓碑；更新记录 = 追加新记录 + 改内存偏移。所有「发布」动作都在内存里完成，因此不存在「半个槽位」「撕裂记录」这类半状态——写路径最坏情况是数据文件尾部有多写了一条没人引用的记录，读路径永远看不到它。

### 5.3 校验与报错边界

- 打开：取不到独占锁 → `IOException`（信息指明跨进程 / 本进程冲突）；路径不可写 → `IOException`。
- 无文件头需要校验——文件内容即记录流，会话从 truncate(0) 开始，天然干净。
- `close()` 幂等：重复调用不抛。

## 六、构建与运行

### 6.1 目录结构（Maven 标准布局）

```
filebackedmap/
├── pom.xml
├── docs/
│   └── perf-plan.md                   ← 历史性能方案（持久化时代的 A/B/C，已被会话模型取代）
├── src/
│   ├── main/java/com/codedad/fbmap/
│   │   └── FileBackedMap.java          ← 组件主体（唯一的生产代码）
│   └── test/java/com/codedad/fbmap/
│       ├── test/                     ← 正确性测试（JUnit 5 黑盒，只用 Map 接口）
│       │   ├── FileBackedMapTest.java
│       │   └── ConcurrencyTest.java   ← 并发与独占锁（读写锁、跨进程锁、回调重入、close 删文件）
│       ├── bench/                     ← 性能专项测试
│       │   ├── Benchmark.java          ← 100 万条插入 / 修改 / 查找
│       │   ├── PutAllBench.java       ← putAll 批量插入专项（对照逐条）
│       │   ├── MapDbBenchmark.java    ← MapDB 对照组（相同场景）
│       │   ├── GrowBench.java         ← 扩容专项（纯内存 rehash）
│       │   ├── VerifyFinal.java      ← 写入-读回自校验 + 打开开销
│       │   ├── BenchInternals.java    ← 专项测试辅助工具（仅 bench 内部使用）
│       │   └── SampleGen.java         ← 确定性样本生成器（bench 内部使用）
│       └── demo/                      ← 演示
│           └── Demo.java
└── build/                             ← 运行产物（数据文件、日志、旧编译输出）
```

包名全部为 `com.codedad` 及子包：`com.codedad.fbmap`（生产代码）、`com.codedad.fbmap.test` / `.bench` / `.demo`（测试与工具）。**测试代码放在 `src/test/java`，与 `src/main/java` 完全分离**，因此生产 jar 里不含任何测试类。

### 6.2 直接用 javac（无需 Maven）

```bash
# 编译生产代码（Java 17+）
javac -encoding UTF-8 -d build/classes $(find src/main/java -name '*.java')
```

> 正确性测试是 **JUnit 5** 测试类（`FileBackedMapTest`、`ConcurrencyTest`），推荐用下面的 `mvn test` 运行；若想用 javac 手动编译测试，需要额外把 JUnit Jupiter jar 放进 classpath。

### 6.3 用 Maven

```bash
mvn test                             # 跑 JUnit 5 测试（正确性 11 例 + 并发 8 例）
mvn package                          # 测试通过后产出 target/filebackedmap-0.0.1.jar 与 -sources.jar

# 性能 / 演示等专项程序仍是 main() 形式，不进 surefire，按需 exec:java 运行：
mvn -q exec:java -Dexec.mainClass=com.codedad.fbmap.bench.Benchmark \
    -Dexec.args="map build/bench-data"
```

> `bench`（性能专项）与 `demo`（演示）保留 `main()` 形式、不标注 `@Test`，surefire 不会执行它们，避免 CI 被长耗时性能测试拖慢。GitHub Actions（`ci.yml` / `release.yml`）用 `mvn verify` 一条命令完成编译 + JUnit 测试 + 打包。

### 6.4 在项目里引用发布包

```xml
<dependency>
    <groupId>com.codedad</groupId>
    <artifactId>filebackedmap</artifactId>
    <version>0.0.1</version>
</dependency>
```

> 该产物未发布到 Maven Central（无中央仓库部署），所以要直接用请下载 GitHub Release 里的 jar 放到本地仓库，或用 `system` 作用域引用本地 jar。仓库本身已配好 CI 与 Release 工作流：推送 `v*` 标签即自动构建并发布。

> Windows 控制台默认 GBK，Java 输出 UTF-8 中文会乱码，可加 `| iconv -f GBK -t UTF-8` 或改用 `chcp 65001`。程序本身读写文件始终使用 UTF-8，不受影响。

## 七、用法

```java
import com.codedad.fbmap.FileBackedMap;

// 对外 API 只有 java.util.Map 接口方法 + close()，其余全是 private。
try (FileBackedMap map = new FileBackedMap(Path.of("data", "kv.fbmap"))) {
    map.put("name", "张三");
    map.put("note", "x".repeat(1_000_000));  // 长度不限
    System.out.println(map.get("name"));     // 张三
    System.out.println(map.size());
}
// close() 已删除 kv.fbmap —— 数据不落盘保留，下次使用请重新加载。
```

典型用法是**应用启动时批量加载**：

```java
try (FileBackedMap dict = new FileBackedMap(Path.of("tmp", "dict.fbmap"))) {
    dict.putAll(loadFromDb());          // putAll 逐条追加，无逐写 fsync
    // 会话期间高速查询……
}
```

> 同一文件同一时刻只允许一个实例：占用中打开会抛 `IOException`。用完请 close（try-with-resources），文件即删。

## 八、测试覆盖

两套测试均为 **JUnit 5** 测试类（`mvn test` 自动执行，合计 19 个 `@Test`；单个用例内是成百上千条对照断言，任何一条不符即失败）。每个用例使用 JUnit `@TempDir` 独享临时目录，用例之间零共享状态。

`FileBackedMapTest`（`com.codedad.fbmap.test`）的 11 个用例（约 5800+ 项断言，与 `HashMap` 逐条对照）：

1. 基本增删改查、size、clear（clear 把数据文件截断为 0）
2. null 键、null 值、空串、中文、emoji、控制字符，含 null 键/值的各类更新路径
3. 超长键（20 万字符）与超长值（150 万字符 UTF-8），值变短后的更新
4. 会话作用域：重开即清空（旧键全部消失），close 后数据文件被删除
5. 打开同名旧文件立即清空（等同删除重建）
6. 5000 轮随机操作与 `HashMap` 逐条对照（返回值、size、内容、hashCode、keySet、values、entrySet 包含关系）
7. Java 8 默认方法：`putIfAbsent/compute/computeIfAbsent/computeIfPresent/merge/replace/replaceAll/forEach/putAll/remove(k,v)`
8. `putAll` 批量专项：5,000 条一批（从 8 槽起步、批次内跨越负载阈值多次，覆盖「批次中途扩容」）+ null 键 / null 值 / 空串 / 覆盖已存在键 / 同值 no-op / 空批次，逐键与 `HashMap` 对照
9. 迭代器：遍历计数、迭代 `remove()`、`entry.setValue()` 写回、快速失败
10. 视图与 `equals/hashCode/toString`
11. 压力混合（黑盒）：反复改值长度 5 轮（数据文件只增不减）、驱动内部自动扩容，再删 90%、插入 3000 条，内容仍与 HashMap 完全一致（不触碰任何非公开方法）

`ConcurrencyTest`（`com.codedad.fbmap.test`）的 8 个用例覆盖并发模型与独占锁（全部黑盒，跨进程用例用进程外 `java` 子进程真实持锁）：

| 用例 | 场景 | 期望行为 |
|---|---|---|
| P1 跨进程独占 | 子进程打开并持锁 → 本进程再打开 → 子进程被强杀 | 持锁期打开抛 `IOException`；强杀后锁随句柄释放，残留文件在重开时被清空 |
| P2 同进程重复打开 | 同一实例未关闭时再开同一路径 | 抛 `IOException`（信息指明本进程内冲突），失败方不泄漏句柄、不影响第一个实例 |
| P3 多读一写并发 | 4 读线程 + 1 写线程，2000 键 | 无异常、不死锁，终态与写入结果一致 |
| P4 并发读无串扰 | 8 线程读长度跨度大的不同键（踩缓冲扩容路径） | 全部读值正确（共享缓冲线程私有） |
| P5 回调重入 | `forEach`/`compute` 回调内再调用本实例方法 | 不死锁；回调内结构修改按 fail-fast 抛 CME（与 `HashMap` 契约一致） |
| P6 并发遍历 | 两线程同时 `entrySet()` 全表遍历 3 轮 | 各自得到完整一致快照 |
| P7 close 删文件 | 写入后正常 close；close 幂等性 | close 后文件不存在、重开是全新空会话；重复 close 不抛 |
| P8 迭代器写路径 | 遍历中 `it.remove()` 交替读扫描 | 删除生效、计数正确、后续写入正常 |

## 九、性能测试报告

### 9.1 测试环境

| 项目 | 值 |
|---|---|
| 操作系统 | Windows（桌面版） |
| CPU | Intel Xeon W-2223 @ 3.60 GHz |
| 内存 | 255.7 GB 物理内存 |
| JDK | Temurin OpenJDK 21.0.13 LTS |
| 运行参数 | `-Xmx256m`（本组件）／`-Xmx8g`（HashMap 对照） |
| 磁盘 | C: 盘 **NVMe SSD**，剩余约 187 GB（测试文件 2.1 GB 可容纳） |

### 9.2 测试样本

| 项目 | 规格 |
|---|---|
| 键 | `"k<下标>#"` + 随机字符，总长度 **100–1000 字符**随机 |
| 值 | 随机字符，长度 **100–1000 字符**随机 |
| 字符池 | `a-zA-Z0-9-_ .`（纯 ASCII，UTF-8 每字符 1 字节） |
| 总量 | **1,000,000** 条键值对 |
| 样本生成方式 | 确定性：由下标推导 key、由种子推导 value，**不保存在内存中** |
| 样本原始负载 | 键 + 值合计 **1,048 MB**（UTF-8 原始字节） |

> 之所以用确定性生成：如果预先构造 100 万个 String 并留在内存里，测的就不是「数据不在内存」的组件，而是内存中的字符串开销。这样每个操作只付出真实解码一条记录的成本。

### 9.3 测试流程

1. **插入**：100 万次 `put`，键 `keyFor(i)`，值 `valueFor(SEED_INSERT, i)`
2. **修改**：100 万次随机键的 `put`（值换成另一组随机值），随机键由 `Random(0x5DEECE66D)` 选出，**全部命中已存在的键**
3. **查找**：100 万次随机键的 `get`，随机键由 `Random(0xDEADBEEF)` 选出

每个操作单独计时（`System.nanoTime()`），统计平均、p50、p95、p99、p999 与最大值；后台线程每 20 ms 采样堆占用峰值。

### 9.4 吞吐与延迟（会话模型：无 fsync、内存索引）

| 阶段 | 吞吐 | avg | p50 | p95 | p99 | p999 |
|---|---|---|---|---|---|---|
| 插入 1M | **48,933 ops/s** | 20.4 µs | 6.5 µs | 12.4 µs | 52.1 µs | 156.6 µs |
| 修改 1M | **18,531 ops/s** | 54.0 µs | 32.8 µs | 74.0 µs | 142.1 µs | 474.7 µs |
| 查找 1M | **27,084 ops/s** | 36.9 µs | 25.7 µs | 49.9 µs | 80.6 µs | 184.6 µs |
| putAll（每批 1,000） | **46,097 ops/s** | 21.7 µs | 8.5 µs | 10.7 µs | 15.3 µs | 37.0 µs |

对照旧「持久化模型」（每写 2 次 fsync）：插入 905、修改 830、查找 18,589、putAll 10,882 ops/s。**去掉 fsync 后写路径快 20–54 倍**，读路径快 1.46 倍（索引进了内存，探测不再伴随索引文件读）。

- 插入 p50 6.5 µs ≈ 一次 550 B 记录追加 + 一次内存探测；p99 抬升来自负载阈值触发的内存 rehash。
- 修改比插入慢：每写要先到数据文件读旧值（判断同值 + 返回旧值），一次随机定位读 ≈ 26 µs——p50 32.8 µs 与之吻合。
- 查找 p50 25.7 µs 是一次 1 GB 文件上的随机 4 B 长度头 + 值体读取（OS 页缓存命中率受 2 GB 工作集限制，NVMe 随机 4K 读 ~100 µs 级，此处多数页在缓存）。

### 9.5 与 HashMap 对照（同样 100 万条、同样样本）

| 阶段 | FileBackedMap | HashMap（-Xmx8g） |
|---|---|---|
| 插入 | 48,933 ops/s | 73,286 ops/s |
| 修改 | 18,531 ops/s | 71,444 ops/s |
| 查找 | 27,084 ops/s | 140,823 ops/s |
| **堆占用** | **29 MB**（峰值 104 MB，含基准自身 8 MB 统计数组） | **1,183 MB** |

写路径达到 HashMap 的 26–67%（差额 = 真写磁盘的记录追加 + 读旧值），堆占用只有其 **2.5%**——这正是本组件的存在意义：数据体量远超堆预算时唯一可行的形态。

### 9.6 磁盘使用量

- 插入阶段后数据文件 = **1,056 MB**（≈ 净载荷，记录开销 8 B/条）。
- 修改 100 万次后 = **2,112 MB**（每条被覆盖的旧记录原地成垃圾）。**没有垃圾整理功能，文件只增不减**——修改密集型会话请按「净载荷 × (1 + 修改比例)」预留磁盘。
- close() 后文件不存在（0 字节）。

### 9.7 内存使用量

`-Xmx256m` 下处理 1 GB 级数据：堆增量稳定 **29 MB**（其中内存索引 2,097,152 槽 × 13 B = 26 MB，其余为复用缓冲与基准程序自身），峰值 104 MB。100 万条键值在 HashMap 里需要 1,183 MB 堆，本组件堆占用与其条目数成正比（13 B/槽）而与键值长度无关。

### 9.8 扩容专项实测（纯内存 rehash）

90 万条基线（2,097,152 槽，负载 0.429）继续插入新键至 2/3 阈值，触发 2,097,152 → 4,194,304 槽扩容：

- 触发扩容的那一次 put = **23.8 ms**（重建 52 MB 内存数组 + 140 万槽位重分布；零 I/O）
- 扩容期间数据文件零重写（只增长本批新纪录），数据区 1,476 MB 全程未动
- 扩容后抽样 300/300 全部命中

摊到每次 put：一轮 1M 插入共 4 次扩容，合计约 60 ms，占插入阶段总时长 0.3%。

### 9.9 打开 / 校验

`VerifyFinal`（1M 写入 + 抽样读回）：

- 打开（取锁 + 清空）= **17 ms**（含创建文件、加锁、truncate）
- 写入 1M 实测 49,883 ops/s（与 9.4 一致）
- 抽样查找 1000/1000 命中（48.2 µs/次），**写入后读回全部正确**
- close 后文件不存在

### 9.10 复现命令

```bash
# 正确性 + 并发测试（JUnit 5，约 3 s）
mvn test

# 性能专项是 main() 形式，先编译再用 java 直跑
mvn -q test-compile
CP="target/classes;target/test-classes"

# 本组件 100 万条（约 2 分钟）
java -Xmx256m -cp "$CP" com.codedad.fbmap.bench.Benchmark map build/bench-data

# putAll 批量插入专项（-Dbench.batch 调批大小；约 30 秒）
java -Xmx256m -cp "$CP" com.codedad.fbmap.bench.PutAllBench build/putall-data

# HashMap 对照（约 34 秒）
java -Xmx8g -cp "$CP" com.codedad.fbmap.bench.Benchmark baseline build/bench-data

# 写入-读回自校验 + 打开开销（约 40 秒）
java -Xmx256m -cp "$CP" com.codedad.fbmap.bench.VerifyFinal build/bench-data

# 扩容专项（约 40 秒）
java -Xmx256m -cp "$CP" com.codedad.fbmap.bench.GrowBench build/grow-data

# MapDB 对照（非事务模式约 8 分钟；classpath 需带 MapDB 传递依赖）
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
CP="target/classes;target/test-classes;$(cat target/cp.txt)"
java -Xmx256m -Dbench.mapdb.tx=false -cp "$CP" com.codedad.fbmap.bench.MapDbBenchmark mapdb build/bench-data
```

### 9.11 与 MapDB 对照（同样 100 万条、同样样本、同为文件存储）

MapDB 3.1.0（`HTreeMap`，单文件 + mmap，官方推荐的堆外大 Map 用法）是同类「Map 数据放磁盘文件」的成熟第三方实现，作为对照组。`com.codedad.fbmap.bench.MapDbBenchmark` 与 `Benchmark` 使用完全相同的样本（同一 `SampleGen`、同种子）、相同三阶段、相同 `-Xmx256m`（两者都不需要大堆）。

**强度对齐说明**：本组件的写语义是「写进页缓存即返回」（无 fsync），MapDB 3.1 默认的非事务模式与此最接近（写入进 mmap，由 OS 择机刷盘，逐写不 fsync）。主对照取该模式：

| 阶段 | FileBackedMap（会话模型） | MapDB 3.1.0（非事务） | 对比 |
|---|---|---|---|
| 插入 | **48,933 ops/s**（p50 6.5 µs） | 4,324 ops/s（p50 193.3 µs） | 本组件快 **11.3 倍** |
| 修改 | **18,531 ops/s**（p50 32.8 µs） | 5,318 ops/s（p50 157.3 µs） | 本组件快 **3.5 倍** |
| 查找 | **27,084 ops/s**（p50 25.7 µs） | 11,822 ops/s（p50 69.8 µs） | 本组件快 **2.3 倍** |
| 磁盘 | 1,056 MB（插入阶段净载荷） | 1,124 MB（单文件） | 本组件小 6% |
| 堆占用 | 29 MB（峰值 104 MB） | 11 MB（峰值 35 MB） | MapDB 略省（索引在 mmap 页里） |

- 三阶段两边最终 size 均为 1,000,000、修改与查找命中均为 1,000,000 / 1,000,000（结果一致，仅速度不同）。
- 写路径差距的来源：MapDB 每次 put 要走 Kryo 序列化 + 可变长度记录编码 + HTreeMap 桶定位（1,124 MB 文件上的随机写，mmap 页查找），p50 193 µs 反映的是随机写布局成本；本组件是纯顺序追加 + 内存索引探测，p50 6.5 µs。
- 读路径本组件快 2.3 倍：内存索引探测（0～1 次哈希比较）+ 一次定位读，对 mmap 上的桶 + 链 + 变长记录解析。
- **持久化强度参考**（历史实测，旧版本组件为逐写 2 fsync 时对照）：MapDB 事务模式（`transactionEnable()` + 逐写 `commit()`，WAL fsync）为 189 / 189 / 12,841 ops/s（commit p50 ≈ 5.1 ms）。会话模型无 fsync，与事务模式相差两个数量级——这正说明两者定位不同：**要逐写持久化选 MapDB 事务模式，要会话内高速读写选本组件**。
- 语义差异：MapDB 提供事务、多 Map、并发容器、数据持久化等完整嵌入式 DB 能力；本组件刻意只做「会话作用域的磁盘内存 Map」这一件事。MapDB 以 `scope=test` 引入作为对照组依赖，不进入发布 jar。

## 十、已知取舍

- **不持久化**：close 删文件、崩溃不恢复。数据价值以会话为界，重启后由应用重新加载。这是与嵌入式 DB 的根本分界，也是写路径快 20–54 倍的来源。
- **无垃圾整理**：数据文件在会话内只增不减，修改 / 删除多则磁盘放大明显（见 9.6）。适用形态是「批量加载 + 读为主 / 追加为主」的会话；重修改会话需要按放大系数预留磁盘。
- **写后读依赖页缓存**：同进程内写后立即可读（OS 保证）；不承诺跨进程可见性——文件同时只允许一个实例打开。
- **无 fsync**：断电丢数据，符合定位（数据丢了重 load 即可）。
- **索引驻堆**：13 B/槽 × 负载 2/3 上限，1 亿条约 2.6 GB 堆。堆预算连索引都装不下的规模不在本组件适用范围内。
- **单文件独占**：一个文件一个实例，无多进程共享、无网络访问。
- **`entrySet()` 的迭代顺序**是槽位序，非插入序（与 `HashMap` 同性质）。
- 复合方法（`compute`/`merge` 等）整段持写锁，回调执行期间全表独占——语义与 `HashMap` 一致，但回调耗时会放大锁持有时间。
