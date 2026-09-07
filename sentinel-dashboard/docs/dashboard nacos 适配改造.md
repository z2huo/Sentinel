# Sentinel Dashboard Nacos 适配改造说明

## 一、改造背景

Sentinel Dashboard 默认将规则存储于应用内存，规则的查询与下发均通过 `SentinelApiClient` 与客户端机器交互完成，Dashboard 重启后规则丢失，无法满足规则持久化的诉求。

本次改造将 Nacos 作为规则存储中心，实现 Dashboard 与 Nacos 之间的双向推送：

- 查询：Dashboard 从 Nacos 读取规则并展示，不再依赖客户端机器在线
- 写入：规则新增、修改、删除后写回 Nacos
- 生效：客户端通过 `sentinel-datasource-nacos` 监听 Nacos 配置变更，实现规则动态生效

改造覆盖流控、热点参数、熔断降级、系统保护、授权、网关流控、网关 API 分组共 7 类规则。

## 二、整体设计

### 1、改造思路

基于 Sentinel Dashboard 官方的规则扩展接口实现：

- `DynamicRuleProvider`：负责从配置中心读取规则，方法 `getRules(appName)`
- `DynamicRulePublisher`：负责向配置中心写入规则，方法 `publish(app, rules)`
- 每个规则类型对应一对 Provider 与 Publisher，通过 `@Component` 指定 bean 名称
- Controller 通过 `@Qualifier` 注入对应规则类型的 Provider 与 Publisher

### 2、规则存储约定

规则以应用为维度存储在 Nacos 配置中心，分组统一为 `SENTINEL_GROUP`，dataId 由应用名与后缀拼接而成，配置格式为 JSON。

| 规则类型          | dataId 后缀           | 常量名                          |
| ----------------- | --------------------- | ------------------------------- |
| 流控规则          | `-flow-rules`         | `FLOW_DATA_ID_POSTFIX`          |
| 热点参数规则      | `-param-rules`        | `PARAM_FLOW_DATA_ID_POSTFIX`    |
| 熔断降级规则      | `-degrade-rules`      | `DEGRADE_DATA_ID_POSTFIX`       |
| 系统保护规则      | `-system-rules`       | `SYSTEM_DATA_ID_POSTFIX`        |
| 授权规则          | `-authority-rules`    | `AUTHORITY_DATA_ID_POSTFIX`     |
| 网关流控规则      | `-gw-flow-rules`      | `GATEWAY_FLOW_DATA_ID_POSTFIX`  |
| 网关 API 分组规则 | `-gw-api-group-rules` | `GATEWAY_API_DATA_ID_POSTFIX`   |

dataId 后缀与分组常量统一定义在 `NacosConfigUtil` 中，与客户端侧数据源约定的 dataId 保持一致。

## 三、核心实现

### 1、Nacos 连接与配置

- `NacosProperties`：配置属性类，`@ConfigurationProperties(prefix = "nacos")`，字段为 `address`、`namespace`、`userName`、`password`
- `NacosConfig`：`@Configuration` 配置类，通过 `@EnableConfigurationProperties` 启用 `NacosProperties`；`nacosConfigService()` 方法使用 `PropertyMapper` 将属性映射为 Nacos `Properties`，经 `ConfigFactory.createConfigService` 创建 `ConfigService` bean
- `NacosConfigUtil`：常量类，定义 `GROUP_ID` 与各类规则的 dataId 后缀

### 2、规则编解码

`NacosConfig` 为每类规则注册一对 `Converter` bean：

- encoder：`List<T>` 转 JSON 字符串，使用 fastjson，启用 `SerializerFeature.PrettyFormat` 便于人工阅读
- decoder：JSON 字符串转 `List<T>`，使用 `JSON.parseArray`

### 3、规则 Provider 与 Publisher

新增包 `rule/nacos`，目录结构如下：

