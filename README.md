# 本地租约锁协调器（Lease Fencing Coordinator）

一套**纯本地、无外部服务依赖**的命名租约锁协调流程。多个并发调用方在同一份服务状态下竞争同一把命名锁时，锁的归属、过期与接管结论始终**可解释、可复现**，并通过严格递增的**栅栏令牌（fencing token）**保护下游操作不被过期持有者静默写入。

- 语言/框架：Java 21 + Spring Boot 3.4（Web 层仅作演示暴露，核心逻辑不依赖 Spring）
- 持久化：本地文件（每把锁一个 properties 文件，临时文件 + 原子 move 落盘）
- 测试：JUnit 5，`FakeClock` 控制时间，**无任何真实等待、无外部服务**

---

## 1. 租约语义（Lease Semantics）

- 每把锁由 `lockName` 标识，租约记录包含：`holderId`（持有者）、`fencingToken`（栅栏令牌）、`acquiredAtMs`（获取时刻）、`expiresAtMs`（**绝对**过期时刻，epoch 毫秒）。
- **同一把锁在任一时刻最多只有一个有效持有者。** 所有判定在单个 `ReentrantLock` 内串行完成；并发获取只有一个调用方能看到"锁空闲"的前置状态，因此不可能双持有。
- **获取（acquire）**：
  - 锁不存在或已过期 → 发放新租约，栅栏令牌 `+1`；
  - 锁仍有效且调用方就是当前持有者 → **幂等返回原记录**，持有者、令牌、过期时刻全部不变；
  - 锁仍有效但属于他人 → 拒绝，原因 `LOCK_HELD_BY_OTHER`。
- **续约（renew）**：必须同时出示正确的 `holderId` 与当前 `fencingToken`。新过期时刻取
  `max(当前 expiresAtMs, now + ttlMillis)`，因此：
  - 续约**永不缩短**剩余租期（传入更短 TTL 时原样返回当前租约）；
  - 续约**永不改变**持有者与令牌；
  - 租约已过期不能续约，必须重新 acquire（原因 `LEASE_EXPIRED`）。
- **过期判定**：`now >= expiresAtMs` 即过期（边界包含，结论确定、无歧义）。时间来源抽象为 `TimeSource`，生产用系统墙钟，测试用可拨弄的假钟。

## 2. 栅栏令牌契约（Fencing Token Contract）

- 令牌是每把锁独立的正整数，从 `1` 开始；**每次成功的"新获取/接管"严格递增 1，绝不回退、绝不重复发放**。
- 同一持有者幂等重取与续约都**不产生新令牌**。
- 受保护操作在执行前必须携带 `(lockName, holderId, fencingToken)` 调用 `/api/leases/validate`（或 `LeaseCoordinator.validateFencedToken`）。**只有**同时满足以下条件才返回 `accepted=true`：
  1. 锁存在；
  2. 租约未过期；
  3. 令牌等于该锁当前令牌；
  4. 调用方是当前持有者。
- 判定顺序固定（先锁存在性 → 过期 → 令牌新旧 → 持有者），保证拒绝原因稳定可复现。
- 下游存储若具备 fencing 能力，应同时持久化见过的最大令牌，形成双保险。

## 3. 过期与接管规则（Expiry & Takeover）

1. 租约到期后，旧持有者立即失去保护：即使出示当前令牌，fence 校验也返回 `LEASE_EXPIRED`。
2. 任何调用方都可在过期后 acquire 接管，得到**严格更大**的新令牌；旧令牌对同一把锁永久失效（`TOKEN_SUPERSEDED`）。
3. 接管**不删除历史**：旧记录（持有者、令牌、绝对过期时刻）持久保留，新记录原子覆盖旧文件。

### 重启恢复（Restart Recovery）

- 协调器是无状态壳，状态全部在文件存储里；重启 = 用新的协调器实例指向同一目录。
- **令牌不重发**：上次发放的最大令牌从磁盘读出，接管时在其基础上 `+1`。
- **到期判定一致**：存的是绝对时间戳而非剩余 TTL，重启前后 `now >= expiresAtMs` 的结论不变。
- **无双持有窗口**：新实例只有在能读到磁盘记录后才提供服务；未到期租约的持有者在重启后幂等重取，拿到的仍是同一条记录、同一个令牌；他人在 TTL 内依旧被 `LOCK_HELD_BY_OTHER` 拒绝。

## 4. 失败原因分类（RejectReason）

所有拒绝都携带机器可区分的 `reason` 枚举（HTTP 层同时给出状态码）：

