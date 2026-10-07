# 本地租约锁协调器（Lease Fencing Coordinator）

一个**纯本地、无外部服务依赖**的命名租约锁协调器，让多个并发调用方在同一份服务状态下竞争同一把命名锁时，
锁的**归属、过期与接管结论始终可解释、可复现**。

- 所有判定都基于**跨实例文件锁 + 内置互斥锁**与一个**可注入时钟**，在临界区内一次性完成；
- 支持**同一台机器上多个实例共用同一份状态目录**：归属结论与栅栏令牌全局唯一，授予/续约/让出对所有实例即时可见；
- 状态持久化到本地 JSON 文件（临时文件 + 原子 rename），重启后结论一致、令牌不重发；
- 受保护操作可带**调用方身份**校验：只有“当前令牌对应的持有者本人 + 租约未到期”才放行；
- 持有者可在到期前**主动让出（release）**，让出立即生效、下一次授予令牌更大；
- 测试使用可控时钟（`ManualClock`）推进时间，**无需任何真实等待**。

---

## 1. 租约语义（Lease Semantics）

- 锁按 `lockName` 命名；调用方用 `holderId` 标识自己；申请时给出正数 TTL（毫秒）。
- 一次成功获取返回 `LeaseGrant`：持有者、栅栏令牌、取得时间 `acquiredAtMillis`、
  到期绝对时间 `expireAtMillis = acquire时刻 + ttl`、是否为接管 `takeover`。
- **同一把锁在任一时刻最多只有一个“有效（未到期）持有者”。** 并发获取由互斥锁串行化判定，
  只会有一个调用方拿到授予，其余得到 `HELD_BY_OTHER`。
- **过期判定是确定的边界比较**：`now >= expireAtMillis` 即视为已过期（到期时刻点本身算过期）。
  这让“恰好到期”的结论不依赖线程调度，可复现。
- **同一持有者在未到期时重复 `acquire`（重入）**：原样返回既有租约，
  **令牌不变、到期时间不变**，不会因为换了一个 TTL 就延长或缩短租期。
- 时间来自注入的 `Clock`，因此同一份输入状态 + 同一个时钟点，永远得到同一个结论。

### 续约（Renew）规则

- 只有**当前有效持有者本人**可以续约；续约**不改变持有者、不更换令牌**。
- 新到期时间取 `max(旧expireAt, now + ttl)`：
  - 申请的 TTL 让到期点更靠后 → 顺延；
  - 申请的 TTL 比“既有剩余租期”更短 → **到期时间保持不变，绝不缩短剩余租期**。
- 过期后不能续约（必须重新获取 / 接管并领取新令牌）。

---

## 2. 栅栏令牌契约（Fencing Token Contract）

- 令牌是**锁级单调递增的正整数**：该锁首次授予 = `1`；之后每次**新的授予**（过期接管 / 让出后重新获取）= `上一个令牌 + 1`。
- 令牌只在“锁的归属发生代际更替”（首次授予 / 接管过期持有者 / 让出后重新授予）时发放；
  **重入 acquire 与 renew 都不产生新令牌**。
- 每次成功获取的令牌**严格递增且永不回退**；令牌随状态持久化、由跨实例文件锁保护，
  **重启后、或换一个实例处理，都绝不重新发放**。
- 受保护操作有两种校验入口：
  - `validateProtectedOperation(lockName, token)`：**单实例兼容入口**，只接受
    `token == 当前最新令牌` **且** `租约未到期`。
  - `validateProtectedOperation(lockName, holderId, token)`：**推荐入口（带身份）**，
    只接受 `holderId == 当前令牌对应的持有者本人` 且 `token == 当前最新令牌` 且 `租约未到期`。
    换一个调用方拿着同一枚数字正确的令牌，也会以 `HOLDER_IDENTITY_MISMATCH` 拒绝。
- 其余情况全部显式拒绝，绝不静默接受。

