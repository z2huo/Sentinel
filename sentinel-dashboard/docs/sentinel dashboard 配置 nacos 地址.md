# Sentinel Dashboard 配置 Nacos 地址

## 一、背景

Sentinel Dashboard 的规则持久化依赖 Nacos，`application-nacos.properties` 中的 `nacos.address` 决定了 Dashboard 访问哪些 Nacos 节点。实际使用中有两个常见疑问：

- 配置多个 Nacos 地址时，如果其中一个节点没有启动，Dashboard 持久化数据到 Nacos 是否会失败
- `nacos.address` 是否支持配置 VIP（如通过 Nginx 反向代理三个 Nacos 节点，对外暴露一个 IP 与端口）

本文基于 Sentinel Dashboard 源码与 nacos-client 1.4.2 源码对以上两个问题进行分析和说明。

## 二、配置方式

### 1、直连多个节点地址

`application-nacos.properties` 中通过逗号分隔配置多个 Nacos 节点：

```properties
nacos.address=192.168.99.99:8848,192.168.99.99:8858,192.168.99.99:8868
nacos.namespace=dev
```

### 2、通过 VIP 统一入口

三个 Nacos 节点通过 Nginx 反向代理后，对外暴露一个统一的 IP 与端口，Dashboard 直接配置该 VIP：

```properties
nacos.address=192.168.99.99:994
nacos.namespace=dev
```

Nginx 方案的详细部署说明见同目录下的 `nginx-nacos-vip.md`。

## 三、多个地址直连：部分节点未启动时的行为

### 1、Dashboard 侧的地址传递

`NacosConfig#nacosConfigService` 将 `nacos.address` 的整串值作为 `SERVER_ADDR` 属性传给 `ConfigFactory.createConfigService(properties)`，之后所有规则读取（`getConfig`）与规则发布（`publishConfig`）都复用同一个 Nacos 客户端。

Sentinel 自身不对多地址做任何处理，多地址解析与容错完全由 nacos-client 负责。Dashboard 通过 `sentinel-datasource-nacos` 依赖的 nacos-client 版本为 1.4.2。

### 2、Nacos 客户端的多地址解析与重试机制

nacos-client 1.4.2 的处理逻辑如下：

- `ServerListManager` 构造时按逗号拆分 `SERVER_ADDR`，三个地址全部保留在 `serverUrls` 中，并以固定列表模式运行
- 每轮迭代使用 `ServerAddressIterator` 对地址列表随机打乱，实现多台之间轮询
- `ServerHttpAgent.httpGet` 与 `httpPost` 请求失败时（连接拒绝、读超时、HTTP 500/502/503）自动切换下一个地址；整轮遍历失败后重试计数递减（默认最多 3 轮），再重新随机打乱开始新一轮
- 某次请求成功后调用 `updateCurrentServerAddr` 记住当前可用地址，后续请求优先从该地址开始

只要三个节点中至少一个存活，请求最终都能连接成功。

### 3、关键约束：单次请求的总时间窗只有 3 秒

重试循环受总时间窗约束，而不是每次尝试单独计时：

- `publishConfig` 的读超时为 `POST_TIMEOUT`，固定 3000 毫秒，且连接超时也是 3000 毫秒
- `getConfig` 的读超时为 Dashboard 传入的 3000 毫秒

也就是说，整个重试过程最多执行 3 秒，超时后直接抛出 `no available server` 异常。

### 4、失败方式决定结果

| 宕机节点的失败方式            | 行为                                   | 结果                     |
| ----------------------------- | -------------------------------------- | ------------------------ |
| 端口未监听，TCP 收到 RST 拒绝 | 毫秒级抛出连接异常，立刻切换下一个地址 | 不会失败，基本无感知     |
| 网络黑洞或防火墙丢弃包        | 一次尝试吃满连接超时，3 秒时间窗被耗尽 | 会失败，即使其他节点健康 |

### 5、结论

三个节点中一个未启动时，持久化通常不会失败，Nacos 客户端会在地址列表内轮询重试，只要还有存活节点即可写入。

