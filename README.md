# 工业边缘采集网关（本地可运行）

在 Spring Boot 3 / Java 21 骨架之上实现的一套**本地可运行**的工业边缘采集网关。
现场设备与上行通道均为确定性的本地模拟实现，无需任何外部依赖即可演示并验证：

- 多设备点位采集与**数据归一化**；
- 上行中断时的**本地持久缓冲**与恢复后的**顺序补传**；
- 断网、重复投递、畸形读数、缓冲溢出、单设备故障与并发场景下，
  结论**一致、可解释、可复现**。

---

## 1. 构建与运行

```bash
# 构建 + 全部测试（21 个测试，无需外部服务）
mvn test

# 本地启动（默认 8080 端口，自动装配两台演示设备并开始采集/补传）
mvn spring-boot:run
```

启动后可通过运维接口观察与操控：

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/gateway/status` | GET | 运行状态、通道在线、缓冲 pending/容量/nextSeq、下游记录数、各设备 offset/接受/拒绝/背压/错误 |
| `/api/gateway/channel?state=offline` | POST | 模拟断网 |
| `/api/gateway/channel?state=online` | POST | 模拟恢复 |
| `/api/gateway/collect?deviceId=device-A` | POST | 立即执行一个采集周期 |
| `/api/gateway/flush` | POST | 立即执行一轮补传 |

典型手工演练：

```bash
curl -X POST 'http://localhost:8080/api/gateway/channel?state=offline'   # 断网
curl -s http://localhost:8080/api/gateway/status                          # 观察 spoolPending 增长
curl -X POST 'http://localhost:8080/api/gateway/channel?state=online'    # 恢复
for i in $(seq 1 5); do curl -s -X POST http://localhost:8080/api/gateway/flush >/dev/null; done
curl -s http://localhost:8080/api/gateway/status                          # spoolPending 归零
```

运行期持久数据默认位于 `data/gateway/`（已加入 `.gitignore`）。

### 关键配置（`application.properties`，前缀 `edge.gateway`）

| 配置项 | 默认值 | 含义 |
|---|---|---|
| `data-dir` | `data/gateway` | spool / 位点 / 拒绝日志目录 |
| `spool-capacity` | `1000` | 未确认记录条数上限（缓冲容量） |
| `poll-batch-size` | `5` | 每设备每轮最多采集条数 |
| `collect-interval-ms` | `200` | 采集周期 |
| `replay-interval-ms` | `200` | 补传周期 |
| `auto-start` | `true` | 启动后自动开始采集与补传 |
| `demo-devices` | `true` | 装配内置的两台演示设备 |

---

## 2. 架构与数据流

```
        ┌ 每个设备独立单线程（故障隔离）─────────────┐
 MockDevice ──poll(offset)──▶ ReadingNormalizer        │
   (现场设备)                  │ 校验+归一化            │
                 ┌────────────┴────────────┐          │
              通过                          拒绝        │
                 ▼                           ▼          │
           SpoolStore.append          RejectionLog   ──┘ 位点 checkpoint
        (持久 WAL, 分配全局 seq)     (rejections.jsonl)    每条处理后落盘
                 │
                 ▼  （断网期间只积累、不确认）
           ReplayService ──按 seq 升序整批投递──▶ MockUpstreamChannel
                 ▲                                   │（seq + 自然键双重去重）
                 └── 仅成功返回后 acknowledge，压缩 spool
