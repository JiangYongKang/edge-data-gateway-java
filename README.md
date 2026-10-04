# 工业边缘采集网关（本地可运行）

基于 Spring Boot 3 / Java 21 的本地模拟边缘网关：从多个模拟设备并发采集点位读数，
归一化为统一上行记录；上行中断时本地有界缓冲，恢复后按时间顺序补传；
对越界、乱序、类型/单位不匹配等畸形读数**只带可区分原因拒绝，绝不静默丢弃或写入脏值**。
在此之上提供**设备健康分级**（正常/反复出错/严重故障/恢复观察，上行中断不误判）、
**运行时统计一致性核对**（逐设备 + 全局，随时可取一份自洽结论）与
**重启后的健康与统计基线续算**（不清零、不重复计数）。

不依赖任何外部中间件：设备与上行通道均为进程内模拟，断网/重连/设备故障均可确定性复现。

## 1. 构建与运行

```bash
mvn test          # 40 个单测：归一化/缓冲/补传 + 设备健康分级、统计核对、重启基线续算
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes:$(cat target/cp.txt)" \
     com.github.highcumontoa.edgedatagatewayjava.demo.DemoRunner
```

演示剧本（约 5 秒）：两设备并发采集 → 上行断网 2s（缓冲积压并触发 DROP_OLDEST）
→ 恢复后顺序补传 → 一台设备掉线（依次经历 降级→严重故障）后恢复（经恢复观察回到正常）。
结束时打印最终位点、计数器、逐设备核对结论与下游收到的去重记录。

## 2. 模块结构

| 包 | 类 | 职责 |
|---|---|---|
| `model` | `RawReading` / `NormalizedRecord` / `PointDefinition` / `RejectReason` / `OverflowPolicy` | 领域模型与枚举 |
| `device` | `SimulatedDevice` / `ScriptedDevice` | 本地模拟设备，按脚本批次产出读数，可抛异常、可上/下线 |
| `normalize` | `Normalizer` | 无状态校验 + 类型转换，输出接受记录或带原因的拒绝 |
| `buffer` | `SpoolBuffer` / `AcceptResult` / `BufferStateStore` | 有界缓冲、WAL、seq 位点与溢出策略 |
| `uplink` | `UplinkChannel` / `MockUplinkChannel` | 上行通道抽象与模拟（断网/重连、按 seq 幂等去重） |
| `forward` | `ForwardWorker` | 单线程顺序补传，断网重试，重复投递/确认安全 |
| `collect` | `CollectorSupervisor` | 每设备独立调度线程，异常隔离；健康判定/归一化/计数回调收口给网关 |
| `health` | `DeviceHealth` / `DeviceHealthTracker` / `HealthConfig` / `HealthTransition` | 设备健康分级状态机（正常/反复出错/严重故障/恢复观察） |
| `stats` | `Counters` / `DeviceCounters` / `DeviceRuntime` / `RuntimeStateStore` / `Reconciler` / `ReconciliationReport` | 全局+逐设备计数、运行时基线持久化、一致性核对 |
| `stats` | `Counters` | 全局线程安全计数（含 DROP_OLDEST / REJECT_NEWEST 分项与基线恢复） |
| `gateway` | `EdgeGateway` / `GatewaySnapshot` | 编排与一致性保证 |
| `config` | `GatewayProperties` | 数据目录、容量、溢出策略、采集/补传周期、批大小 |
| `demo` | `DemoRunner` | 本地可运行演示 + 测试用条件等待工具 |

## 3. 采集与归一化规则

点位目录键为 `deviceId#pointId`（多设备同名点位互不干扰）。原始读数
`(deviceId, pointId, rawValue, rawUnit, timestamp)` 按**固定顺序**校验，
多重异常返回排在最前的原因，保证拒绝原因确定可解释：

