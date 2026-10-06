# 工业边缘采集网关（本地可运行）

基于 Spring Boot 3 / Java 21 的本地模拟边缘网关：从多个模拟设备并发采集点位读数，
归一化为统一上行记录；上行中断时本地有界缓冲，恢复后按时间顺序补传；
对越界、乱序、类型/单位不匹配等畸形读数**只带可区分原因拒绝，绝不静默丢弃或写入脏值**。
在此之上提供**设备健康分级**（正常/反复出错/严重故障/恢复观察）、**运行中统计一致性核对**
（逐设备指出哪类计数对不上）与**重启后续算**（健康与统计基线衔接重启前，不清零、不重复计数）。

不依赖任何外部中间件：设备与上行通道均为进程内模拟，断网/重连/设备故障均可确定性复现。

## 1. 构建与运行

```bash
mvn test          # 37 个单测，覆盖断网重连、重复补传、畸形读数、缓冲溢出边界、
                  # 健康分级、恢复观察、并发核对自洽、统计对账、重启续算
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes:$(cat target/cp.txt)" \
     com.github.highcumontoa.edgedatagatewayjava.demo.DemoRunner
```

演示剧本（约 5 秒）：两设备并发采集 → 上行断网 2s（缓冲积压并触发 DROP_OLDEST）
→ 恢复后顺序补传 → 一台设备掉线 1s 后恢复（另一台不受影响，且该设备经历
DEGRADED → RECOVERING → HEALTHY 的完整健康切换）。结束时打印最终位点、计数器、
下游去重记录、逐设备健康状态（含最近切换原因）与统计核对结论。

## 2. 模块结构

| 包 | 类 | 职责 |
|---|---|---|
| `model` | `RawReading` / `NormalizedRecord` / `PointDefinition` / `RejectReason` / `OverflowPolicy` | 领域模型与枚举 |
| `device` | `SimulatedDevice` / `ScriptedDevice` | 本地模拟设备，按脚本批次产出读数，可抛异常、可上/下线 |
| `normalize` | `Normalizer` | 无状态校验 + 类型转换，输出接受记录或带原因的拒绝 |
| `buffer` | `SpoolBuffer` / `AcceptResult` / `BufferStateStore` | 有界缓冲、WAL、seq 位点与溢出策略 |
| `uplink` | `UplinkChannel` / `MockUplinkChannel` | 上行通道抽象与模拟（断网/重连、按 seq 幂等去重） |
| `forward` | `ForwardWorker` | 单线程顺序补传，断网重试，重复投递/确认安全 |
| `collect` | `CollectorSupervisor` / `AcceptOutcome` / `DeviceStats` | 每设备独立调度线程，异常隔离、在线状态；按网关处置结果计数，逐台与整体同口径 |
| `stats` | `StatsRegistry` / `StatsSnapshot` / `DeviceCounters` / `DeviceStateStore` | 全局+逐设备计数（单锁自洽快照）、设备级状态持久化 |
| `health` | `HealthMonitor` / `HealthState` / `DeviceHealth` | 设备健康分级状态机，只由真实轮询结果驱动 |
| `audit` | `ConsistencyAuditor` / `AuditReport` | 运行中统计一致性核对，逐设备指出不一致类别 |
| `gateway` | `EdgeGateway` / `GatewaySnapshot` | 编排与一致性保证 |
| `config` | `GatewayProperties` | 数据目录、容量、溢出策略、采集/补传周期、批大小、健康阈值 |
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

每台设备除在线/离线（`DeviceStatus`，反映最近一次轮询是否成功）外，还有独立的
**健康状态**（`HealthState`），只由该设备**自身真实轮询结果**驱动：

| 状态 | 含义 | 进入条件 |
|---|---|---|
| `HEALTHY` | 正常，采集稳定 | 初始状态；或恢复观察期连续成功达到 `recoverAfterSuccesses` |
| `DEGRADED` | 反复出错 | 连续失败 ≥ `degradeAfterFailures`；或恢复观察期内再次出错 |
| `FAULT` | 已判定严重故障 | 连续失败 ≥ `faultAfterFailures` |
| `RECOVERING` | 故障后恢复观察中 | DEGRADED/FAULT 后首次轮询成功；连续成功不足恢复阈值前保持 |

关键语义：

- **上行通道短时中断不影响健康判定**：健康输入只有 `poll()` 的成功/失败，
  断网、缓冲溢出、补传滞后都不会把设备算成不健康（断网期间设备正常采集时
  保持 `HEALTHY`）。
- **恢复观察**：故障设备重新成功后先进入 `RECOVERING`，必须连续成功达到阈值
  才回到 `HEALTHY`，避免"刚恢复一次就被当成完全正常"；观察期内再次出错立即
  退回 `DEGRADED`，连续失败计数继续累计，达到故障阈值仍会判 `FAULT`。