这样下游存储只要按“拒绝更旧令牌”实现（fencing 模式），过期/被接管/已让出的旧持有者即使晚到也无法写入。

---

## 3. 过期与接管规则（Expiry & Takeover）

1. 旧持有者 `now >= expireAtMillis` 后即失效，不再是有效持有者。
2. 此时任意调用方 `acquire` 即**安全接管**：持有者更新为新调用方，
   并发放 `旧令牌 + 1` 的新栅栏令牌，`takeover = true`。
3. 接管后旧持有者：
   - 再 `acquire` 只能得到 `HELD_BY_OTHER`（在新持有者未到期时）；
   - 持旧令牌调用受保护操作得到 `FENCING_TOKEN_SUPERSEDED`；
   - 不能靠 `renew` 恢复身份（其租约已过期，且锁已易主）。
4. 临界期保护：未到期时任何他人 `acquire` 都被拒绝，因此不存在“旧持有者还没过期、新持有者已拿锁”的双持有窗口。

### 进程重启

- 协调器构造时从状态文件一次性载入全部锁状态；**到期与否完全由 `expireAtMillis` 与当前时间比较决定**，
  所以未到期租约重启后仍有效、已过期租约重启后仍过期，结论与宕机前一致。
- 接管所需的“下一令牌”来自持久化的最新令牌 +1，因此**重启不会把令牌倒回去重发**。
- 状态文件用“同目录临时文件写完 + 原子 rename”落盘，崩溃只可能留下旧完整文件或新完整文件，
  不会有半截状态；协调器启动即载入完整状态后才开始服务，**重启本身不会制造双持有窗口**。

---

## 3a. 多实例共用同一份状态（Multi-Instance Shared State）

同一台机器上可以起多个协调器**实例**（多个 JVM / 多个 `LeaseCoordinator`），它们指向
**同一个状态文件**，外部调用方连到其中任意一个都得到同一份结论。

### 怎么用

构造协调器时传入一个**跨实例文件锁**（锁文件建议放在状态文件同目录、固定命名）：

```java
Path stateFile = Path.of("data/lease-state.json");
LockStateStore store = new FileLockStateStore(stateFile);
InterProcessLock fileLock = new FileChannelInterProcessLock(Path.of("data/lease-state.json.lock"));
LeaseCoordinator coordinator = new LeaseCoordinator(clock, store, fileLock);
```

- Spring Boot 装配已自动启用：`lease.state.file` 指向同一文件的多个进程，
  会共用 `<状态文件名>.lock` 这个锁文件（见 `LeaseCoordinatorConfig`）。
- 所有实例**必须指向同一个状态文件与同一个锁文件**；锁文件随进程保持打开、无需清理（已加入 `.gitignore`）。

### 一致性保证

- 每次判定（获取 / 续约 / 接管 / 让出 / 受保护操作 / 快照）都先获取**跨进程排他文件锁**，
  并在临界区内**从磁盘重新载入最新状态**，判定后原子写回。因此：
  - 某个实例刚把锁授予出去，别的实例处理下一个请求时**立刻看到并承认该归属**；
  - 不会出现同一时刻两个实例把锁判给不同调用方（跨实例并发获取也恰好一个成功）；
  - 已发出的令牌**全局连续**：换实例处理、实例重启都不会重发；过期接管/让出后拿到的令牌一定更大。
- 锁通道按锁文件绝对路径在**单 JVM 内共享**，所以同一 JVM 内多个 `LeaseCoordinator`
  指向同一状态文件（常见于集成测试、同机多协作者）也会正确串行。

### 对单实例老用法的兼容范围

- 旧的两参构造器 `new LeaseCoordinator(clock, store)` **原样保留**：不启用跨实例文件锁，
  行为与以前完全一致（仅靠 JVM 内互斥锁）。旧测试、单实例部署不受影响。
