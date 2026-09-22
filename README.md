# 工业边缘采集网关（本地可运行）

基于 Spring Boot 3 / Java 21 的本地模拟边缘网关：从多个模拟设备并发采集点位读数，
归一化为统一上行记录；上行中断时本地有界缓冲，恢复后按时间顺序补传；
对越界、乱序、类型/单位不匹配等畸形读数**只带可区分原因拒绝，绝不静默丢弃或写入脏值**。

不依赖任何外部中间件：设备与上行通道均为进程内模拟，断网/重连/设备故障均可确定性复现。

## 1. 构建与运行

```bash
mvn test          # 23 个单测，覆盖断网重连、重复补传、畸形读数、缓冲溢出边界
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes:$(cat target/cp.txt)" \
     com.github.highcumontoa.edgedatagatewayjava.demo.DemoRunner
```

演示剧本（约 5 秒）：两设备并发采集 → 上行断网 2s（缓冲积压并触发 DROP_OLDEST）
→ 恢复后顺序补传 → 一台设备掉线 1s 后恢复（另一台不受影响）。结束时打印
最终位点、计数器与下游收到的去重记录。

## 2. 模块结构

| 包 | 类 | 职责 |
|---|---|---|
| `model` | `RawReading` / `NormalizedRecord` / `PointDefinition` / `RejectReason` / `OverflowPolicy` | 领域模型与枚举 |
| `device` | `SimulatedDevice` / `ScriptedDevice` | 本地模拟设备，按脚本批次产出读数，可抛异常、可上/下线 |
| `normalize` | `Normalizer` | 无状态校验 + 类型转换，输出接受记录或带原因的拒绝 |
| `buffer` | `SpoolBuffer` / `AcceptResult` / `BufferStateStore` | 有界缓冲、WAL、seq 位点与溢出策略 |
| `uplink` | `UplinkChannel` / `MockUplinkChannel` | 上行通道抽象与模拟（断网/重连、按 seq 幂等去重） |
| `forward` | `ForwardWorker` | 单线程顺序补传，断网重试，重复投递/确认安全 |
| `collect` | `CollectorSupervisor` | 每设备独立调度线程，异常隔离、在线状态与设备统计 |
| `stats` | `Counters` | 全局线程安全计数 |
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

## 5. 并发与故障边界

- 每个设备拥有**独立的单线程调度器**（`collector-<deviceId>`），补传为独立
  单线程（`forward-worker`）。采集与缓冲交互通过 `SpoolBuffer` 的内置锁串行化。
- 设备轮询中的任何 `Throwable`（含脚本主动抛出的故障、设备掉线异常）都在该设备
  线程内捕获，记为 `onDeviceError`、置设备 `DOWN`，**不会传播到调度器或其它设备**；
  下一次轮询成功即恢复 `UP`。在线状态与逐设备统计（轮询/错误/接受/拒绝次数、
  最后错误）通过 `EdgeGateway.snapshot()` / `deviceStats()` 读取，与实际一致。
- 上行断网不影响采集：记录继续进缓冲（满则按策略处理）。
- 关闭顺序：先停采集、再停补传，保证关闭期间无新记录与补传竞争。

## 6. 可观测性

日志标记（单测同样打印，便于核对结论）：

- `[ACCEPT]`：原始读数 → 归一化记录（含分配到的 seq）；
- `[REJECT]`：被拒绝原始读数 + 可区分 `reason` + `detail`；
- `[BUFFER-FULL DROP-OLDEST|REJECT-NEWEST]`：溢出处理明细与容量、位点；
- `[FORWARD]`：补传位点推进（confirmedSeq、本批条数、剩余 pending）；
- `[UPLINK] down/up`：上行中断与恢复（打印恢复位点）；
- `[DEVICE] online=true/false`、`[DEVICE-ERROR]`。

`GatewaySnapshot` 字段：`uplinkConnected, nextSeq, confirmedSeq, pendingCount,
polled, accepted, delivered, dropped, rejectedByReason(各原因分项), devices(逐设备)`。

## 7. 测试与验证方法

| 测试类 | 覆盖点 |
|---|---|
| `NormalizerTest`（8） | 三类类型转换、边界值、全部 6 种归一化拒绝原因、多异常时的原因优先级 |
| `SpoolBufferTest`（5） | seq 单调分配、重复/回退/超前确认无效、DROP_OLDEST 与 REJECT_NEWEST 边界、WAL 重启只回放未确认、丢弃记录重启不复活 |
| `ForwardWorkerTest`（4） | 启动即断网→重连顺序补传无缺口、8 线程并发 kick 无重复、同批重复发送幂等、Worker 重启从已确认位点续传 |
| `GatewayIntegrationTest`（5） | 单设备异常不影响其它设备且统计如实、畸形+乱序读数原因可区分且脏值不到下游、断网溢出 DROP_OLDEST 端到端守恒、网关重启不重放已确认且 seq 连续、重启后乱序时间戳位点恢复 |

测试日志中可直接看到 `[原始读数]`、`[归一化结果]`、`[补传位点]` 三类打印。
关键一致性断言：下游记录严格按 seq 升序且全局唯一；
`已分配 seq 数 = 下游记录数 + 溢出丢弃数`；拒绝原因分项计数与注入的异常一一对应；
逐设备 `errorCount/acceptedCount` 与故障/恢复剧本一致。

## 8. 配置

`GatewayProperties(dataDir, bufferCapacity, overflowPolicy, collectIntervalMillis,
drainIntervalMillis, batchSize)`，测试与演示直接构造；生产可由 Spring 配置绑定。
默认值：容量 1000、`DROP_OLDEST`、采集 100ms、补传 50ms、批 100。
