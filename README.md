# 本地租约锁协调器（Lease Fencing Coordinator）

一个**纯本地、无外部服务依赖**的命名租约锁协调器，让多个并发调用方在同一份服务状态下竞争同一把命名锁时，
锁的**归属、过期与接管结论始终可解释、可复现**。

- 所有判定都基于一把内置互斥锁与一个**可注入时钟**，在临界区内一次性完成；
- 状态持久化到本地 JSON 文件（临时文件 + 原子 rename），重启后结论一致、令牌不重发；
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

- 令牌是**锁级单调递增的正整数**：该锁首次授予 = `1`；之后每次**新的授予**（含过期接管）= `上一个令牌 + 1`。
- 令牌只在“锁的归属发生代际更替”（首次授予 / 接管过期持有者）时发放；
  **重入 acquire 与 renew 都不产生新令牌**。
- 每次成功获取的令牌**严格递增且永不回退**；令牌随状态持久化，**重启后已发放的令牌绝不重新发放**。
- 受保护操作通过 `validateProtectedOperation(lockName, token)` 校验，**只接受一种情况**：
  `token == 当前最新令牌` **且** `租约未到期`。其余情况全部显式拒绝，绝不静默接受。

这样下游存储只要按“拒绝更旧令牌”实现（fencing 模式），过期/被接管的旧持有者即使晚到也无法写入。

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

## 4. 失败原因分类（RejectReason）

所有拒绝都返回**可区分的枚举原因**，调用方可以据此精确处理：

| 原因 | 出现于 | 含义 |
| --- | --- | --- |
| `INVALID_LOCK_NAME` | acquire / renew / fence | 锁名为 null 或空白 |
| `INVALID_HOLDER_ID` | acquire / renew | 持有者标识为 null 或空白 |
| `INVALID_TTL` | acquire / renew | TTL 非正数，或大到使到期时间溢出 |
| `HELD_BY_OTHER` | acquire / renew | 锁当前由**其他未到期持有者**占有 |
| `LOCK_NOT_HELD` | renew / fence | 锁从未被获取，无租约 / 无任何已发令牌 |
| `LEASE_EXPIRED` | renew / fence | 租约已到期（fence：令牌仍是当前一代但租约过期） |
| `INVALID_FENCING_TOKEN` | fence | 令牌非法（≤ 0） |
| `UNKNOWN_FENCING_TOKEN` | fence | 令牌比该锁已发放的最新令牌还大，从未发放过 |
| `FENCING_TOKEN_SUPERSEDED` | fence | 令牌曾发放但已被更新持有者的令牌取代（更早一代） |

> 注意区分三类“旧令牌”操作：
> 租约过期但还没人接管 → `LEASE_EXPIRED`；已被新令牌取代 → `FENCING_TOKEN_SUPERSEDED`；
> 拿一个从没发放过的（过大的）令牌 → `UNKNOWN_FENCING_TOKEN`。

---

## 5. 代码结构

```
model/    RejectReason（拒绝原因枚举），LeaseGrant / AcquireResult /
          RenewResult / FenceCheckResult / LeaseView（结果与快照记录），LockLease（锁状态）
time/     Clock（可注入时钟）、SystemClock（默认墙钟）
store/    LockStateStore（持久化接口）、FileLockStateStore（JSON + 原子 rename）
core/     LeaseCoordinator（全部判定逻辑：获取/续约/接管/栅栏校验/只读快照）
config/   LeaseCoordinatorConfig（Spring 装配；状态文件由 lease.state.file 配置）
test/.../support/ManualClock（测试可控时钟，无需真实等待）
```

每次判定都会打印结构化日志，包含 **持有者、令牌、时间（now / expireAt）与判定依据（basis/reason）**，
测试也会用 `[TEST]` 行打印同样的关键信息。

---

## 6. 本地验证方法

无需任何外部服务或真实等待：

```bash
# 运行全部测试（并发竞争 / 到期接管 / 过期令牌操作 / 重启恢复）
mvn test

# 只跑某一类
mvn test -Dtest=ConcurrentContentionTest      # 32 线程并发抢同一把锁，恰好 1 个持有者
mvn test -Dtest=TakeoverFencingTest           # 到期接管 + 栅栏令牌拒绝矩阵
mvn test -Dtest=RestartRecoveryTest           # 新实例模拟重启：令牌不重发、结论一致
mvn test -Dtest=RenewSemanticsTest            # 续约不改持有者/令牌、不缩短租期
mvn test -Dtest=AcquireRejectReasonTest       # 获取语义与可区分拒绝原因
```

控制台与 `target/surefire-reports/` 中可看到每次判定的持有者、令牌、时间点与依据。
状态文件默认写到工作目录 `lease-state.json`（可用 `lease.state.file` 覆盖），该文件已加入 `.gitignore`。
