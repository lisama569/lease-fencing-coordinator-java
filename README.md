# 本地租约锁协调器（Lease Fencing Coordinator）

一个**纯本地、无外部服务依赖**的命名租约锁协调器，让多个并发调用方在同一份服务状态下竞争同一把命名锁时，
锁的**归属、过期、让出与接管结论始终可解释、可复现**。

- 所有判定都在**跨进程独占区**（同 JVM 互斥锁 + 状态文件上的 OS 文件锁）与一个**可注入时钟**下，
  进入临界区后重新读盘、一次性完成；
- 支持**同一台机器上多个实例共用同一个状态目录/状态文件**对外提供协调服务：
  无论请求落到哪个实例，“这把锁现在归谁”的结论全局唯一，某个实例刚授予的归属其他实例立即可见；
- 状态持久化到本地 JSON 文件（临时文件 + 原子 rename），重启后结论一致、令牌不重发；
- 受保护操作可携带**调用方身份**：只有令牌持有者本人 + 租约未到期才放行，冒充者单独以 `HOLDER_MISMATCH` 拒绝；
- 持有者可在到期前**主动让出**（`release`），让出立即跨实例生效，让出后再获取发放更大令牌；
- 测试使用可控时钟（`ManualClock`）推进时间，**无需任何真实等待**，
  另有真实多 JVM 子进程测试验证跨进程文件锁。

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

- 令牌是**锁级单调递增的正整数**：该锁首次授予 = `1`；之后每次**新的授予**
  （含过期接管、主动让出后再获取）= `上一个令牌 + 1`。
- 令牌只在“锁的归属发生代际更替”（首次授予 / 接管过期持有者 / 让出后再获取）时发放；
  **重入 acquire 与 renew 都不产生新令牌**。
- 每次成功获取的令牌**严格递增且永不回退**；令牌随状态持久化，
  **换实例处理、进程重启后已发放的令牌绝不重新发放**。
- 受保护操作有两个入口：
  - **新用法（推荐）** `validateProtectedOperation(lockName, holderId, token)`：
    **只接受一种情况**——`token == 当前最新令牌` **且** `holderId == 该令牌对应的持有者本人`
    **且** `租约未到期、未让出`。别人拿着同一枚数字正确的令牌过来也必须拒绝（`HOLDER_MISMATCH`）。
  - **兼容老用法** `validateProtectedOperation(lockName, token)`：不校验身份，
    只看“令牌为当前最新令牌且租约未到期”，行为与升级前一致。
- 其余情况全部显式拒绝，绝不静默接受（拒绝原因见第 5 节矩阵）。

这样下游存储只要按“拒绝更旧令牌”实现（fencing 模式），过期/被接管/已让出的旧持有者即使晚到也无法写入。

---

## 3. 多实例共用同一状态（Shared-State Multi-Instance）

多个实例（同机多个进程，或同一进程内多个协调器）指向**同一个状态文件**即可组成协调服务：

```java
// 每个实例都用同一个 state file；实例间无需其它协调组件
LeaseCoordinator instance = new LeaseCoordinator(
        new SystemClock(),
        new FileLockStateStore(Path.of("/var/lib/lease/lease-state.json")));
// Spring Boot 下让各实例使用相同配置：lease.state.file=/var/lib/lease/lease-state.json
```

实现机制与保证：

1. **两层互斥，全局唯一结论。** 每次 acquire / renew / release / fence / inspect 都在
   `LockStateStore.exclusively(...)` 事务中执行：
   - JVM 内：按状态文件绝对路径分配的全局 monitor（避免同一 JVM 内重复持有文件锁）；
   - 跨 JVM：状态文件旁自动创建的 `lease-state.json.lock` 锁文件上的阻塞式排他 `FileLock`。
2. **进入临界区先重新读盘再判定，退出前原子落盘。** 因此实例 1 刚授予的锁，
   实例 2 处理下一个请求时读到的一定是同一份归属；不存在“两个实例同时判给不同调用方”的窗口。
3. **令牌以磁盘上持久化的最新值 +1**，跨实例接管、实例重启都不会重发。
4. 判定时钟是各实例自己的墙钟/注入时钟；多实例部署要求各实例时钟一致（同一台机器天然满足）。
   “恰好到期”仍是确定的边界比较 `now >= expireAtMillis`。

### 对单实例老用法的兼容范围