前提是故障节点必须干净利落地失败（端口拒绝）。如果故障节点导致连接挂起（防火墙丢包、网络不可达且无快速响应），单次发布请求的 3 秒时间窗会被一次挂起耗尽，此时 `publishConfig` 失败，Dashboard 上会看到规则发布失败。该行为是 nacos-client 1.4.2 的固有逻辑，Sentinel Dashboard 没有额外的容错层。

## 四、配置 VIP 的方式与要点

### 1、配置改法

支持 VIP，直接把 `nacos.address` 改为 Nginx 暴露的统一地址即可：

```properties
nacos.address=192.168.99.99:994
```

### 2、客户端侧的源码依据

- `ServerListManager` 对 `SERVER_ADDR` 按逗号拆分，拆出几个就用几个，只有一个元素时同样合法，无协议的 `ip:port` 会自动补成 `http://ip:port`
- 客户端默认 contextPath 为 `nacos`，实际请求路径为 `http://192.168.99.99:994/nacos/v1/cs/configs`，与 Nginx 透传 `/nacos` 前缀的规则正好匹配
- nacos-client 1.4.2 为纯 HTTP 客户端，Dashboard 只使用 `getConfig` 与 `publishConfig`，不依赖 gRPC。`nginx-nacos-vip.md` 中 stream 转发的 1994 gRPC 端口是为 Nacos 2.x 客户端准备的，Dashboard 不需要

### 3、容错责任转移

改为 VIP 后，客户端侧的多地址轮询重试不复存在，所有请求只打 Nginx 一个地址，可用性完全取决于 Nginx upstream：

- 好处：后端节点扩缩容、迁移对 Dashboard 无感知；故障节点由 Nginx 摘除，比客户端随机踩到故障节点再切换更可控
- 代价：Nginx 变成单点；如果健康检查与超时配置不当，一个挂起的后端节点会拖垮所有请求

### 4、Nginx 侧关键配置

为保证 VIP 方案的可靠性，Nginx 侧需要重点配置两项。

第一，被动健康检查摘除故障节点：

```nginx
upstream nacos-http {
    server 192.168.99.99:8848 max_fails=2 fail_timeout=30s;
    server 192.168.99.99:8858 max_fails=2 fail_timeout=30s;
    server 192.168.99.99:8868 max_fails=2 fail_timeout=30s;
}
```

故障节点连续失败 2 次后被摘除 30 秒，请求只路由到健康节点。

第二，连接超时务必调小：

```nginx
proxy_connect_timeout 2s;
proxy_read_timeout 30s;
```

Nginx 默认 `proxy_connect_timeout` 为 60 秒。若后端节点连接挂起，Nginx 会等 60 秒才返回 502，而 Dashboard 客户端单次请求总时间窗只有 3 秒，客户端早已超时失败。连接超时调小后，故障节点 2 秒内返回 502，触发摘除计数，最多前几次请求受影响，之后恢复稳定。

`proxy_read_timeout` 保持 30 秒是为了兼容其他 Nacos 客户端的长轮询请求，Dashboard 自身 3 秒足够。

### 5、直连与 VIP 对比

| 项                 | 直连多个地址                 | Nginx VIP                            |
| ------------------ | ---------------------------- | ------------------------------------ |
| 客户端配置         | 逗号分隔多个地址             | 单一 IP 与端口                       |
| 故障节点处理       | 客户端随机轮询切换           | Nginx 健康检查摘除                   |
| 慢失败场景         | 3 秒窗口耗尽则本次请求失败   | 超时调小后快速返回 502 并摘除        |
| 后端变更的可见性   | 每次请求地址可能变化         | 永远一个入口，后端变更无感知         |
| 新增单点           | 无                           | Nginx 本身成为单点                   |

## 五、总结

- 多地址直连时，一个节点未启动通常不会导致持久化失败，客户端会自动切换重试；但故障节点若为连接挂起型故障，会因 3 秒时间窗耗尽导致当次发布失败
- `nacos.address` 支持配置 VIP，将地址改为 Nginx 暴露的统一入口即可，客户端源码层面完全兼容
- VIP 方案的可靠性依赖 Nginx 的被动健康检查与连接超时配置，二者缺一不可
