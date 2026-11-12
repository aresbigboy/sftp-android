# LanSftpServer

一个运行在 Android 手机上的 **SFTP + rsync 文件服务器**。安装后可在应用内自行设置用户名/密码/端口/共享根目录，局域网或公网下其他设备（电脑、NAS、其他手机）即可通过 SFTP 协议对手机文件进行增删改查，或通过 rsync 做增量同步。

同时内置 **rsync 客户端**，可从远程服务器拉取文件到手机（适合手机做下载端）。

## 功能特性

### SFTP 服务端
- 可配置账号：用户名、密码（≥6 位）、端口、共享根目录均可设置并持久化
- 完整 SFTP 增删改查：列目录、上传、下载、删除、重命名、新建文件夹
- 公钥认证（authorized_keys）：粘贴客户端公钥后免密登录，与密码认证并存
- 根目录隔离：只能访问共享根目录内的文件，路径越权自动拒绝
- **公网提速**：channel window 调至 4MB + 优先 chacha20-poly1305 加密，高延迟公网链路下跑满带宽
- **公网 IPv6 显示**：启动后自动枚举并显示有效的公网 IPv6 地址（过滤 link-local / ULA），长按即可复制

### rsync 双向支持
- **rsync 服务端**：电脑可直接 `rsync -av -e ssh ...` 与手机双向增量同步
  - 支持带空格/引号的中文或英文路径（`/Download/PC/DRAGON QUEST VII Reimagined` 这类目录可直接同步）
  - 服务端按 POSIX 词法解析 SSH exec 命令，并直接以 argv 启动 rsync（不经 shell），既还原路径又杜绝注入
  - 保留 rsync 尾斜杠语义：`/a/b/` 同步目录内容，`/a/b` 同步目录本身
- **rsync 客户端**：App 内置 rsync + dropbear，可从远程服务器拉取文件到手机（适合手机做下载端）
  - 自动补全大接收窗口（4MB）+ chacha20 加密，公网速度从 ~500KB/s 提升到 10MB/s+
  - 自动解析域名（静态 musl 二进制不走系统 DNS，App 端 Java 解析后替换为 IP；路径含空格同样识别）
  - 支持密码认证（`DROPBEAR_PASSWORD` 环境变量，无需交互 TTY）和私钥认证（可粘贴或从文件导入 RSA / ed25519）
  - **后台/息屏保活**：传输期间启动前台服务 + 持有 WakeLock + WifiLock，WiFi 与移动数据下都不会断连
  - **返回不中断**：切到其他页面或返回上一级，传输继续在后台跑；回到界面自动恢复输出与按钮状态
  - **历史命令**：无论成功、失败还是离开页面后才结束，都会记录最近 10 条命令，点击「历史」即可复用

### 其他
- 前台服务 + 常驻通知，锁屏后持续运行
- 按需 WakeLock：无连接时不持锁，手机可深度休眠省电
- 首次启动自动生成 ed25519 密钥对（客户端）和 RSA 2048 主机密钥（服务端）
- 输出框自动滚动到最新，可上下拖动查看历史输出
- 公钥 / 私钥设置区集中在页面底部（使用说明上方），常用的命令与运行区保持在首屏

## 安装要求

- Android 8.0（API 26）及以上
- 首次启动需授予「所有文件访问权限」（Android 11+）
- 部分国产 ROM（小米/华为/OPPO 等）需允许应用「后台运行/自启动」并加入电池优化白名单

## 使用方法

### 启动 SFTP 服务端

1. 安装 APK，打开 App
2. 设置用户名、密码（务必改成强密码！）、端口、共享根目录
3. 点击「启动服务器」，按提示授予权限
4. 界面显示连接地址，例如：
   ```
   sftp://admin@192.168.1.100:2222
   sftp://admin@[240e:xxxx:xxxx:xxxx::xxxx]:2222
   ```
5. 长按地址即可复制

**连接方式**：

```bash
# 命令行 sftp
sftp -P 2222 admin@192.168.1.100

# FileZilla / WinSCP：协议选 SFTP，填 IP/端口/用户名/密码
```

### rsync 服务端（电脑 → 手机双向同步）

```bash
# 推送：电脑 → 手机
rsync -av --delete -e "ssh -p 2222" /本地/照片/ admin@手机IP:/DCIM/Camera/

# 拉取：手机 → 电脑
rsync -av -e "ssh -p 2222" admin@手机IP:/DCIM/ /本地/备份/
```

免密：先 `ssh-copy-id -p 2222 admin@手机IP` 传公钥，之后 rsync 不再输密码。

### rsync 客户端（手机 → 远程服务器拉文件）

1. 打开 App，点击「rsync 客户端」
2. 输入 rsync 命令，例如：
   ```
   rsync -avzP -e "ssh -p22013" user@your-server:~/game-dir/ /sdcard/Download/PC/
   ```
   远程目录名带空格时，用单引号包起来即可：
   ```
   rsync -avzP -e "ssh -p22013" user@your-server:/data/'DRAGON QUEST VII Reimagined' /sdcard/Download/PC/
   ```