| 顺序 | 拒绝原因 (`RejectReason`) | 触发条件 |
|---|---|---|
| 1 | `TIMESTAMP_MISSING` | 时间戳为 null |
| 2 | `UNKNOWN_POINT` | 点位未在设备点位表登记 |
| 3 | `VALUE_NULL` | 值为 null |
| 4 | `UNIT_MISMATCH` | 点位定义了期望单位但读数单位不等（点位单位为空串表示量纲无关，跳过单位校验） |
| 5 | `TYPE_MISMATCH` | 类型不能严格转换（见下）、NaN、整型超出 int 范围 |
| 6 | `OUT_OF_RANGE` | 数值落在 `[min, max]` 之外；边界值本身合法 |
| 7（网关） | `TIMESTAMP_OUT_OF_ORDER` | 读数时间戳严格小于该设备最后一条**已接受**时间戳 |

类型转换（严格、不做隐式脏转换）：

- `INT`：只接受 `Number`，且必须为整数且在 `int` 范围内（`3.5`、`"42"` 拒绝）；
- `DOUBLE`：接受任意 `Number`（除 NaN），不接受字符串/布尔；
- `BOOLEAN`：只接受 `Boolean`，不接受 `"true"`。

乱序语义：每设备时间戳**单调不减**（允许相等，严格回退才拒绝）。最后接受时间戳
随缓冲位点一起持久化，重启后恢复，不会从错误时间点继续接受。

归一化记录统一为：
`NormalizedRecord(seq, deviceId, pointId, type, value, unit, timestamp)`。
`seq` 不由归一化分配，而是入缓冲时分配全局单调递增序号（补传排序的依据）。

## 4. 断网缓冲与补传语义

### 4.1 序号与位点

- `nextSeq`：下一个待分配序号，从 0 开始，**只增不复用**；
- `confirmedSeq`：已被下游确认的最大序号（含），初始 -1；
- 任何接受的记录先写 WAL（`spool.wal`，每行一条）再更新位点状态
  （`buffer-state.json`，临时文件 + 原子 rename），二者都在数据目录下。

### 4.2 有界缓冲与溢出策略

容量上限固定，**绝不无界增长**。满时二选一（`OverflowPolicy`）：

- **`DROP_OLDEST`（默认）**：弹出最旧的未确认记录为新记录腾位。被丢弃 seq 的
  位点被推进为"已越过"（不会重投），下游产生可解释的 seq 缺口；每次丢弃都打印
  `[BUFFER-FULL DROP-OLDEST]` 并计入 `dropped` 统计，满足
  `已分配 seq 数 = 下游条数 + 丢弃条数` 的守恒关系。
- **`REJECT_NEWEST`**：新记录不进入缓冲；其 seq 同样被消耗（形成缺口），
  打印 `[BUFFER-FULL REJECT-NEWEST]` 并计入 `dropped`。容量腾出后后续记录正常接受。

### 4.3 补传与幂等

- `ForwardWorker` 单线程调度，始终取缓冲头部（seq 最小）成批发送，保证时间顺序；
  新记录入缓冲时 `kick()` 唤醒尽快发送，唤醒重叠由原子位防护。
- **发送成功才确认位点**；断网时 `send` 抛 `UplinkException`，不确认、不丢数据，
  等待下周期重试；恢复后从 `confirmedSeq + 1` 继续排空。
- 下游 `MockUplinkChannel` 按 seq 去重：同一 seq 无论重复投递多少次只产生一条
  下游记录。`confirm(seq)` 只进不退：重复确认、回退确认、确认未分配位点都返回
  `false` 且无任何效果。因此"重传 + 重复确认"在下游不产生重复记录。
- 确认/丢弃后 WAL 会按当前未确认集合原子压缩重写，文件不无限增长。

### 4.4 重启恢复

启动时先加载位点状态，再回放 WAL：仅 `seq > confirmedSeq` 的记录进入内存队列。
因此：已确认数据不重放（不重复）、未确认数据按序重放（不丢失）、
`nextSeq` 接着旧值分配（不复用）、每设备时间戳基线恢复（乱序判断连续）。
设备健康状态与统计计数基线由独立的 `runtime-state.json` 衔接（见第 8 节）。

## 5. 并发与故障边界

- 每个设备拥有**独立的单线程调度器**（`collector-<deviceId>`），补传为独立
  单线程（`forward-worker`）。采集与缓冲交互通过 `SpoolBuffer` 的内置锁串行化。