- 构造方式，以及 `acquire` / `renew` / `validateProtectedOperation(lockName, token)` / `inspect`
  的签名与结论保持不变；原有单实例/重启测试全部不回归。
- 内部唯一变化：每个操作现在都走一次“加锁 → 读盘 → 判定 → 落盘”，单实例下结论等价，
  并额外获得与同机其它实例的互斥能力。
- 身份校验第三参、`release` 均为**新增 API**，不影响老调用方。

---

## 4. 过期、接管与主动让出（Expiry, Takeover & Release）

1. 旧持有者 `now >= expireAtMillis` 后即失效，不再是有效持有者。
2. 此时任意调用方 `acquire` 即**安全接管**：持有者更新为新调用方，
   并发放 `旧令牌 + 1` 的新栅栏令牌，`takeover = true`。
3. **主动让出**：持有者任务提前完成时，可在到期前调用 `release(lockName, holderId)`：
   - 只有“当前未到期持有者本人”能让出成功；让出**立即对所有共用实例生效**，
     其他调用方**无需等到期**即可获取；
   - 让出**不回收令牌**：被让出的代次与令牌保留，下一次获取发放 `被让出令牌 + 1`，
     新授予的 `takeover = false`（语义是“让出后再授予”，不是“过期接管”）；
   - 让出后旧持有者持旧令牌调受保护操作：在新授予前得到 `LEASE_RELEASED`，
     新授予后得到 `FENCING_TOKEN_SUPERSEDED`。
4. 接管/让出后旧持有者：
   - 再 `acquire` 只能得到 `HELD_BY_OTHER`（在新持有者未到期时）；
   - 持旧令牌调用受保护操作得到 `FENCING_TOKEN_SUPERSEDED`；
   - 不能靠 `renew` 恢复身份。
5. 临界期保护：未到期、未让出时任何他人 `acquire` 都被拒绝，因此不存在双持有窗口。

### 进程重启

- 协调器不长期缓存状态：每次判定都重新读盘，重启后首次操作读到的就是完整持久化状态。
  **到期与否完全由 `expireAtMillis` 与当前时间比较决定**，未到期租约重启后仍有效、已过期仍过期。
- 接管所需的“下一令牌”来自持久化的最新令牌 +1，因此**重启不会把令牌倒回去重发**。
- 状态文件用“同目录临时文件写完 + 原子 rename”落盘，崩溃只可能留下旧完整文件或新完整文件，
  不会有半截状态；文件锁随进程退出由 OS 自动释放，**崩溃/重启不会留下永久锁死，也不制造双持有窗口**。

---

## 5. 失败原因分类（RejectReason）

所有拒绝都返回**可区分的枚举原因**，调用方可以据此精确处理：

| 原因 | 出现于 | 含义 |
| --- | --- | --- |
| `INVALID_LOCK_NAME` | acquire / renew / release / fence | 锁名为 null 或空白 |
| `INVALID_HOLDER_ID` | acquire / renew / release / fence | 持有者标识为 null 或空白 |
| `INVALID_TTL` | acquire / renew | TTL 非正数，或大到使到期时间溢出 |
| `HELD_BY_OTHER` | acquire / renew | 锁当前由**其他未到期持有者**占有 |
| `LOCK_NOT_HELD` | renew / release / fence | 锁从未被获取，无租约 / 无任何已发令牌 |
| `LEASE_EXPIRED` | renew / release / fence | 租约已到期（fence：令牌仍是当前一代但租约过期） |
| `INVALID_FENCING_TOKEN` | fence | 令牌非法（≤ 0） |
| `UNKNOWN_FENCING_TOKEN` | fence | 令牌比该锁已发放的最新令牌还大，从未发放过 |
| `FENCING_TOKEN_SUPERSEDED` | fence | 令牌曾发放但已被更新持有者的令牌取代（更早一代） |
| `HOLDER_MISMATCH` | fence（三参） | 令牌数字正确、租约也有效，但**调用方不是该令牌持有者本人** |
| `LEASE_RELEASED` | renew / release / fence | 当前代次已被持有者**主动让出**，无有效持有者 |
| `NOT_CURRENT_HOLDER` | release | 主动让出的调用方不是当前未到期持有者本人 |