| reason | 触发场景 | HTTP |
| --- | --- | --- |
| `INVALID_PARAMETER` | `lockName`/`holderId` 为空、`ttlMillis <= 0` 或超过 24h、`fencingToken <= 0`、请求体缺字段/非法 JSON | 400 |
| `LOCK_HELD_BY_OTHER` | acquire 时锁仍被他人有效持有 | 409 |
| `UNKNOWN_LOCK` | renew/validate 一把从未存在过的锁 | 409 |
| `HOLDER_MISMATCH` | renew/validate 的调用方不是当前持有者（且令牌本身未过期/未被取代） | 409 |
| `TOKEN_STALE` | renew 出示令牌与当前令牌不符；validate 出示**大于**当前令牌（从未发放/未来令牌） | 409 |
| `TOKEN_SUPERSEDED` | validate 出示**小于**当前令牌的旧令牌（含旧持有者拿旧令牌） | 409 |
| `LEASE_EXPIRED` | renew 已过期租约；过期后仍执行受保护操作 | 409 |
| `PERSISTENCE_FAILURE` | 存储目录不可创建、记录不可读/损坏、落盘失败 | 500 |

> 说明：fence 校验**不抛异常**，返回 `FenceDecision{accepted, reason, detail, current}`，受保护操作走正常业务分支即可；acquire/renew 的拒绝抛 `LeaseException`，由 `LeaseApiExceptionHandler` 统一映射。

## 5. HTTP API

`Content-Type: application/json`

### 获取 / 幂等重取
```bash
curl -s -XPOST localhost:8080/api/leases/acquire \
  -d '{"lockName":"invoice-42","holderId":"node-A","ttlMillis":5000}'
# 200 -> {"lockName":"invoice-42","holderId":"node-A","fencingToken":1,
#         "acquiredAtMs":...,"expiresAtMs":...}
```

### 续约（只延不缩）
```bash
curl -s -XPOST localhost:8080/api/leases/renew \
  -d '{"lockName":"invoice-42","holderId":"node-A","fencingToken":1,"ttlMillis":8000}'
```

### 受保护操作前的栅栏校验
```bash
curl -s -XPOST localhost:8080/api/leases/validate \
  -d '{"lockName":"invoice-42","holderId":"node-A","fencingToken":1}'
# 200 -> {"accepted":true,"reason":null,"detail":"...","current":{...}}
# 拒绝 -> {"accepted":false,"reason":"TOKEN_SUPERSEDED",...}（HTTP 仍为 200）
```

冲突与参数错误示例：
```json
{"reason":"LOCK_HELD_BY_OTHER","message":"lock 'invoice-42' is currently held by 'node-A' until ..."}
```

## 6. 本地验证方法

无需数据库、Redis 或任何外部服务，也不需要真实等待时间：

```bash
# 全量测试（含并发竞争、到期接管、过期令牌操作、重启恢复、HTTP 契约）
mvn test

# 只跑某一组
mvn test -Dtest=LeaseConcurrencyTest      # 32 线程屏障并发抢锁，仅 1 个赢家
mvn test -Dtest=LeaseTakeoverFenceTest    # 到期接管 + 令牌严格递增 + 旧令牌被拒
mvn test -Dtest=LeaseRestartRecoveryTest  # 新实例指同一目录模拟重启
mvn test -Dtest=LeaseAcquireRenewTest     # 获取/续约幂等语义与全部拒绝原因
mvn test -Dtest=LeaseControllerTest       # HTTP 状态码与 reason 契约
```

测试日志（SLF4J）逐条打印**持有者、令牌、当前时间、过期时刻与判定依据**（如
`basis=takeover_after_expiry`、`basis=same_valid_holder`、
`fence rejected ... basis=TOKEN_SUPERSEDED`），便于复盘每次结论。

手工启动（默认端口可用 `-Dserver.port=8080` 固定）：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
# 存储目录可用 --lease.store.dir=/path/to/dir 覆盖（默认 ${java.io.tmpdir}/lease-fencing-store）
```

## 7. 代码结构

```
src/main/java/com/github/lisama569/leasefencingcoordinatorjava/
├── lease/
│   ├── TimeSource.java            # 时间抽象（可测试性）
│   ├── SystemTimeSource.java      # 墙钟实现
│   ├── RejectReason.java          # 可区分拒绝原因枚举
│   ├── LeaseException.java        # 携带原因的业务异常
│   ├── Lease.java                 # 租约记录（record）
│   ├── FenceDecision.java         # 栅栏校验结论（record）
│   ├── LeaseStore.java            # 持久化接口
│   ├── LeaseCoordinator.java      # 核心：串行化的 acquire/renew/validate
│   └── store/FileLeaseStore.java  # 原子落盘的文件存储
├── config/LeaseConfig.java
└── web/                           # DTO、Controller、异常映射
src/test/.../
├── support/FakeClock.java         # 可拨弄的时钟
└── tests/                         # 五组测试（见第 6 节）
```
