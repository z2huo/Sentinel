# Sentinel Dashboard Nacos 适配待办清单

## 一、背景

本文档基于对 z2huo 提交的 Sentinel Dashboard 持久化数据到 Nacos 适配（双向推送）的代码审查整理，记录当前实现中待继续完善的点，供后续迭代处理。

## 二、高优先级待办

### 1、Nacos 集成改为可选启用（opt-in）

- 现状：`application.properties` 无条件配置 `spring.profiles.include[0]=nacos`，`application-nacos.properties` 硬编码了私有环境地址 `192.168.99.99:8848,8858,8868`
- 问题：任何用户构建启动 Dashboard 后都会默认连接该私有 Nacos，规则查询每次 3 秒超时报错，破坏原 standalone 模式的用户体验；私有环境地址不应提交到代码仓库
- 建议：`spring.profiles.active` 交由环境变量控制（如 `${SENTINEL_PROFILE:local}`），或使用 `@ConditionalOnProperty("nacos.address")` 条件装配 Provider 与 Publisher；`nacos.address` 留空或使用占位符，不提交真实地址

### 2、发布失败需要被调用方感知

- 现状：各 Controller 的 `publishRules` 方法发布 Nacos 失败时仅记录 `logger.warn`，接口仍返回 `Result.ofSuccess`
- 问题：用户看到保存成功，但 Nacos 未写入、客户端规则不会生效，且无任何途径感知失败
- 建议：发布失败时返回失败结果给前端，或采用内存保存与发布的一致性方案（发布失败回滚内存操作）

### 3、消除规则 ID 生成的并发竞态

- 现状：`GatewayFlowRuleController` 与 `GatewayApiController` 新增规则时，从 Nacos 读取现有规则后取 `max(id) + 1` 作为新 ID
- 问题：并发请求会获得相同 ID 导致规则互相覆盖；新增规则变成依赖 Nacos 可用；内存仓库 `nextId` 计数器重启归零，与 Nacos 中已存在的 ID 可能冲突
- 建议：保留 `InMemoryRuleRepositoryAdapter.save()` 的原子 `nextId()` 分配机制，或引入持久化 ID 生成策略（时间戳或全局唯一 ID）

### 4、避免 Dashboard 元数据污染 Nacos 配置

- 现状：Publisher 直接序列化整个 Entity（含 `id`、`ip`、`port`、`gmtCreate`、`gmtModified`）；`queryFlowRules`、`queryApis` 查询时将当前机器 `ip/port` 写入实体并 `saveAll`，随后 `publishRules` 通过 `findAllByApp` 将这些字段写回 Nacos
- 问题：多台机器交替查询会互相覆盖，Nacos 中的规则数据随查询机器漂移，共享配置被无意义数据污染
- 建议：Converter 基于 `FlowRule` 等规则类而非 `FlowRuleEntity` 进行序列化（官方社区做法），或发布前剥离 Dashboard 特有字段

### 5、恢复 Java 8 兼容性

- 现状：各 Provider 使用 `List.of()` 返回空列表，该 API 为 Java 9 引入；根 pom 声明 `java.source.version=1.8`
- 问题：编译不报错但产物在 Java 8 JRE 上运行抛 `NoSuchMethodError`，Dockerfile 使用 temurin:17 掩盖了该问题
- 建议：改回 `Collections.emptyList()` 或 `new ArrayList<>()`

## 三、后续优化待办

### 1、查询路径增加降级方案

- 现状：规则查询完全依赖 Nacos，Nacos 不可用时所有规则页面直接报错，原实现从客户端机器拉取规则可用性更高
- 建议：Nacos 查询失败时回退到内存仓库已有缓存，保证只读降级

### 2、统一各 Controller 的机器信息处理

- 现状：`AuthorityRuleController`、`DegradeController`、`ParamFlowRuleController`、`SystemController` 从 Nacos 读取规则后直接 `repository.saveAll`，未补齐 `ip/port`，与网关两个 Controller 的处理不一致
- 问题：反序列化后 `ip/port` 为 null 或陈旧值，依赖 `findAllByMachine` 的逻辑会失效
- 建议：统一补齐机器信息，或统一改为按 app 维度管理并移除对机器维度的依赖

### 3、清理注释残留的旧逻辑

- 现状：各 Controller 中存在大量被注释掉的 `fetchFromClient`、`publishToClient` 旧逻辑，以及 `GatewayFlowRuleController` 中已废弃的 `publishRules(app, ip, port)` 私有方法
- 建议：直接删除，通过 git 历史追溯即可

### 4、修正 NacosConfig 细节问题

- 现状：`NacosConfig` 中降级规则 region 缺少 `// endregion`；`nacosConfigService()` 声明 `throws Exception` 且使用 Spring Boot 内部 API `PropertyMapper`；bean 名 `authorRuleEntityEncoder` 存在拼写；region 注释中英文混用
- 问题：`PropertyMapper` 在 Spring Boot 3 已移除，升级 Spring Boot 会编译失败
- 建议：补全 region 注释；改为 `if` 判断直接设置 `Properties`；修正拼写并统一注释语言

### 5、完善配置与部署文件

- 现状：`application-nacos.properties` 文件末尾无换行符；`nacos.username/password` 被注释导致 Nacos 开启鉴权时无法连接；Dockerfile 基础镜像靠注释切换版本且 `COPY ./packages/sentinel-dashboard-1.8.8.jar` 依赖人工放置构建产物
- 建议：补齐换行；补充 Nacos 鉴权配置说明；Dockerfile 基础镜像改为 `ARG` 变量化，构建产物改为多阶段构建或明确说明构建前置步骤

### 6、补充单元测试

- 现状：新增的 7 类规则 Provider 与 Publisher 均无测试覆盖，仅 test 目录保留官方原有 FlowRule 示例测试
- 问题：此前多次出现的 dataId 后缀复制粘贴错误（authority 后缀误用于 param、system、gateway 规则）未被及时发现
- 建议：为每类规则补充 encoder 到 decoder 的编解码往返测试，以及 Provider 空配置场景测试

## 四、建议处理顺序

1. Nacos 集成改为可选启用
2. 发布失败需要被调用方感知
3. 消除规则 ID 生成的并发竞态
4. 避免 Dashboard 元数据污染 Nacos 配置
5. 恢复 Java 8 兼容性
6. 其余各项作为代码卫生项分批处理