- 设备轮询中的任何 `Throwable`（含脚本主动抛出的故障、设备掉线异常）都在该设备
  线程内捕获，记为 `onDeviceError`、置设备 `DOWN`，**不会传播到调度器或其它设备**；
  下一次轮询成功即恢复 `UP`。在线状态与逐设备统计（轮询/错误/接受/拒绝次数、
  最后错误）通过 `EdgeGateway.snapshot()` / `deviceStats()` 读取，与实际一致。
- 上行断网不影响采集：记录继续进缓冲（满则按策略处理）。
- 关闭顺序：先停采集、再停补传，保证关闭期间无新记录与补传竞争。

## 6. 设备健康分级

在线/离线（`DeviceStatus.UP/DOWN`，最近一次轮询是否成功）与**健康分级正交**。
健康分级只依据**本设备自己真实的轮询结果**（`device.poll()` 成功/失败）：
归一化拒绝、缓冲溢出、上行中断等都**不参与**健康判定，因此上行短时中断绝不会
把设备算成不健康。

| 健康状态 | 含义 | 进入条件 |
|---|---|---|
| `HEALTHY` | 正常，轮询连续稳定 | 初始态；或 `RECOVERING` 中连续成功满观察窗口 |
| `DEGRADED` | 反复出错，需关注 | `HEALTHY` 下连续失败次数达到降级阈值（默认 3）；`RECOVERING` 中再次失败也回到此态 |
| `CRITICAL` | 已判定严重故障 | 连续失败次数达到严重故障阈值（默认 5） |
| `RECOVERING` | 故障后恢复观察中 | `DEGRADED/CRITICAL` 后**第一次**成功即进入；需再连续成功满窗口（默认 3 次）才回到 `HEALTHY` |

转移要点：

- 连续失败计数只增（成功才清零）：`HEALTHY → DEGRADED → CRITICAL`；
- 刚恢复一次**不会**被当成完全正常，必须先进入 `RECOVERING`，持续稳定采集
  满观察窗口才确认恢复；观察期内再次失败则观察中止、退回 `DEGRADED`
  （连续失败已达严重阈值时直接 `CRITICAL`）；
- 每次切换都记录 `HealthTransition(from, to, reason, atMillis)`，可随时查到
  最近一次切换的**原因与时间**（`DeviceStats.lastHealthTransition`，日志 `[HEALTH]`）；
- 阈值可配：`HealthConfig(degradedFailures, criticalFailures, recoverySuccesses)`，
  `criticalFailures > degradedFailures`，默认 `(3, 5, 3)`。空批次（轮询成功但无读数）
  也算一次真实的成功轮询。

## 7. 运行时统计一致性核对

随时调用 `EdgeGateway.reconcile()` 或读取 `snapshot().reconciliation()` 即可拿到一份
`ReconciliationReport`（`consistent` + 逐设备/全局 `issues`）。报告在网关**一致性读锁**
内与位点、pending、全部计数一次性采集，因此采集与补传并发进行时，报告本身也是对
**同一状态版本**自洽的一版，不会拼出"自相矛盾"的数字。

计数口径（每条读数的处置路径互斥且穷尽）：

- 轮询：`polled = successfulPolls + failedPolls`；
- 读数：`received = accepted + shedNewest + Σ reason rejected`
  （拒绝按 `RejectReason` 分开计数，乱序拒绝也在网关口径内计入）；
- 已接受记录去向：`accepted = delivered + droppedOldest + pending`，
  其中 `droppedOldest` 是本设备记录被 DROP_OLDEST 弹出的条数（被弹出记录按其
  `deviceId` 归属），`shedNewest` 是 REJECT_NEWEST 消耗 seq 但未入缓冲的缺口数。

核对规则（不通过时 issue 精确指出 **GLOBAL 或 deviceId + 指标类别 + 期望/实际值**）：

| 标识 | 口径 |
|---|---|
| `POLL` | `successful + failed == polled` |
| `RECEIVED` | `accepted + shedNewest + Σrejected == received` |
| `ACCEPT-LIFECYCLE` | `accepted == delivered + droppedOldest + pending(本设备)` |
| `REJECT:<reason>` | 各原因拒绝分项非负、分项之和即拒绝总数 |
| `G-DEV-POLLED / -ACCEPTED / -REJECTED / -DELIVERED / -DROPPED` | 逐设备之和 == 全局对应计数 |
| `G-SEQ-ALLOCATED` | `nextSeq == global.accepted + global.shedNewest`（已分配 seq 数） |
| `G-SEQ-LIFECYCLE` | `nextSeq == delivered + droppedOldest + shedNewest + pending` |