- 每次切换都记录**原因与时间**：`DeviceHealth.lastTransitionReason /
  lastTransitionAt`，连同 `lastError / consecutiveFailures /
  consecutiveSuccesses` 可通过 `EdgeGateway.deviceHealth()` 查询，
  切换时打印 `[HEALTH]` 日志并立即落盘。
- 归一化内部防御性异常（设计上不发生）计为 `internalErrors`，**不**参与健康判定。

## 7. 运行中统计一致性核对

`EdgeGateway.audit()` 随时返回一份 `AuditReport`（`CONSISTENT` / `INCONSISTENT`
/ `UNSTABLE`），核对口径：

| 核对项 | 关系 | 粒度 |
|---|---|---|
| 轮询守恒 | `polls == okPolls + pollFailures` | 逐设备 |
| 全局=逐设备之和 | polled / accepted / delivered / droppedOldest / rejectedNewest / 各拒绝原因 | 全局 |
| 序号守恒 | `nextSeq == accepted + rejectedNewest`（每个被接受记录消耗一个 seq，REJECT_NEWEST 也消耗一个） | 全局 |
| 流向守恒 | `accepted == delivered + droppedOldest + pending` | 全局 + 逐设备 |

发现不一致时，`violations` 逐条给出**哪台设备（全局关系为 null）、哪一类计数
（`Category`）、期望值与实际值**，而不是笼统的失败结论。

### 7.1 逐台统计与整体统计的口径关系

一条读数在任一计数体系里**要么算接受、要么算拒绝、要么算缓冲拒新，
三选一且只算一次**，逐台与整体同口径：

- 归一化拒绝与网关乱序拒绝（`TIMESTAMP_OUT_OF_ORDER`）都计入
  `rejectedByReason`：逐台各原因之和 == 整体对应原因；
- 通过全部校验并写入缓冲才计入 `accepted`：逐台之和 == 整体；
- 缓冲满且 `REJECT_NEWEST` 时被拒的新记录计入 `rejectedNewest`（不占
  `accepted`、不占 `rejectedByReason`）：逐台之和 == 整体；
- 采集侧运行计数（`DeviceStats`）与统计登记处（`StatsRegistry` 逐设备
  `DeviceCounters`）一一对应：`acceptedCount == accepted`、
  `rejectedCount == ΣrejectedByReason`、`droppedNewestCount == rejectedNewest`。
  关键在采集线程不预先计数，而是以网关 `onAccepted` 返回的处置结果
  （`AcceptOutcome`）为准——乱序读数不会出现在"设备侧算接受、整体算拒绝"
  的撕裂状态；
- 重启续算时两侧衔接同一恢复基线（`StatsRegistry.restore` 恢复逐设备计数并
  求和出全局；`CollectorSupervisor.restoreBaseline` 用同一基线初始化采集侧
  运行计数），重启后逐台与整体不错位。

### 7.2 并发下核对结论的判定边界

- 统计快照在 `StatsRegistry` 单锁内生成，本身不会被并发写撕裂；
- 与缓冲联动的计数（accepted / delivered / droppedOldest / rejectedNewest）
  由 `SpoolBuffer.MutationListener` **在缓冲锁内**与位点变更同时生效
  （锁序固定：缓冲锁 → 统计锁），因此缓冲锁释放的瞬间"统计 ↔ 缓冲位点"
  必然互相吻合；
- 核对器按"读缓冲视图 → 读统计快照 → 再读缓冲视图，两次视图相等"取一致性
  切面：切面稳定即保证窗口内没有任何 append/confirm，统计联动计数也未变化，
  此时给出的 `CONSISTENT` / `INCONSISTENT` 都是基于同一时刻的真实结论；
- 切面不稳定就重试（最多 100 次，间隔 1ms）；采集与补传持续高频写入、
  重试耗尽仍取不到稳定切面时返回 **`UNSTABLE`——即"当前无法判定"**，
  报告里不带任何核对结论，绝不把不同时刻的数字拼成一份自相矛盾的报告。
  `UNSTABLE` 是瞬时状态：写入停顿后再次核对即可得到确定结论。

## 8. 重启续算与兼容范围

数据目录下有三类持久化文件，各司其职：

| 文件 | 内容 | 保证 |
|---|---|---|
| `spool.wal` + `buffer-state.json` | 未确认记录、seq 位点、每设备最后时间戳 | 已确认数据不重放、未确认按序重放、seq 不复用 |
| `device-state.json` | 逐设备统计基线（轮询/接受/拒绝/送达/丢弃）+ 健康状态、连续失败/成功次数、最近错误、最近切换原因与时间 | 重启后续算：不清零重算、已确认/已溢出处理的数据不再次计入 |