3. 如需密码认证，在「远程密码」框输入（明文仅用于本次连接）
4. 点击「运行」，输出框实时显示进度

> App 会自动补全 ssh 路径、`-y`（自动接受主机指纹）、`-i`（私钥路径）、`-W 4194304`（4MB 窗口）、`-c chacha20-poly1305`（快速加密），无需手动指定。

## 技术架构

| 组件 | 说明 |
|---|---|
| SFTP/SSH 服务端 | Apache MINA SSHD 2.12（纯 Java 实现） |
| rsync 服务端 | 静态编译 rsync 3.2.7（musl libc，arm64-v8a / armeabi-v7a） |
| rsync 客户端 | 静态编译 dropbear dbclient + rsync（musl libc） |
| 加密提供者 | BouncyCastle（补齐 Android Conscrypt 缺失算法） |
| 文件系统 | VirtualFileSystemFactory 根目录隔离 + rsync 路径越权校验 |
| 后台运行 | 前台服务 + 按需 WakeLock（活跃会话计数） |
| 最低版本 | Android 8.0（API 26），targetSdk 33 |

核心源码：
- `SftpServerManager.java` — SFTP 服务端核心（启动/认证/根目录/性能调优）
- `RsyncCommand.java` — rsync 服务端命令处理（白名单校验、进程与 IO 转发）
- `RsyncServerArgs.java` — 服务端 rsync 命令的 POSIX 词法解析与共享根路径映射（纯逻辑，可单测）
- `RsyncClientRunner.java` — rsync 客户端单例（进程管理、输出流、命令重写、历史记录）
- `RsyncClientService.java` — rsync 客户端前台服务（传输期间保活）
- `RsyncClientActivity.java` — rsync 客户端界面（生命周期绑定、输出恢复）
- `RemotePathTokens.java` — 远程路径识别与域名→IP 替换（纯逻辑，可单测）
- `ShellTokenizer.java` — POSIX 风格命令分词（单/双引号、反斜杠转义）
- `NetworkUtils.java` — 本机 IPv4/IPv6 地址枚举
- `SftpServerService.java` — 前台服务

## 从源码构建

环境要求：JDK 17、Android SDK（platform 33 + build-tools）、Gradle 7.6.4

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
gradle assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

## 安全提示

- 该服务将手机文件通过密码保护暴露在网络中，请务必设置强密码
- 公网使用时建议配合公钥认证（禁用密码登录），并使用非默认端口
- 仅建议在可信网络环境下使用，不要在不可信网络中暴露端口

## 版本历史

| 版本 | 主要变更 |
|---|---|
| v1.0.29 | 修复带空格文件夹同步失败：服务端改为 POSIX 词法解析 + argv 直启（不经 shell）；客户端域名解析支持带空格路径 |
| v1.0.28 | 公钥 / 私钥设置区下移到页面底部（使用说明上方） |
| v1.0.27 | 修复返回上级或任务失败后历史命令未被记录 |
| v1.0.26 | 返回上一级不再中断 rsync（Runner 改单例，输出/按钮状态自动恢复） |
| v1.0.25 | rsync 客户端新增前台服务保活，后台/锁屏下 WiFi 与移动数据均不断连 |
| v1.0.24 | 修复域名解析失败：解析移到后台线程，失败时回退保留域名，优先选用 IPv4 |
| v1.0.23 | 清理示例命令中的隐私信息（用户名、公网 IP 改为占位符） |
| v1.0.22 | SFTP 服务端公网提速（4MB window + chacha20）；显示公网 IPv6 地址；长按复制地址 |
| v1.0.21 | rsync 客户端提速：4MB 接收窗口 + chacha20（500KB/s → 10MB/s+） |
| v1.0.20 | rsync 客户端后台/息屏保活（WakeLock + WifiLock） |
| v1.0.19 | 回退输出滚动方案；移除超时看门狗；新增域名→IP 解析 |
| v1.0.18 | 修复认证卡死与输出不刷新 |
| v1.0.17 | 修复启动初始化失败 |
| v1.0.15 | 输出框自动滚动到最新 |
| v1.0.14 | 修复 ssh exec；支持密码认证（无需交互）与命令历史 |
| v1.0.13 | 修复 nativeLibraryDir exec；支持私钥导入 |
| v1.0.12 | 新增 rsync 客户端命令窗口 |
| v1.0.11 | 按需 WakeLock 省电改造 |
| v1.0.10 | 修复 rsync 尾斜杠语义丢失 |
| v1.0.8 | 公钥粘贴即生效，无需重启服务端 |
| v1.0.7 | 新增客户端公钥免密登录 |
| v1.0.6 | 改用 musl 静态编译 rsync（修复 seccomp 崩溃） |
| v1.0.5 | 修复真机 rsync 无法执行（改用 nativeLibraryDir 绝对路径） |
| v1.0.4 | 新增 rsync 服务端支持 |
| v1.0.3 | 修复删除/重命名目录失败（尾斜杠路径问题） |
| v1.0.1 | 修复启动崩溃（user.home + BC Provider 冲突） |
| v1.0.0 | 初始版本：SFTP 服务端 |

## License

MIT