边界说明：`G-SEQ-LIFECYCLE` 成立的前提是 DROP_OLDEST 把确认位点推进到被弹出 seq
（本实现保证）。若进程在"缓冲已落盘新位点/WAL"与"运行时基线落盘"两个文件之间
崩溃，重启后会被该规则**检出为差异**（而非静默对错数），这是唯一的跨文件崩溃窗口，
正常停止与单文件原子落盘不影响一致性。

## 8. 重启后的状态与统计续算

新增持久化文件 `runtime-state.json`（临时文件 + 原子 rename），与原有
`buffer-state.json` / `spool.wal` 一起在启动时恢复：

- **健康续算**：健康等级、连续失败/连续成功次数、最近一次切换记录全部衔接；
  例如重启前 `DEGRADED` 且连续失败 4 次，重启后再失败 1 次即判 `CRITICAL`，
  不从 0 开始；重启发生在恢复观察中则观察计数继续累积。
- **统计基线续算**：逐设备 `polled/accepted/rejected(按原因)/delivered/
  droppedOldest/shedNewest` 与全局计数都从持久化值接续，**不清零重算**。
- **不重复计入**：已确认记录不会因重启重放（WAL 只回放 `seq > confirmedSeq`）；
  已按 DROP_OLDEST / REJECT_NEWEST 处理过的 seq 不复活、不再次计数；
  `nextSeq` 接续旧值分配（seq 不复用）；每设备最后接受时间戳基线恢复，乱序判断连续。
- 重启后继续采集/补传一段时间，`reconcile()` 仍成立；跨重启总账
  `总分配 seq = 旧下游 + 新下游 + 丢弃` 守恒（见集成测试断言）。

兼容范围：运行时基线按 `deviceId` 关联。本次设备列表中的设备从持久化基线恢复；
基线文件缺失（首次启动）时全部按初始态启动。**即使设备本次不在列表中（已移除）**，
其健康/计数基线仍会被恢复并参与全局守恒（不启动采集线程），其 WAL 未确认记录在
通道恢复后仍会被顺序补传送达；这样"全局计数 / nextSeq / pending"三方在设备增删后
依然对得上（见 `RestartWithRemovedDeviceTest`）。

## 9. 并发与一致性锁

- 网关持有一把 `ReentrantReadWriteLock`：健康状态机、逐设备/全局计数器的变更，
  以及确认位点推进都在**写锁**内；`snapshot()` / `reconcile()` 在读锁内一次性
  采集缓冲位点 + pending 分组 + 全部计数。
- 锁序固定为 **网关锁 → 缓冲锁**，不存在反向获取，因此无死锁；
  `device.poll()` 与上行 `send()`（可能较慢/阻塞）都在锁外执行，锁内只做内存计算、
  归一化与文件落盘，采集线程之间不会因串行化而被彼此的设备 I/O 拖住。

## 10. 可观测性

日志标记（单测同样打印，便于核对结论）：

- `[ACCEPT]`：原始读数 → 归一化记录（含分配到的 seq）；
- `[REJECT]`：被拒绝原始读数 + 可区分 `reason` + `detail`；
- `[BUFFER-FULL DROP-OLDEST|REJECT-NEWEST]`：溢出处理明细与容量、位点；
- `[FORWARD]`：补传位点推进（confirmedSeq、本批条数、剩余 pending）；
- `[UPLINK] down/up`：上行中断与恢复（打印恢复位点；不影响健康分级）；
- `[DEVICE] online=true/false`、`[DEVICE-ERROR]`；
- `[HEALTH]`：设备健康状态切换（from → to、时间、确定性原因）。

