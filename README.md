# hexu-backend

禾序分销批发中台后端，为 hexu-admin 和 hexu-app 提供统一业务 API、权限控制及数据存储。

## 项目简介

在 RuoYi-Vue 基础上开发的多商城分销与批发服务。业务代码集中在 `com.ruoyi.hexu`，管理后台和移动端共用 MySQL 业务数据。

- 商品、SKU、职级价格、运费模板、批发混批和库存锁定。
- 订单报价、收发货、部分退款、退换货、收益与库存冲正。
- 代理关系、客户绑定、跨商城采购审批、考核、升降级及商城交接。
- 级差收益、同级分红承担、提现审核、收付款渠道事件和对账调整。
- 平台/商城积分、批次有效期、转赠、兑换及风控审核。
- 优惠券、限时活动、邀请奖励、拼团和 2+1 联动活动。
- 异步报表快照、CSV 导出、岗位授权、消息模板、审计和开发环境加密备份。

支付、退款、转账及微信身份服务提供可替换接口；当前默认实现为未配置网关。真实渠道需要自行实现/配置并完成验收。本仓库包含持续开发中的功能，代码导入不等同于生产上线验收。

## 技术栈

| 层次 | 实际使用 |
| --- | --- |
| 运行环境 | JDK 17、Maven 多模块工程；父 POM 保留上游 Java 1.8 编译属性，但业务使用 Java 11+ API，请使用 JDK 17 |
| 服务框架 | Spring Boot 2.5.15、Spring Framework 5.3.39、Spring Security 5.7.14 |
| 数据访问 | MySQL 8、Druid、MyBatis、JdbcTemplate、PageHelper |
| 会话与缓存 | Redis、管理端 JWT、移动端服务端会话 |
| 验证 | JUnit 5、Spring Boot Test、H2 2.2.224 |
| 辅助脚本 | PowerShell、Node.js；本地备份调用 mysql/mysqldump |

## 关联仓库

