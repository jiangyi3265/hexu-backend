后端启动修复包（仅本项目使用 JDK 17）

1. 将 fx-startup.zip 上传到 /www/wwwroot/fenxiao.java 并解压，覆盖旧 fx.jar。
   解压后该目录应有 fx.jar、start-fx.sh。
2. 将单独提供的 .env.production 放在 /www/server/fenxiao/.env.production，
   不要放入任何网站的静态目录。确保启动用户可读，并执行：
   chmod 600 /www/server/fenxiao/.env.production
3. 在服务器终端先检查环境变量及 Java：
   HEXU_ENV_FILE=/www/server/fenxiao/.env.production bash /www/wwwroot/fenxiao.java/start-fx.sh --check
4. 宝塔 Java 项目的启动命令设为：
   HEXU_ENV_FILE=/www/server/fenxiao/.env.production bash /www/wwwroot/fenxiao.java/start-fx.sh
   不需修改服务器全局 Java；脚本只选择 JDK 17。若自动找不到，
   在 .env.production 中设置 HEXU_JAVA17_BIN=/实际路径/bin/java。
5. 后端监听 127.0.0.1:8093。确认现有 Nginx 反向代理也指向 8093，
   启动后检查日志和 HTTPS 接口。--check 不会连接数据库。

此环境文件已设置数据库 hexu_integration、账号 hexu_integration、
端口 3306 和此前指定的数据库密码。数据库是从本地
完整导入的，因此资料加密密钥保持与本地一致，避免已有加密资料无法读取。
新的令牌密钥只影响旧登录态；部署后用户需重新登录。

注意：fx-startup.zip 不含数据库密码或密钥；.env.production 单独上传。