`GatewaySnapshot` 字段：`uplinkConnected, nextSeq, confirmedSeq, pendingCount,
polled, accepted, delivered, dropped(=droppedOldest+shedNewest),
rejectedByReason(各原因分项), devices(逐设备), reconciliation(一致性核对结论)`。
`DeviceStats` 在原有在线状态/计数/最后错误之外，新增 `health, consecutiveFailures,
consecutiveSuccesses, lastHealthTransition, receivedCount, shedNewestCount,
droppedOldestCount, deliveredCount, pendingCount`。

## 11. 测试与验证方法

| 测试类 | 覆盖点 |
|---|---|
| `NormalizerTest`（8） | 三类类型转换、边界值、全部 6 种归一化拒绝原因、多异常时的原因优先级 |
| `SpoolBufferTest`（5） | seq 单调分配、重复/回退/超前确认无效、DROP_OLDEST 与 REJECT_NEWEST 边界、WAL 重启只回放未确认、丢弃记录重启不复活 |
| `ForwardWorkerTest`（4） | 启动即断网→重连顺序补传无缺口、8 线程并发 kick 无重复、同批重复发送幂等、Worker 重启从已确认位点续传 |
| `GatewayIntegrationTest`（5） | 单设备异常不影响其它设备且统计如实、畸形+乱序读数原因可区分且脏值不到下游、断网溢出 DROP_OLDEST 端到端守恒、网关重启不重放已确认且 seq 连续、重启后乱序时间戳位点恢复 |
| `DeviceHealthTrackerTest`（6） | 连续失败降级→严重故障、首次成功进恢复观察且满窗口才回正常、观察期再失败退回降级、严重故障同样走观察、重启健康/连胜基线续算、非法阈值拒绝 |
| `ReconcilerTest`（4） | 自洽快照零差异、逐设备不一致精确点名设备+指标、全局不一致点名全局指标、溢出/pending 下 seq 守恒 |
| `HealthAndReconciliationIntegrationTest`（5） | 反复出错后 DEGRADED→CRITICAL 且可查切换原因时间、**上行中断设备保持 HEALTHY**、恢复观察满窗口才回正常、采集与补传并发下连续多版核对自洽、**重启健康/统计基线续算且不重复计数** |
| `RejectNewestReconciliationTest`（1） | REJECT_NEWEST 溢出（shedNewest 缺口）下 seq 分配口径 `nextSeq = accepted + shedNewest` 与去向守恒仍成立 |
| `RestartWithRemovedDeviceTest`（1） | 移除设备后重启：其历史未确认记录仍被补传送达，基线保留参与全局守恒，核对自洽 |

测试日志中可直接看到 `[健康分级]`、`[恢复观察]`、`[上行中断不误判]`、
`[并发核对]`、`[重启续算]`、`[统计核对]` 以及原有的 `[原始读数]`、
`[归一化结果]`、`[补传位点]` 三类打印，每类业务的输入条件与断言结果均可见。
关键一致性断言：下游记录严格按 seq 升序且全局唯一；
`已分配 seq 数 = 下游记录数 + 溢出丢弃数`；拒绝原因分项计数与注入的异常一一对应；
任意时刻 `reconcile().consistent()` 为真（并发压测中连续采样多版）；
重启后全局接受数恰为"旧基线 + 本段新增"，新下游只收到大于旧 confirmedSeq 的记录。

本地验证：

```bash
mvn test                      # 40 个用例，BUILD SUCCESS
# 或只跑本轮新增：
mvn -Dtest=DeviceHealthTrackerTest,ReconcilerTest,HealthAndReconciliationIntegrationTest test
# 演示（结束时打印逐设备核对与全局守恒式）：
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes:$(cat target/cp.txt)" \
     com.github.highcumontoa.edgedatagatewayjava.demo.DemoRunner
```

## 12. 配置

`GatewayProperties(dataDir, bufferCapacity, overflowPolicy, collectIntervalMillis,
drainIntervalMillis, batchSize, health)`，测试与演示直接构造；生产可由 Spring 配置绑定。
默认值：容量 1000、`DROP_OLDEST`、采集 100ms、补传 50ms、批 100、
健康阈值 `HealthConfig(3, 5, 3)`（降级失败 3 次、严重故障 5 次、恢复观察连续成功 3 次）。
不含 `health` 的旧构造器仍可用（取默认阈值）。