| 项目 | 说明 | GitHub |
| --- | --- | --- |
| hexu-backend | 后端 API、业务规则与数据库 | [hexu-backend](https://github.com/jiangyi3265/hexu-backend) |
| hexu-admin | 平台 Web 管理后台 | [hexu-admin](https://github.com/jiangyi3265/hexu-admin) |
| hexu-app | 用户、代理与商家移动端 | [hexu-app](https://github.com/jiangyi3265/hexu-app) |

三个代码仓库对应后端、平台后台、移动客户端；用户商城、代理和商家操作入口共同包含在 hexu-app 中。

## 快速启动

以下为 Windows PowerShell 本地联调流程。准备 JDK 17、Maven、MySQL 8、Redis；将 java、mvn、mysql、mysqldump 加入 PATH。备份功能另需 Node.js。

### 1. 创建本地配置

在仓库根目录执行：

```powershell
Copy-Item ruoyi-admin/src/main/resources/application.yml.example ruoyi-admin/src/main/resources/application.yml
Copy-Item ruoyi-admin/src/main/resources/application-druid.yml.example ruoyi-admin/src/main/resources/application-druid.yml
Copy-Item ruoyi-admin/src/main/resources/application-hexu-dev.yml.example ruoyi-admin/src/main/resources/application-hexu-dev.yml
```

编辑 application-druid.yml 中主库用户名、密码；示例中的 REPLACE_WITH_* 必须替换为本地配置。初始化脚本连接本机 MySQL，数据库固定为 `hexu_integration`；请使用有权创建该开发数据库的本地账号。Redis 默认连接 localhost:6379，联调使用数据库 9；有密码时在当前进程设置 HEXU_REDIS_PASSWORD。上述真实配置均被 Git 忽略。

### 2. 编译并生成管理员密码散列

```powershell
mvn -pl ruoyi-admin -am package -DskipTests
java --class-path "$env:USERPROFILE/.m2/repository/org/springframework/security/spring-security-crypto/5.7.14/spring-security-crypto-5.7.14.jar" scripts/HashPassword.java
```

HashPassword.java 在交互终端隐藏输入，要求至少 12 个字符，只输出 BCrypt 散列。保留密码用于后台登录，将散列粘贴到下一步提示中；不要写入仓库。若使用自定义 Maven 本地缓存，调整 class-path。仓库不提供默认管理员密码。

### 3. 初始化并启动

```powershell
powershell -NoProfile -File scripts/hexu-local.ps1 -Action init
powershell -NoProfile -File scripts/hexu-local.ps1 -Action migrate
powershell -NoProfile -File scripts/hexu-local.ps1 -Action run
```

init 仅允许创建空的 hexu_integration，包含系统表和明确标注的演示商城数据；检测到已有业务表会拒绝覆盖。已有数据库不要再次 init。migrate 执行增量迁移，run 自动生成本地随机开发密钥，启动 `druid,hexu-dev` 配置；服务地址为 http://127.0.0.1:8088。管理端用户名为 admin，密码为上一步自行输入的密码。

跨域来源默认只允许本机 H5 与管理端。分域部署时，在启动前将实际前端 Origin（含协议和端口）写入 `HEXU_CORS_ALLOWED_ORIGINS`，多个地址用英文逗号分隔；同源部署无需配置。不要使用 `*`。

查看商城列表：

```powershell
Invoke-RestMethod http://127.0.0.1:8088/hexu/app/shops
```

启动另外两个仓库即可联调。`hexu-dev` 的身份和渠道模拟入口仅用于本机，运行目录 `.hexu-local` 包含密钥、备份等，不得提交或公开。正式环境需提供自己的配置并关闭开发配置；真实微信和支付渠道尚需接入。

### 指定域名的服务器配置

- 小程序及 H5 使用 `https://fenxiao.oksja.cn`，接口由该域名的 `/hexu/` 转发到后端。
- 管理后台使用 `https://fenxiao.web.oksja.cn`，其 `/prod-api/` 转发到同一后端，并去掉此前缀。
- 现有服务器的 Nginx 站点扩展分别使用 `deploy/nginx-fenxiao-app.conf` 和 `deploy/nginx-fenxiao-admin.conf`，将接口代理到本机 8093。管理后台静态包位于 `/www/wwwroot/fenxiao.web.oksja.cn/menu-default-20261006`。
- 正式后端叠加 `application-hexu-prod.yml`，连接 `hexu_integration` 的 MySQL 3306。服务器使用 `/www/server/fenxiao/.env.production` 提供密码和密钥，使用独立的 `fenxiao-java.service` 与 `deploy/start-fx.sh` 选用 JDK 17，以 `druid,hexu-prod` 启动；不改变全局 Java 8。部署包及具体步骤见 `dist/fx-java-db-hexu-20261005/README-DEPLOY.txt`。
- 发布前将 `https://fenxiao.oksja.cn` 加入微信公众平台的 request、uploadFile、downloadFile 合法域名，并核对 DNS、HTTPS 证书和网关回执。

### 业务测试

```powershell
mvn -pl ruoyi-admin -am test -Dtest=HexuBusinessTest -Dsurefire.failIfNoSpecifiedTests=false
```

测试使用内存 H2，覆盖订单、库存、收益、退款、积分、营销、报表和权限等业务规则。

### 本地备份

```powershell
node scripts/backup-database.mjs backup
```

需要 mysql 与 mysqldump 在 PATH 中，或设置 HEXU_MYSQL_BIN 指向其目录。加密备份和密钥保存在 .hexu-local；恢复命令仅允许新建 hexu_restore_* 验证数据库，详情见脚本。

## 项目结构

```text
ruoyi-admin/       应用入口、Hexu API/业务逻辑、表结构/演示数据与业务测试
ruoyi-common/      公共模型、工具、异常与注解
ruoyi-framework/   安全、缓存、Web 与框架配置
ruoyi-system/      系统用户、角色、菜单、字典与日志
ruoyi-quartz/      保留的上游任务模块（当前 admin 未引用）
ruoyi-generator/   保留的上游生成器模块（当前 admin 未引用）
sql/              脱敏系统初始化模板与 Hexu 菜单 SQL
scripts/          本地初始化、迁移、启动、密码散列与加密备份
```

旧机器配置、历史数据库初始化文件、依赖和产物仅保留本地，不在公开提交中。

## 简历描述示例

参与禾序分销批发中台后端开发，建设多商城订单、代理分润、积分营销及对账流程，通过统一 API 支撑运营后台与用户/商家移动端。实现幂等业务命令、历史规则快照、租户权限校验及异步报表，并使用 H2 业务测试验证资金和库存流转。

## 开源基础

基于 [RuoYi-Vue](https://gitee.com/y_project/RuoYi-Vue) 二次开发，保留上游 [MIT LICENSE](LICENSE) 及版权声明。