> 易混场景的区分：
> - 人不对、令牌对、租约没过期 → `HOLDER_MISMATCH`；
> - 令牌对、人对、租约过期且尚无人接管 → `LEASE_EXPIRED`；
> - 令牌对、人对、但该代次已被主动让出且尚无人再获取 → `LEASE_RELEASED`；
> - 令牌已被新一代取代 → `FENCING_TOKEN_SUPERSEDED`；
> - 拿一个从没发放过的（过大的）令牌 → `UNKNOWN_FENCING_TOKEN`。
>
> 主动让出侧：锁从未有过 → `LOCK_NOT_HELD`；已让出再让出 → `LEASE_RELEASED`；
> 过期后才来让出 → `LEASE_EXPIRED`；锁有效但不是本人 → `NOT_CURRENT_HOLDER`。

---

## 6. 代码结构

```
model/    RejectReason（拒绝原因枚举），LeaseGrant / AcquireResult /
          RenewResult / ReleaseResult / FenceCheckResult / LeaseView（结果与快照记录），
          LockLease（锁状态，含 releasedAtMillis 让出时刻）
time/     Clock（可注入时钟）、SystemClock（默认墙钟）
store/    LockStateStore（持久化 + exclusively 独占事务接口）、
          FileLockStateStore（JSON + 原子 rename；JVM 内 per-path monitor + 跨进程 FileLock）
core/     LeaseCoordinator（全部判定逻辑：获取/续约/接管/主动让出/身份栅栏校验/只读快照）
config/   LeaseCoordinatorConfig（Spring 装配；状态文件由 lease.state.file 配置）
test/.../support/ManualClock（可控时钟）、LeaseCliClient（跨进程测试的子进程入口）
```

每次判定都会打印结构化日志，包含 **实例标识、持有者/调用方、令牌、时间（now / expireAt / releasedAt）
与判定依据（basis/reason）**，测试也会用 `[TEST]` 行打印同样的关键信息。

---

## 7. 本地验证方法

无需任何外部服务；除跨进程测试外都无需真实等待：

```bash
# 运行全部测试（22 个：原有获取/续约/接管/重启 12 个 + 新场景 10 个）
mvn test

# 只跑某一类
mvn test -Dtest=MultiInstanceSharedStateTest  # 多实例共享状态：授予即时可见、跨实例并发唯一赢家、令牌不重发
mvn test -Dtest=MultiProcessCoordinatorTest   # 真实多个 JVM 进程共用一个状态文件（文件锁、身份、让出全链路）
mvn test -Dtest=IdentityFenceCheckTest        # 身份不匹配被拒 HOLDER_MISMATCH，与其它原因可区分
mvn test -Dtest=ReleaseSemanticsTest          # 主动让出跨实例立即生效、让出后令牌递增、拒绝矩阵
mvn test -Dtest=ConcurrentContentionTest      # 32 线程并发抢同一把锁，恰好 1 个持有者
mvn test -Dtest=TakeoverFencingTest           # 到期接管 + 栅栏令牌拒绝矩阵
mvn test -Dtest=RestartRecoveryTest           # 新实例模拟重启：令牌不重发、结论一致
mvn test -Dtest=RenewSemanticsTest            # 续约不改持有者/令牌、不缩短租期
mvn test -Dtest=AcquireRejectReasonTest       # 获取语义与可区分拒绝原因
mvn test -Dtest=LegacyStateFormatCompatibilityTest  # 旧版本 4 字段状态 JSON 平滑升级读取
```

### 手工验证多实例

1. 用相同的 `lease.state.file` 启动两个应用实例（同机不同端口，例如
   `java -jar app.jar --server.port=8081 --lease.state.file=/tmp/lease/state.json`
   与 `--server.port=8082 --lease.state.file=...`）。
2. 在实例 A 上获取一把锁；立刻在实例 B 上尝试获取同一把锁，应得到 `HELD_BY_OTHER`，
   `inspect` 看到的持有者/令牌两边一致。
3. 在实例 A 上 `release`，实例 B 立刻可获取，且新令牌 = 旧令牌 + 1。
4. 观察状态目录下同时存在 `state.json`（原子落盘）与 `state.json.lock`（跨进程锁文件，
   自动创建、可留空，已加入 `.gitignore`）。

控制台与 `target/surefire-reports/` 中可看到每次判定的实例、持有者、令牌、时间点与依据。
状态文件默认写到工作目录 `lease-state.json`（可用 `lease.state.file` 覆盖），
运行期临时文件 `*.lease-state-*.tmp` 与锁文件 `*.json.lock` 均已加入 `.gitignore`。