```text
rule/nacos/
├── NacosConfig.java
├── NacosConfigUtil.java
├── authority/
│   ├── AuthorityRuleNacosProvider.java
│   └── AuthorityRuleNacosPublisher.java
├── degrade/
│   ├── DegradeRuleNacosProvider.java
│   └── DegradeRuleNacosPublisher.java
├── flow/
│   ├── FlowRuleNacosProvider.java
│   └── FlowRuleNacosPublisher.java
├── param/
│   ├── ParamFlowRuleNacosProvider.java
│   └── ParamFlowRuleNacosPublisher.java
├── system/
│   ├── SystemRuleNacosProvider.java
│   └── SystemRuleNacosPublisher.java
└── gateway/
    ├── api/
    │   ├── GatewayApiNacosProvider.java
    │   └── GatewayApiNacosPublisher.java
    └── flow/
        ├── GatewayFlowRuleNacosProvider.java
        └── GatewayFlowRuleNacosPublisher.java
```

实现逻辑：

- Provider 的 `getRules` 调用 `configService.getConfig(dataId, GROUP_ID, 3000)`，配置为空时返回空列表，否则交由 decoder 转换
- Publisher 的 `publish` 调用 `configService.publishConfig(dataId, GROUP_ID, json, ConfigType.JSON.getType())` 写回 Nacos

### 4、Controller 改造

- `FlowControllerV2`：查询改为从 Nacos 读取，并恢复 `app` 与集群 `flowId`；新增、修改、删除后调用 `publishRules(app)` 将内存仓库中该应用的全部规则写回 Nacos
- `AuthorityRuleController`、`DegradeController`、`ParamFlowRuleController`、`SystemController`：查询改为 `ruleProvider.getRules(app)`；增删改后 `publishRules(app)`，原向客户端机器推送规则的逻辑被注释保留
- `GatewayApiController`、`GatewayFlowRuleController`：查询从 Nacos 读取并补齐 `app`、`ip`、`port` 及默认字段（网关流控的 `interval`、`controlBehavior`、`burst` 等）；新增规则时从 Nacos 读取现有规则取最大 id 加一作为新 id；增删改后 `publishRules(app)`

### 5、前端适配

- `app.js`：保留 `flowV1` 路由（指向 `flow_v1.html`，供 V2 页面"回到单机页面"按钮使用）；V2 页面通过 `dashboard.flow` 路由（指向 `flow_v2.html`）暴露；被注释的重复 `flowV1` 路由定义为改造残留
- `identity.js`：簇点链路新建流控规则改用 `FlowServiceV2`，成功后跳转 `/dashboard/v2/flow/{app}`
- `sidebar.html`：为网关与非网关入口补充中文注释

## 四、配置与部署

### 1、配置文件拆分

Dashboard 配置按 profile 拆分为独立文件：

- `application.properties`：主配置，通过 `spring.profiles.include` 引入 `nacos` 与 `sentinel` 两个 profile，配置服务端口
- `application-nacos.properties`：Nacos 配置 profile，包含 `nacos.address`、`nacos.namespace`、`nacos.username`、`nacos.password`
- `application-sentinel.properties`：Dashboard 自身配置 profile，包含 `project.name`、`csp.sentinel.dashboard.server`、cookie 名称、日志、认证等配置

### 2、Docker 部署

- `my.dockerfile`：基础镜像为 `eclipse-temurin:17-jre-noble`，通过 `COPY` 本地构建产物 `packages/sentinel-dashboard-1.8.8.jar` 启动
- `origin.dockerfile`：改造前的原始 Dockerfile 备份
- `pom.xml`：`sentinel-datasource-nacos` 依赖移除 `test` scope，作为正式依赖引入

## 五、后续待办

本次改造在代码审查中发现的待完善点已记录于 `TODO.md`，主要包括 Nacos 集成改为可选启用、发布失败感知、规则 ID 生成竞态、Dashboard 元数据污染 Nacos 配置与 Java 8 兼容性等，具体内容见 `TODO.md` 文档。
