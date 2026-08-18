# FlowController V1 与 V2 对比说明

## 一、概述

FlowControllerV1 与 FlowControllerV2 是 Sentinel Dashboard 提供的两套流控规则管理接口，分别服务于单机页与流控规则页两个前端页面。

二者的本质区别在于规则的管理维度与下发机制：V1 是机器直连模式，规则以客户端实例（ip:port）为维度交互；V2 是配置中心托管模式，规则以应用（app）为维度持久化到 Nacos。Nacos 适配的详细改造内容见 `dashboard nacos 适配改造.md`。

## 二、两者的定位与作用

### 1、FlowControllerV1：机器直连模式

- 代码位置：`controller/FlowControllerV1.java`
- 请求路径：`/v1/flow`
- 作用：规则查询与下发均以客户端机器为维度，通过 `SentinelApiClient` 直连客户端暴露的 HTTP 接口拉取与推送规则
- 数据存储：规则只存在于 Dashboard 内存仓库与客户端机器内存中，无外部持久化
- 适用场景：单机规则调试、向指定实例临时下发规则

### 2、FlowControllerV2：配置中心托管模式

- 代码位置：`controller/v2/FlowControllerV2.java`
- 请求路径：`/v2/flow`
- 作用：规则以应用（app）为维度统一管理，通过 `DynamicRuleProvider` / `DynamicRulePublisher` SPI 与配置中心交互；本项目注入 Nacos 实现（`flowRuleNacosProvider` / `flowRuleNacosPublisher`）
- 数据存储：规则持久化在 Nacos 配置中心，客户端通过 `sentinel-datasource-nacos` 订阅配置变更后动态生效
- 适用场景：生产环境规则统一管理与持久化

## 三、核心机制差异

### 1、规则存储与下发链路

V1 链路：

```text
Dashboard 内存仓库 -> SentinelApiClient -> 客户端机器（ip:port）HTTP 接口
```

V2 链路：

```text
Dashboard 内存仓库 -> Nacos Provider/Publisher -> Nacos 配置中心 -> 客户端 sentinel-datasource-nacos 订阅
```

### 2、接口对照

| 操作     | V1 接口                                                     | V2 接口                                              |
| -------- | ----------------------------------------------------------- | ---------------------------------------------------- |
| 查询规则 | `GET /v1/flow/rules?app=&ip=&port=`（从目标机器拉取）       | `GET /v2/flow/rules?app=`（从 Nacos 读取）            |
| 新增规则 | `POST /v1/flow/rule`（JSON body）                           | `POST /v2/flow/rule`（JSON body）                     |
| 修改规则 | `PUT /v1/flow/save.json`（表单参数，部分字段更新）          | `PUT /v2/flow/rule/{id}`（JSON body，整体替换）       |
| 删除规则 | `DELETE /v1/flow/delete.json?id=`                           | `DELETE /v2/flow/rule/{id}`                           |

### 3、实现细节差异

- 参数校验：V1 校验 app/ip/port 非空并确认机器归属（`isValidMachineOfApp`）；V2 校验请求体与规则字段，不校验 ip/port
- 下发方式：V1 通过 `CompletableFuture.get(5000, MILLISECONDS)` 同步等待推送结果；V2 直接同步调用 Nacos publisher 写回配置中心
- 权限要求：V1 删除接口要求 `WRITE_RULE`；V2 删除接口要求 `DELETE_RULE`
- 更新语义：V1 为部分字段更新（传什么改什么）；V2 为整体替换，app/ip/port 从旧记录继承
- 集群规则：V2 查询时把 `clusterConfig.flowId` 回填为实体 `id`，便于前端处理集群流控；V1 无此逻辑

## 四、前端调用位置

### 1、页面与路由

路由定义位于 `app.js`：

- `dashboard.flowV1` -> URL `/flow/:app` -> 页面 `flow_v1.html` + 控制器 `FlowControllerV1`（单机页）
- `dashboard.flow` -> URL `/v2/flow/:app` -> 页面 `flow_v2.html` + 控制器 `FlowControllerV2`（流控规则页，默认入口）

### 2、调用链

| 页面/入口                     | 控制器                           | 服务                                  | 后端接口      |
| ----------------------------- | -------------------------------- | ------------------------------------- | ------------- |
| V1 单机页（flow_v1.html）     | `FlowControllerV1`（flow_v1.js） | `FlowServiceV1`（flow_service_v1.js） | `/v1/flow/*`  |
| V2 流控规则页（flow_v2.html） | `FlowControllerV2`（flow_v2.js） | `FlowServiceV2`（flow_service_v2.js） | `/v2/flow/*`  |
| 簇点链路（identity.js）       | `IdentityCtl`                    | `FlowServiceV2`                       | `/v2/flow/*`  |

### 3、各入口明细

- 侧边栏（`sidebar.html`）：默认链接指向 V2 页面（`dashboard.flow`），原 `dashboard.flowV1` 链接被注释保留
- V2 页面顶部（`flow_v2.html`）：保留"回到单机页面"按钮，点击跳转 `dashboard.flowV1` 进入 V1 单机页
- 簇点链路（`identity.js`）：注入的 `FlowServiceV1` 被注释，改用 `FlowServiceV2`；新增流控规则成功后跳转 `/dashboard/v2/flow/{app}`（V2 页面）
- V1 页面查询规则前需先在页面选择机器，`FlowServiceV1.queryMachineRules(app, ip, port)` 携带 ip/port 请求 `/v1/flow/rules`
- `FlowServiceV2.queryMachineRules` 虽仍接收 ip/port 参数，但后端 V2 接口只按 app 查询，ip/port 实际无效，属命名遗留

## 五、注意事项

1. V1 与 V2 的数据源相互独立：V1 修改的规则只推送到目标机器内存，不写入 Nacos，配置中心数据不会变化
2. 若客户端同时挂载 Nacos datasource，V1 推送的规则可能被 Nacos 后续配置推送覆盖，混用两套入口存在数据不一致风险
3. Dashboard 重启后，V1 对应的内存规则全部丢失；V2 规则因持久化在 Nacos 而得以保留
4. 生产环境应统一使用 V2 页面管理规则；V1 仅作为遗留兼容与单机调试入口保留
5. V1 更新接口为部分字段更新，V2 为整体替换，前端调用 V2 时需保证请求体包含完整规则字段
6. 前端 V2 服务的 `queryMachineRules` 命名与后端行为不一致（后端忽略 ip/port），后续可考虑清理