- 旧的两参 fence 入口 `validateProtectedOperation(lockName, token)` **原样保留**：只校验令牌与租期，不校验身份。
- 状态文件新增了一个 `released` 布尔字段；**缺少该字段的旧状态文件可直接加载**（按未让出处理）。
- 新的拒绝原因（`HOLDER_IDENTITY_MISMATCH` / `LOCK_RELEASED` / `NOT_CURRENT_HOLDER`）是枚举增量，
  老的 switch/if 分支不会被破坏。

> 注意：兼容模式（两参构造器）只保证**单个 JVM 内**的互斥；多实例共用同一份状态时，
> **每个实例都必须使用三参构造器并指向同一个锁文件**，否则跨进程不互斥。

---

## 3b. 主动让出（Release / Voluntary Yield）

持有者在任务提前完成时，可在租约到期前主动放弃这把锁：

```java
ReleaseResult r = coordinator.release("order-42", "node-A");
```

语义：

- 只有**当前未到期持有者本人**能成功让出；成功后锁立即空闲，
  **任意调用方（含其他实例上的）马上可以 `acquire`**，并拿到 `旧令牌 + 1` 的新令牌。
- 让出在跨实例文件锁下原子写回状态，**对所有共用实例立即生效**。
- 让出会在状态里保留上一代持有者与令牌留痕（`released = true`），因此：
  - 旧持有者**不能续约**（`LOCK_RELEASED`），旧令牌在“让出后、被重新获取前”校验受保护操作得到 `LOCK_RELEASED`；
  - 一旦有人重新获取，旧令牌随即变为 `FENCING_TOKEN_SUPERSEDED`。
- 拒绝（全部可区分）：
  - 锁从未获取 → `LOCK_NOT_HELD`；
  - 租约已到期（持有者身份随过期失效，无需也不能让出）→ `LEASE_EXPIRED`；
  - 未到期但调用方不是当前持有者，或锁已被让出 → `NOT_CURRENT_HOLDER`。

---

## 4. 失败原因分类（RejectReason）

所有拒绝都返回**可区分的枚举原因**，调用方可以据此精确处理：

| 原因 | 出现于 | 含义 |
| --- | --- | --- |
| `INVALID_LOCK_NAME` | acquire / renew / release / fence | 锁名为 null 或空白 |
| `INVALID_HOLDER_ID` | acquire / renew / release / fence(带身份) | 持有者标识为 null 或空白 |
| `INVALID_TTL` | acquire / renew | TTL 非正数，或大到使到期时间溢出 |
| `HELD_BY_OTHER` | acquire / renew | 锁当前由**其他未到期持有者**占有 |
| `LOCK_NOT_HELD` | renew / release / fence | 锁从未被获取，无租约 / 无任何已发令牌 |
| `LEASE_EXPIRED` | renew / release / fence | 租约已到期（fence：令牌仍是当前一代但租约过期） |
| `INVALID_FENCING_TOKEN` | fence | 令牌非法（≤ 0） |
| `UNKNOWN_FENCING_TOKEN` | fence | 令牌比该锁已发放的最新令牌还大，从未发放过 |
| `FENCING_TOKEN_SUPERSEDED` | fence | 令牌曾发放但已被更新持有者的令牌取代（更早一代） |
| `HOLDER_IDENTITY_MISMATCH` | fence(带身份) | 令牌数字是当前令牌、租约也未到期，但**调用方不是该令牌持有者本人** |
| `LOCK_RELEASED` | renew / fence | 锁已被持有者主动让出，当前无有效持有者（被重新获取前） |
| `NOT_CURRENT_HOLDER` | release | 调用方不是当前未到期持有者本人（锁在他人名下，或已被让出） |