- 落盘时机：每次健康状态切换、以及 `shutdown()` 时（临时文件 + 原子 rename）。
- **优雅停机后重启**：健康状态（如 FAULT）、连续失败/成功次数、统计基线完整
  衔接，继续运行后 `audit()` 仍成立；全局计数由逐设备基线求和恢复，
  "全局=逐设备之和"天然成立；采集侧运行计数（DeviceStats）也用同一基线
  初始化，重启后逐台与整体不错位。
- **崩溃（非优雅停机）**：缓冲位点由 WAL 保证不丢不重；统计与健康状态回退到
  最近一次落盘（最后一次健康切换或上次优雅停机），可能丢失其后的计数增量——
  属于已声明边界，不会因此产生重复计数。
- **旧版本数据目录升级**：`device-state.json` 缺失或字段缺失时按零基线 /
  `HEALTHY` 处理，未知字段忽略，可直接升级。

## 9. 可观测性

日志标记（单测同样打印，便于核对结论）：

- `[ACCEPT]`：原始读数 → 归一化记录（含分配到的 seq）；
- `[REJECT]`：被拒绝原始读数 + 可区分 `reason` + `detail`；
- `[BUFFER-FULL DROP-OLDEST|REJECT-NEWEST]`：溢出处理明细与容量、位点；
- `[FORWARD]`：补传位点推进（confirmedSeq、本批条数、剩余 pending）；
- `[UPLINK] down/up`：上行中断与恢复（打印恢复位点）；
- `[DEVICE] online=true/false`、`[DEVICE-ERROR]`；
- `[HEALTH]`：设备健康状态切换（含原因与时间）；
- `[INTERNAL-ERROR]`：归一化内部防御性异常（不影响健康判定）。

`GatewaySnapshot` 字段：`uplinkConnected, nextSeq, confirmedSeq, pendingCount,
polled, accepted, delivered, dropped, rejectedByReason(各原因分项), devices(逐设备),
health(逐设备健康)`。核对结论通过 `EdgeGateway.audit()` 获取。

## 10. 测试与验证方法

| 测试类 | 覆盖点 |
|---|---|
| `NormalizerTest`（8） | 三类类型转换、边界值、全部 6 种归一化拒绝原因、多异常时的原因优先级 |
| `SpoolBufferTest`（5） | seq 单调分配、重复/回退/超前确认无效、DROP_OLDEST 与 REJECT_NEWEST 边界、WAL 重启只回放未确认、丢弃记录重启不复活 |
| `ForwardWorkerTest`（4） | 启动即断网→重连顺序补传无缺口、8 线程并发 kick 无重复、同批重复发送幂等、Worker 重启从已确认位点续传 |
| `GatewayIntegrationTest`（5） | 单设备异常不影响其它设备且统计如实、畸形+乱序读数原因可区分且脏值不到下游、断网溢出 DROP_OLDEST 端到端守恒、网关重启不重放已确认且 seq 连续、重启后乱序时间戳位点恢复 |
| `HealthMonitorTest`（4） | 反复出错 HEALTHY→DEGRADED→FAULT 且切换原因/时间可查、恢复观察通过才回正常、观察期复发退回降级、重启恢复后续算不重新累计 |
| `HealthAndAuditTest`（6） | 网关端到端：反复出错降级、上行中断不误判设备不健康、恢复观察期可观察、并发采集+断连+溢出下 300 次核对无自相矛盾、重启后健康与统计续算不重复计数、REJECT_NEWEST 序号守恒 |
| `StatsReconciliationTest`（4） | 统计对账：乱序读数被拒后逐台与整体同口径（两侧都算拒绝）、REJECT_NEWEST 逐台拒新==整体拒新、并发采集+补传+断连+溢出下 400 次核对每份报告自洽（CONSISTENT 报告内部数字复核吻合，否则 UNSTABLE）、断网积压/溢出丢弃/乱序拒绝后重启续算再续跑全程对账成立 |

测试日志中可直接看到 `[原始读数]`、`[归一化结果]`、`[补传位点]` 三类打印，
`[对账]` 前缀的日志逐条给出每个用例覆盖的业务与对账结果。
关键一致性断言：下游记录严格按 seq 升序且全局唯一；
`已分配 seq 数 = 下游记录数 + 溢出丢弃数`；拒绝原因分项计数与注入的异常一一对应；
逐设备 `acceptedCount/rejectedCount/droppedNewestCount` 与统计登记处逐设备计数
逐项相等，且逐台之和等于整体。

## 11. 配置

`GatewayProperties(dataDir, bufferCapacity, overflowPolicy, collectIntervalMillis,
drainIntervalMillis, batchSize, degradeAfterFailures, faultAfterFailures,
recoverAfterSuccesses)`，测试与演示直接构造；生产可由 Spring 配置绑定。
默认值：容量 1000、`DROP_OLDEST`、采集 100ms、补传 50ms、批 100、
健康阈值 3 / 6 / 3（降级 / 故障 / 恢复观察）。旧 6 参构造器保留，健康阈值取默认值。