```

核心类：

| 类 | 职责 |
|---|---|
| `device.MockDevice` / `DemoDeviceFactory` | 脚本化模拟设备与点位表；脚本含各类畸形读数 |
| `normalize.ReadingNormalizer` | 七步固定顺序校验 + 归一化，无状态 |
| `collect.DeviceCollectorTask` | 单条读数处理，产出 `Accepted`/`Rejected` 可区分结果 |
| `collect.CollectorSupervisor` | 每设备独立调度线程、位点推进、背压、统计 |
| `spool.SpoolStore` | 持久缓冲：追加 fsync、确认压缩、重启恢复、容量上限 |
| `spool.DeviceCheckpointStore` | 每设备 offset + 最近已接受时间戳，原子写 |
| `spool.RejectionLog` | 拒绝读数 JSONL 持久日志 |
| `replay.ReplayService` | 升序补传、整批失败即止、确认幂等 |
| `upstream.MockUpstreamChannel` | 模拟断网/恢复、瞬时故障、下游去重 |
| `gateway.GatewayOrchestrator` | 装配、恢复、生命周期 |

---

## 3. 采集与归一化规则

每条原始读数 `RawReading(deviceId, pointId, value, unit, timestamp)` 按
**固定的七步顺序**校验（顺序即拒绝原因的优先级，保证同一读数在任何并发/重试场景下结论一致）：

1. **点位存在性** — 点位不在设备点位表中：`UNKNOWN_POINT`
2. **值缺失** — `value == null`：`VALUE_MISSING`
3. **类型匹配** —
   - 点位为 `DOUBLE` 时，值必须是 JVM `Number`（字符串 `"21.5"` **不**隐式转换）；
   - 点位为 `BOOLEAN` 时，值必须是 JVM `Boolean`；
   否则：`TYPE_MISMATCH`
4. **单位匹配** — 读数单位必须与点位声明单位完全一致（缺失也算不匹配）：`UNIT_MISMATCH`
5. **量程** — `DOUBLE` 且落在 `[min, max]` 闭区间之外：`OUT_OF_RANGE`
6. **时间戳存在** — `timestamp == null`：`TIMESTAMP_MISSING`
7. **时间戳顺序** — 同一设备内时间戳必须**非递减**；严格回退：`TIMESTAMP_OUT_OF_ORDER`
   - 同一扫描周期内**不同点位**时间戳相等是正常的，允许通过；
   - 乱序判定基准是“该设备最近一条**已接受**读数的时间戳”，该基准随位点持久化，
     因此**重启后乱序判定仍然一致**。

通过校验的读数归一化为统一上行记录
`NormalizedRecord(seq, deviceId, pointId, dataType, unit, numeric, flag, timestamp)`。

**拒绝处理原则：绝不静默丢弃，绝不写入脏值。**
被拒绝读数不会进入上行缓冲，而是生成
`RejectedReading(raw, reason, detail, rejectedAt)` 追加到 `rejections.jsonl`，
同时按原因累加到设备统计；拒绝读数的位点照常推进（不会反复重试脏值）。

---

## 4. 断网缓冲与补传语义

### 4.1 持久缓冲（`SpoolStore`）

- 数据目录下 `spool.dat`（仅未确认记录的 TSV 日志）、`spool.meta`（全局 `nextSeq`）。
- `append` 时写入数据行并 `force` 落盘，再推进 `nextSeq`；序列号**全局单调递增**。
- 仅当上行通道**成功返回确认**后才 `acknowledge` 并以“临时文件 + 原子移动”压缩重写。
- 所有公开方法串行化；重启时从文件完整恢复未确认记录与 `nextSeq`。

### 4.2 补传（`ReplayService`）

- 严格按 **seq 升序**（即入缓冲时间顺序）批量投递，单批 50 条。
- **整批原子**：通道离线或任一批投递抛 `ChannelException` 时，该批一条都不确认、
  缓冲不变，并从**最旧未确认位点**停止本轮，等待下个周期重试；
  绝不会越过缺口先投递后面的数据（日志可见 `REPLAY-HOLD` / `REPLAY-RETRY` /
  `REPLAY-ACK ... cursorAfter=...`）。
- **确认幂等**：对已确认或不存在的 seq 重复确认无副作用；缓冲为空时重复 flush 无效果。

### 4.3 下游幂等（两层去重）

`MockUpstreamChannel` 模拟真实下游存储：

1. **按 seq 去重**：同一 seq 重复投递（补传重试、重复确认窗口）只确认、不新增记录；
2. **按自然键 `(deviceId, pointId, timestamp)` 去重**：
   覆盖“已入 spool 但位点尚未落盘即崩溃 → 重启后重采产生新 seq”这一窗口。
   自然键重复时不新增下游记录，但仍确认新 seq 使其能从缓冲清理。

因此：**重复投递或重复确认，下游都不会出现重复记录。**

### 4.4 缓冲容量上限（有界且确定）

容量 = 未确认记录条数上限。打满时采取**背压（backpressure）策略**，该策略是确定且可解释的：

- **拒绝新写入、保留最旧未确认数据**（不覆盖、不淘汰旧数据、不无界增长）；
- 抛出 `SpoolFullException`（日志 `[SPOOL-BACKPRESSURE]`），
  设备统计 `backpressure` 计数 +1，`lastError` 记录容量与最旧 seq；
- 该读数的采集位点**不推进**，下轮从同一读数重试；
  通道恢复、补传确认腾出空间后自动续传，数据**不丢不重**。
- 畸形读数不占缓冲（直接进拒绝日志并推进位点），因此溢出只由“通道长期不可用”引起。

---

## 5. 并发与故障边界

- **线程模型**：每个设备一个独立的单线程调度执行器（`collector-<deviceId>`），
  补传独占一个线程（`replay-worker`）。
- **单设备故障隔离**：设备 `poll` 抛任何异常都被捕获在本设备线程内，
  该设备标记为离线（`online=false`）、累加 `consecutiveErrors` 与 `lastError`、
  位点不动；其它设备的采集、缓冲与补传完全不受影响（见 `DeviceFailureIsolationTest`）。
- **位点一致性**：处理顺序严格为
  「归一化通过 → append spool 落盘 → 保存 checkpoint(offset+1, 最近时间戳)」；
  畸形读数为「写 rejection 日志 → 保存 checkpoint」。
  崩溃窗口最坏情况是位点落后导致重采，由时间戳乱序规则 + 下游自然键去重兜底，
  **不会从错误位点继续，也不会丢失已确认数据**。
- **统计一致性**：在线状态仅反映最近一次设备轮询是否成功；
  accepted/rejected/backpressure/offset 均在实际处理后更新，测试对其逐项断言。
- **重启恢复**：spool、checkpoint、rejection 均为磁盘文件；
  全新进程启动时先恢复再采集，未确认数据从最旧 seq 继续补传（见
  `RecoveryWhileOfflineIntegrationTest`）。

---

## 6. 测试与可解释性

`src/test/.../tests` 共 21 个测试，全部为确定性本地测试（使用 JUnit `@TempDir`，无端口依赖）：

| 测试类 | 覆盖内容 |
|---|---|
| `NormalizerTest` | 七类拒绝原因可区分；相等时间戳跨点位允许、严格回退拒绝 |
| `SpoolStoreTest` | 容量边界（拒绝新写入、保留最旧）、确认幂等、重启恢复 pending 与 seq、升序 |
| `MockUpstreamChannelTest` | 断网全有或全无、seq 去重、自然键去重、瞬时失败重试 |
| `DisconnectReconnectIntegrationTest` | 断网缓冲、断网 flush 不改缓冲、恢复后升序补传、下游无重复 |
| `BufferOverflowIntegrationTest` | 溢出背压、位点停住、恢复后续传，25 条不丢不重 |
| `MalformedAndRestartIntegrationTest` | 七类原因全部落拒绝日志且计数一致；重启位点恢复、不重复采集 |
| `RecoveryWhileOfflineIntegrationTest` | 断网期间崩溃重启：未确认缓冲恢复、从 seq=0 补传、跨崩溃不丢不重 |
| `DuplicateReplayTest` | 重复 flush / 重复 ack 幂等；瞬时失败重试恰好投递一次 |
| `DeviceFailureIsolationTest` | 故障设备不影响健康设备（真实周期线程） |
| `ConcurrentCollectReplayTest` | 3 设备并发采集 + 补传中途断网恢复，750 条 seq 0..749 连续无重 |
| `ApplicationTests` | Spring 全栈上下文装配冒烟 |

**单测日志可观测**（测试运行时直接打印）：

- 原始读数与归一化结果：`[COLLECT-ACCEPT] raw=... normalized=...`、
  `[NORMALIZER-OK]`；
- 拒绝原始读数与原因：`[COLLECT-REJECT] reason=... raw=... detail=...`、
  `[REJECTED-READING]`、`[REJECT-SUMMARY]`；
- 补传位点：`[REPLAY-HOLD] keep seq[from, to]`、`[REPLAY-RETRY] ... at seq[...]`、
  `[REPLAY-ACK] delivered+acked seq[from, to], cursorAfter=..., pending=...`；
- 缓冲溢出：`[SPOOL-BACKPRESSURE] ... offset=... stays unchanged`；
- 重启位点：`[RUN1] offsetA=... offsetB=...`、`[RUN2] newAccepted=0 ...`。

```bash
# 运行并查看这些日志
mvn test -Dtest=DisconnectReconnectIntegrationTest
```

---

## 7. 目录下的持久文件

| 文件 | 内容 |
|---|---|
| `spool.dat` | 尚未确认的归一化记录（TSV，仅 pending） |
| `spool.meta` | 下一个全局序列号 `nextSeq` |
| `checkpoints.dat` | 每行 `deviceId\toffset\tlastAcceptedTimestamp` |
| `rejections.jsonl` | 每行一条被拒绝读数（原始读数 + reason + detail + 时间） |