> 注意区分“当前一代令牌”的几种失败（按判定顺序）：
> 锁已主动让出 → `LOCK_RELEASED`；租约已过期（本人或他人来都算）→ `LEASE_EXPIRED`；
> 锁仍有效、令牌也对，只是**人不对** → `HOLDER_IDENTITY_MISMATCH`。
> 另外：租约过期但还没人接管 → `LEASE_EXPIRED`；已被新令牌取代 → `FENCING_TOKEN_SUPERSEDED`；
> 拿一个从没发放过的（过大的）令牌 → `UNKNOWN_FENCING_TOKEN`。

---

## 5. 代码结构

```
model/    RejectReason（拒绝原因枚举），LeaseGrant / AcquireResult / RenewResult /
          ReleaseResult / FenceCheckResult / LeaseView（结果与快照记录），LockLease（锁状态，含 released）
time/     Clock（可注入时钟）、SystemClock（默认墙钟）
store/    LockStateStore（持久化接口）、FileLockStateStore（JSON + 原子 rename）、
          InterProcessLock（跨实例互斥接口）、FileChannelInterProcessLock（文件锁 + JVM 内共享通道）
core/     LeaseCoordinator（全部判定逻辑：获取/续约/接管/栅栏校验（含身份）/主动让出/只读快照；
          三参构造器启用跨实例临界区，两参构造器为单实例兼容）
config/   LeaseCoordinatorConfig（Spring 装配；状态文件 lease.state.file，锁文件 <state>.lock）
test/.../support/ManualClock（测试可控时钟，无需真实等待）
```

每次判定都会打印结构化日志，包含 **持有者、令牌、时间（now / expireAt）与判定依据（basis/reason）**，
带身份的 fence 日志还会打印 `caller` 与记录中的 `holder`，测试也会用 `[TEST]` 行打印同样的关键信息。

---

## 6. 本地验证方法

无需任何外部服务或真实等待：

```bash
# 运行全部测试（多实例 / 身份校验 / 主动让出 / 并发竞争 / 到期接管 / 重启恢复）
mvn test

# 只跑某一类
mvn test -Dtest=ConcurrentContentionTest      # 32 线程并发抢同一把锁，恰好 1 个持有者
mvn test -Dtest=MultiInstanceCoordinatorTest  # 多实例共用状态：授予即时可见、跨实例并发唯一持有者、令牌不重发
mvn test -Dtest=HolderIdentityFenceTest       # 带身份 fence：人不对被拒，且与过期/被取代/未发放可区分
mvn test -Dtest=ReleaseSemanticsTest          # 主动让出：让出后立即接管、令牌递增、拒绝原因可区分
mvn test -Dtest=TakeoverFencingTest           # 到期接管 + 栅栏令牌拒绝矩阵
mvn test -Dtest=RestartRecoveryTest           # 新实例模拟重启：令牌不重发、结论一致
mvn test -Dtest=RenewSemanticsTest            # 续约不改持有者/令牌、不缩短租期
mvn test -Dtest=AcquireRejectReasonTest       # 获取语义与可区分拒绝原因
```

控制台与 `target/surefire-reports/` 中可看到每次判定的持有者、令牌、时间点与依据；
带身份的校验还会打印调用方（caller）与记录持有者（holder）。
状态文件默认写到工作目录 `lease-state.json`（可用 `lease.state.file` 覆盖），
跨实例锁写到 `lease-state.json.lock`，两者都已加入 `.gitignore`。

### 手工验证多实例

在同一台机器上起两个进程并让它们指向同一状态目录：

```bash
# 终端 A 与终端 B 使用相同的 lease.state.file
mvn spring-boot:run -Dspring-boot.run.arguments=--lease.state.file=/tmp/lease/shared.json
mvn spring-boot:run -Dspring-boot.run.arguments=--lease.state.file=/tmp/lease/shared.json
```

对任一进程获取锁后，立刻向另一进程发起竞争获取会得到 `HELD_BY_OTHER`；
对其中一进程 `release` 后，另一进程可立即获取并拿到 `旧令牌 + 1`。
（当前发布以协调器 API 与单测为主，日志中可直接观察上述判定。）
