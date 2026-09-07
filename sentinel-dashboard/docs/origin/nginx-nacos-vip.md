---
date: 2026-04-23 00:00:00
updated: 2026-04-23 00:00:00
---

# Docker 中部署 Nginx

## 一、部署目标

当前环境中，`XXL-JOB` 已经是 3 节点集群，`Nacos` 也是 3 节点集群，但外部访问仍然是多个端口、多个节点地址。

本方案通过一个独立的 `Nginx` 容器提供统一入口：

- 通过宿主机 IP `192.168.99.99` 代理 `XXL-JOB` 与 `Nacos`
- `Nginx` 同时接入 `xxl-cluster-net` 和 `nacos-cluster-net`
- `994` 统一承接 `XXL-JOB` 与 `Nacos` 的 HTTP 请求
- `1994` 单独承接 `Nacos` 客户端 gRPC 请求

## 二、为什么先用单节点 Nginx

在当前单机 Docker 环境中，先部署一个 `Nginx` 单节点就够了。

原因如下：

- 你现在最需要的是统一域名入口和后端负载均衡
- 单机 Docker 下部署多个 `Nginx` 容器，不能自动形成真正的 `VIP`
- 即使做成多个 `Nginx` 容器，宿主机仍然是单点
- 真正的入口高可用更适合放到后续多主机场景中，再做 `Keepalived`、上层负载均衡或 DNS 故障切换

## 三、目录结构

```text
Docker 中部署 Nginx/
├── .env
├── compose.yaml
├── conf
│   ├── nginx.conf
│   ├── conf.d
│   │   ├── nacos-http.conf
│   │   └── xxl-job.conf
│   └── stream.d
│       └── nacos-grpc.conf
└── Docker 中部署 Nginx.md
```

## 四、IP 与端口规划

### 1、XXL-JOB

- 宿主机 IP：`192.168.99.99`
- 端口：`994`
- 访问地址：`http://192.168.99.99:994/xxl-job-admin/`

### 2、Nacos

- 宿主机 IP：`192.168.99.99`
- HTTP 端口：`994`
- gRPC 端口：`1994`
- 控制台地址：`http://192.168.99.99:994/nacos`
- 客户端 `serverAddr`：`192.168.99.99:994`

## 五、本地挂载说明

本次保留本地日志目录挂载，风格与已有服务一致，通过 `.env` 中的变量引用 `DOCKER_CONTAINER_DATA_PATH`。

当前挂载如下：

- `${NGINX_LOG_DIR}:/var/log/nginx`
- `./conf/nginx.conf:/etc/nginx/nginx.conf:ro`
- `./conf/conf.d:/etc/nginx/conf.d:ro`
- `./conf/stream.d:/etc/nginx/stream.d:ro`

其中：

- 本地日志目录：`${DOCKER_CONTAINER_DATA_PATH}/nginx/logs`
- 配置目录直接使用当前项目下的文件，便于修改与版本管理

## 六、资源限制说明

Nginx 这里只承担反向代理和 TCP 转发，不跑静态站点、不做大流量缓存，因此资源不需要配得太高。

当前 `compose.yaml` 中的资源配置如下：

- CPU limit：`1`
- CPU reservation：`0.5`
- memory limit：`512m`
- memory reservation：`128m`

这个配置适合当前开发 / 测试环境。如果后续并发上来，再按实际监控数据调大即可。

## 七、使用前提

启动前需要确认以下 Docker 网络已经存在：

- `xxl-cluster-net`
- `nacos-cluster-net`

此外，需要保证宿主机 IP `192.168.99.99` 可被访问。

## 八、启动方式

进入目录：

```bash
cd "/Users/zhuozhuo/Documents/notes/部署/Docker 中部署 Nginx"
```

启动容器：

```bash
docker compose up -d
```

查看日志：

```bash
docker logs -f nginx-vip
```

## 九、验证方式

### 0、配置变更生效注意事项

当你修改以下任一配置后，必须重启对应容器，否则可能看起来“配置文件已改但行为未变化”：

- `Nginx` 配置（`nginx.conf`、`conf.d/*.conf`、`stream.d/*.conf`）改动后，重启 `nginx-vip`
- `XXL-JOB` 节点配置（`application-1/2/3.properties`）改动后，重启 `xxl-admin-1/2/3`

推荐命令：

```bash
# 重启 Nginx
cd "/Users/zhuozhuo/Documents/notes/部署/Docker 中部署 Nginx"
docker compose restart nginx-vip

# 重启 XXL-JOB 三节点
cd "/Users/zhuozhuo/Documents/notes/部署/Docker 中部署 XXL-JOB/使用 MySQL 为数据源/cluster"
docker compose -f compose-cluster.yaml restart xxl-admin-1 xxl-admin-2 xxl-admin-3
```

最小验证建议（确认跳转端口是否正确带上 `:994`）：

```bash
curl -I "http://192.168.99.99:994/xxl-job-admin/"
```

预期响应头示例：

```text
Location: http://192.168.99.99:994/xxl-job-admin/auth/login
```

### 1、验证 XXL-JOB

访问：

```text
http://192.168.99.99:994/xxl-job-admin/
```

预期结果：

- 页面能够正常打开
- 随机停止一个 `XXL-JOB` 节点后仍可访问

### 2、验证 Nacos 控制台

访问：

```text
http://192.168.99.99:994/nacos
```

预期结果：

- 控制台可以正常打开
- 查询配置、命名空间等基础功能正常

### 3、验证 Nacos 客户端

应用侧将 `serverAddr` 指向：

```text
192.168.99.99:994
```

预期结果：

- 服务注册正常
- 服务发现正常
- 配置拉取正常

## 十、关于 Nacos gRPC

`Nacos 2.x` 客户端除了访问 HTTP 入口端口，还会访问 `server.port + 1000` 的 gRPC 端口。

当前 HTTP 统一入口走 `994`，所以这里额外通过 `stream` 暴露：

- `1994`

而后端 `Nacos` 节点自身仍然使用内部 gRPC 端口：

- `9848`
- `9858`
- `9868`

这也是为什么这个 `Nginx` 配置不只是 `http`，还包含 `stream` 配置。

## 十一、关于 nginx:1.30.0 的注意事项

本方案固定使用镜像：

```text
nginx:1.30.0
```

如果启动时报错提示 `stream` 相关指令不可用，一般说明当前镜像内的 `stream` 模块装载方式和配置文件不匹配。

这时按下面顺序排查：

1. 先进入容器执行 `nginx -V`，确认是否包含 `stream` 模块
2. 如果是动态模块，需要补充 `load_module` 配置
3. 如果当前变体不含 `stream`，则需要在保持 `nginx:1.30.0` 为基础镜像的前提下，额外构建一个包含 `stream` 模块的镜像

如果后面你需要，我可以继续帮你把这一步也补完整。

## 十二、后续升级路线

如果以后你要把入口本身也做成高可用，建议按下面的顺序升级：

1. 保留当前单节点 `Nginx` 方案，先跑稳
2. 等进入双机或多主机部署，再把 `Nginx` 扩展为两台
3. 在两台入口节点前面加 `VIP`、云负载均衡或 `HAProxy`

当前阶段不建议直接在单机 Docker 上做“`Nginx` 集群 + VIP”。
