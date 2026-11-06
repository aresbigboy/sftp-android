package com.example.lansftp;

import android.content.Context;
import android.util.Log;

import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.PublicKey;
import java.security.Security;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 基于 Apache MINA SSHD 的 SFTP 文件服务器核心。
 * 支持：用户名/密码认证、自定义端口、根目录隔离、增删改查（SFTP 协议原生支持
 * 上传、下载、删除、重命名、建目录、列目录等全部文件操作）。
 */
public class SftpServerManager {
    private static final String TAG = "SftpServerManager";
    private static SftpServerManager sInstance;

    private final Context appContext;
    private SshServer sshd;
    private String lastError;

    /** 活跃 SSH 会话计数（用于按需 WakeLock：0 个连接时不持锁，首个连接进来才持锁） */
    private final java.util.concurrent.atomic.AtomicInteger activeSessions =
            new java.util.concurrent.atomic.AtomicInteger();
    private android.os.PowerManager.WakeLock wakeLock;

    private SftpServerManager(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    public static synchronized SftpServerManager getInstance(Context ctx) {
        if (sInstance == null) {
            sInstance = new SftpServerManager(ctx);
        }
        return sInstance;
    }

    public synchronized String getLastError() {
        return lastError;
    }

    /** 启动 SFTP 服务器；成功返回 true，失败返回 false 并用 getLastError() 查看原因 */
    public synchronized boolean start(int port, String username, String password, String rootPath,
                                      String authorizedKeys) {
        lastError = null;
        try {
            if (sshd != null && sshd.isStarted()) {
                return true; // 已在运行
            }
            if (username == null || username.trim().isEmpty()) {
                lastError = "用户名不能为空";
                return false;
            }
            if (password == null || password.length() < 6) {
                lastError = "密码至少需要 6 位";
                return false;
            }
            if (port < 1 || port > 65535) {
                lastError = "端口号无效（1-65535）";
                return false;
            }
            File root = new File(rootPath);
            if (!root.isDirectory()) {
                lastError = "共享根目录不存在：" + rootPath;
                return false;
            }

            // 关键修复：Android 没有 user.home 系统属性，sshd 初始化时读取它会抛
            // ExceptionInInitializerError("No user home") 导致崩溃，必须先设置
            System.setProperty("user.home", appContext.getFilesDir().getAbsolutePath());

            // 显式移除并重新注册 BouncyCastle：
            // - 部分 Android 系统自带旧版 BC（名称同为 "BC"），直接 insertProviderAt 会被忽略，
            //   sshd 探测到旧版 BC 会触发 NoClassDefFoundError(javax.management.*) 崩溃
            // - 先 remove 再 add 确保加载的是我们打包的版本，补齐 Conscrypt 缺失的算法
            Security.removeProvider("BC");
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

            SshServer server = SshServer.setUpDefaultServer();
            server.setHost("0.0.0.0"); // 监听所有网卡，供局域网访问
            server.setPort(port);

            // v1.0.22：公网高延迟链路下优化吞吐
            //  - window-size：默认 64KB 在 RTT 50ms 的公网链路下吞吐 ≈ 1.2MB/s；调到 4MB 后 ≈ 80MB/s
            //  - cipher 优先 chacha20-poly1305：纯软件加密在 ARM64 上比软 AES 快约 60%+
            server.getProperties().put("window-size", 4194304);
            server.setCipherFactoriesNameList(
                    "chacha20-poly1305@openssh.com," +
                    "aes128-ctr,aes192-ctr,aes256-ctr," +
                    "aes128-gcm@openssh.com,aes256-gcm@openssh.com");

            // 主机密钥（首次启动自动生成 RSA 2048，持久化在应用私有目录）
            SimpleGeneratorHostKeyProvider hostKey = new SimpleGeneratorHostKeyProvider(
                    new File(appContext.getFilesDir(), "hostkey.ser").toPath());
            hostKey.setAlgorithm("RSA");
            server.setKeyPairProvider(hostKey);

            // 用户名/密码认证
            server.setPasswordAuthenticator((u, p, session) ->
                    username.equals(u) && password.equals(p));

            // 客户端公钥认证（authorized_keys，免密登录）：在设置里粘贴客户端公钥后，
            // 该客户端的 ssh / rsync 连接即无需密码。公钥认证与密码认证可并存（任一通过即可）。
            // 实现完全复刻 sshd 官方 AuthorizedKeysAuthenticator 路径：
            //   readAuthorizedKeys(StringReader) + entry.resolvePublicKey(session, loginOptions, IGNORING)
            //   + KeyUtils.compareKeys（官方内部比较器，兼容不同 JCA Provider 的 key 对象）。
            // 每次认证时实时读取持久化数据：粘贴公钥后无需重启服务器，下次连接立即生效。
            server.setPublickeyAuthenticator((u, key, session) -> {
                if (key == null) {
                    return false;
                }
                String keys = Settings.readAuthorizedKeys(appContext);
                if (keys == null || keys.trim().isEmpty()) {
                    return false;
                }
                try {
                    List<AuthorizedKeyEntry> entries = AuthorizedKeyEntry.readAuthorizedKeys(
                            new StringReader(keys), true);
                    for (AuthorizedKeyEntry entry : entries) {
                        try {
                            PublicKey stored = entry.resolvePublicKey(session, entry.getLoginOptions(),
                                    org.apache.sshd.common.config.keys.PublicKeyEntryResolver.IGNORING);
                            if (stored != null && KeyUtils.compareKeys(key, stored)) {
                                return true;
                            }
                        } catch (Exception ignored) {
                            // 跳过无法解析的行
                        }
                    }
                } catch (Exception ignored) {
                    // 整体解析失败视为无匹配
                }
                return false;
            });

            // SSH exec 命令工厂：支持 rsync over SSH。
            // 客户端：rsync -av -e "ssh -p 端口 用户名@手机IP" 本地目录/ 用户名@手机IP:/目标目录/
            // 打包的静态 rsync 二进制位于 nativeLibraryDir（Android 10+ 唯一可执行的自带二进制位置，
            // 由 useLegacyPackaging=true 保证被解压为独立文件并带执行权限）。
            String rsyncPath = appContext.getApplicationInfo().nativeLibraryDir + "/librsync.so";
            File rsyncFile = new File(rsyncPath);
            if (rsyncFile.exists()) {
                server.setCommandFactory((channel, command) -> {
                    if (RsyncCommand.isSafeRsyncCommand(command)) {
                        return new RsyncCommand(command, root.getAbsolutePath(), rsyncPath);
                    }
                    return null; // 拒绝未知/危险命令
                });
            } else {
                Log.w(TAG, "rsync 二进制缺失（rsync 功能不可用，SFTP 不受影响）：" + rsyncPath);
            }

            // SFTP 子系统（提供增删改查文件操作）。
            // 修复：某些 SFTP 客户端删除/重命名目录时发送带尾斜杠（或 "/."）的路径，
            // sshd 会把它解析成 "/xxx/."，导致 rmdir 触发 Invalid argument(EINVAL)、
            // 重命名触发 No such file。这里在路径解析入口统一清理尾部的 "/" 和 "/."。
            SftpSubsystemFactory factory = new SftpSubsystemFactory();
            factory.setFileSystemAccessor(new SftpFileSystemAccessor() {
                @Override
                public Path resolveLocalFilePath(SftpSubsystemProxy subsystem, Path root, String remotePath)
                        throws IOException, InvalidPathException {
                    String cleaned = remotePath;
                    while (cleaned.length() > 1 && (cleaned.endsWith("/") || cleaned.endsWith("\\"))) {
                        cleaned = cleaned.substring(0, cleaned.length() - 1);
                    }
                    if (cleaned.endsWith("/.")) {
                        cleaned = cleaned.substring(0, cleaned.length() - 2);
                    }
                    return root.resolve(cleaned);
                }
            });
            server.setSubsystemFactories(Collections.singletonList(factory));

            // 根目录隔离：客户端只能访问共享根目录内的文件
            server.setFileSystemFactory(new VirtualFileSystemFactory(root.toPath()));

            // 按需 WakeLock（Termux 式省电）：
            // 空闲（无活跃 SSH 连接）时不持锁，手机可进入深度休眠；有连接进来才持有，
            // 保证 ssh/sftp/rsync 传输期间 CPU 稳定；全部断开后释放。
            server.addSessionListener(new org.apache.sshd.common.session.SessionListener() {
                @Override
                public void sessionCreated(org.apache.sshd.common.session.Session session) {
                    if (activeSessions.incrementAndGet() == 1) {
                        acquireLock();
                    }
                }

                @Override
                public void sessionClosed(org.apache.sshd.common.session.Session session) {
                    if (activeSessions.decrementAndGet() == 0) {
                        releaseLock();
                    }
                }
            });

            server.start();
            sshd = server;
            return true;
        } catch (Throwable t) {
            // 必须捕获 Throwable：Android 兼容性问题可能抛 Error（如 ExceptionInInitializerError）
            lastError = t.getMessage() == null ? t.toString() : t.getMessage();
            Log.e(TAG, "启动 SFTP 服务器失败", t);
            stop();
            return false;
        }
    }

    public synchronized void stop() {
        if (sshd != null) {
            try {
                sshd.stop(true);
            } catch (IOException e) {
                Log.w(TAG, "停止服务器异常", e);
            }
            sshd = null;
        }
        activeSessions.set(0);
        releaseLock(); // 保险：确保停止后锁一定被释放
    }

    public synchronized boolean isRunning() {
        return sshd != null && sshd.isStarted();
    }

    private synchronized void acquireLock() {
        try {
            if (wakeLock == null) {
                android.os.PowerManager pm =
                        (android.os.PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "LanSftp:active");
            }
            wakeLock.acquire();
            Log.i(TAG, "WakeLock acquired (active sessions)");
        } catch (Throwable t) {
            Log.w(TAG, "WakeLock acquire failed", t);
        }
    }

    private synchronized void releaseLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.i(TAG, "WakeLock released (no active sessions)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "WakeLock release failed", t);
        }
    }
}
