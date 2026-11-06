package com.example.lansftp;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * rsync 客户端执行器。
 *
 * 二进制部署策略（v1.0.13 起修复 error=13）：
 *  Android data 分区（filesDir）上复制出的文件不可 execve（SELinux 限制），
 *  而 APK jniLibs 解压目录 nativeLibraryDir 下的文件可直接 exec（v1.0.5 已验证）。
 *  因此：rsync / dbclient(ssh=libssh.so) / dropbearkey 全部直接使用 nativeLibraryDir 里的文件。
 *  v1.0.14 起 run() 通过 rewriteSsh 把 -e "ssh ..." 重写为 libssh.so 绝对路径并自动补 -y/-i；
 *  v1.0.17 起彻底移除 filesDir/bin/ssh 软链接逻辑（部分机型 Os.symlink 亦被拒，且 data 分区 exec 受限）。
 *
 * 认证：私钥免密（内置 dropbearkey 生成 ed25519 / 导入用户 OpenSSH v1、PEM 的 RSA·ed25519）
 *  + 密码认证（DROPBEAR_PASSWORD 环境变量，dropbear 2022.83 原生支持）。
 */
public class RsyncClientRunner {

    private static final String TAG = "RsyncClient";
    public static final String KEY_DIR = "keys";
    public static final String PRIV_KEY = "id_ed25519";     // 内置密钥
    public static final String USER_KEY = "user_key";       // 导入密钥（dropbear 原生格式）

    private final Context ctx;
    private final File keyDir;
    private Process process;
    /** 同步期间持有的 CPU 唤醒锁（v1.0.20：防后台/息屏时 CPU 挂起导致连接中断） */
    private PowerManager.WakeLock wakeLock;
    /** 同步期间持有的 WiFi 高性能锁（v1.0.20：防息屏 WiFi 省电断流） */
    private WifiManager.WifiLock wifiLock;

    public interface OutputListener {
        /** 追加一行输出（可能是 stdout 或 stderr） */
        void onLine(String line);

        /** 进程结束 */
        void onExit(int exitCode, String summary);
    }

    public RsyncClientRunner(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.keyDir = new File(ctx.getFilesDir(), KEY_DIR);
    }

    // ---------- 路径 ----------
    private String nativeLibDir() {
        return ctx.getApplicationInfo().nativeLibraryDir;
    }

    private String rsyncPath() {
        return nativeLibDir() + "/librsync.so";
    }

    private String dropbearkeyPath() {
        return nativeLibDir() + "/libdropbearkey.so";
    }

    private String builtinKeyPath() {
        return new File(keyDir, PRIV_KEY).getAbsolutePath();
    }

    /** 当前生效私钥：导入过用户私钥则用它，否则用内置密钥 */
    public String getActiveKeyPath() {
        File uk = new File(keyDir, USER_KEY);
        return uk.exists() ? uk.getAbsolutePath() : builtinKeyPath();
    }

    public boolean hasImportedKey() {
        return new File(keyDir, USER_KEY).exists();
    }

    /** 是否已具备运行条件（二进制就绪） */
    public boolean isReady() {
        return new File(rsyncPath()).exists() && new File(dropbearkeyPath()).exists();
    }

    /**
     * 部署校验：rsync / dbclient(ssh=libssh.so) / dropbearkey 全部直接使用
     * nativeLibraryDir 下的文件（v1.0.5 验证可 exec）。
     * v1.0.17 起不再创建 filesDir/bin/ssh 软链接或副本：
     *  - Android SELinux 下 data 分区文件不可 execve（error=13）；
     *  - 部分机型 Os.symlink 也被拒，且软链接 exec 同样受限；
     *  - run() 已通过 rewriteSsh 把 -e "ssh ..." 重写为 libssh.so 绝对路径，不依赖任何 PATH 查找。
     */
    public synchronized void ensureBins() throws IOException {
        checkBin("librsync.so");
        checkBin("libssh.so");
        checkBin("libdropbearkey.so");
    }

    private void checkBin(String name) throws IOException {
        File f = new File(nativeLibDir(), name);
        if (!f.exists()) {
            throw new IOException("内置组件缺失：" + f.getAbsolutePath());
        }
        if (!f.canExecute()) {
            Log.w(TAG, "note: " + f + " not marked executable, will try exec anyway");
        }
    }

    // ---------- 密钥 ----------
    /** 生成（或复用）内置 ed25519 密钥；返回私钥绝对路径 */
    public String ensureKey() throws IOException {
        if (!keyDir.exists() && !keyDir.mkdirs()) {
            throw new IOException("无法创建密钥目录");
        }
        File priv = new File(keyDir, PRIV_KEY);
        if (priv.exists()) {
            return priv.getAbsolutePath();
        }
        runAndWait(dropbearkeyPath(), "-t", "ed25519", "-f", priv.getAbsolutePath(),
                "生成密钥失败");
        return priv.getAbsolutePath();
    }

    /**
     * 导入用户自己的私钥：解析文本（OpenSSH v1 / PEM，RSA / ed25519，无口令），
     * 转换为 dropbear 原生格式保存为 user_key，并立即生效。
     */
    public synchronized void importPrivateKey(String privKeyText) throws IOException {
        byte[] raw = OpenSshKeyConverter.convertToDropbear(privKeyText);
        if (!keyDir.exists() && !keyDir.mkdirs()) {
            throw new IOException("无法创建密钥目录");
        }
        File out = new File(keyDir, USER_KEY);
        File tmp = new File(keyDir, USER_KEY + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(raw);
            fos.getFD().sync();
        }
        if (!tmp.renameTo(out)) {
            throw new IOException("保存私钥失败");
        }
        Log.i(TAG, "imported user key -> " + out);
    }

    /** 移除导入的私钥，回到内置密钥 */
    public void resetToBuiltinKey() {
        File uk = new File(keyDir, USER_KEY);
        if (uk.exists()) {
            uk.delete();
        }
    }

    /** 当前生效私钥对应的公钥文本（用于免密配置）；失败返回 null */
    public String getPublicKeyText() {
        String keyPath = getActiveKeyPath();
        if (!new File(keyPath).exists()) {
            return null;
        }
        try {
            Process p = new ProcessBuilder(dropbearkeyPath(), "-y", "-f", keyPath)
                    .redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            StringBuilder sb = new StringBuilder();
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("ssh-") && t.contains(" ")) {
                    sb.append(t);
                    break;
                }
            }
            p.waitFor();
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "读取公钥失败", e);
            return null;
        }
    }

    // ---------- 执行 ----------
    /**
     * 执行用户输入的 rsync 命令。命令经分词后直接 exec（不经 shell）。
     * 首参必须是 rsync（允许 ./rsync 等形态）。
     *
     * 自动修正：
     *  - rsync 的 -e/--rsh 里如果写的是 "ssh ..."，把首词换成 nativeLibraryDir/libssh.so
     *    绝对路径（filesDir 软链接在部分机型 SELinux 下仍不可 exec，见 v1.0.13 真机 error=13）；
     *  - 无 -e/--rsh 时自动补 -e libssh.so；
     *  - ssh 参数自动补 -y（跳过 hostkey 确认）与 -i 生效私钥（避免交互卡住）；
     *  - password 非空时通过环境变量 DROPBEAR_PASSWORD 传给 dbclient（dropbear 原生支持）。
     */
    public void run(String commandLine, String password, OutputListener listener)
            throws IOException {
        if (process != null && process.isAlive()) {
            throw new IOException("已有任务在运行，请先停止");
        }
        List<String> argv = ShellTokenizer.tokenize(commandLine);
        if (argv == null || argv.isEmpty()) {
            throw new IOException("命令为空或引号未闭合");
        }
        String prog = new File(argv.get(0)).getName();
        if (!"rsync".equals(prog) && !"rsync.exe".equals(prog)
                && !"librsync.so".equals(prog)) {
            throw new IOException("命令必须以 rsync 开头（其他命令不被允许）");
        }
        argv.set(0, rsyncPath()); // 用内置 rsync 二进制执行

        // ---- 预处理 -e/--rsh，把 ssh 指向内置 libssh.so ----
        boolean hasRsh = false;
        for (int i = 1; i < argv.size(); i++) {
            String a = argv.get(i);
            if ("-e".equals(a) || "--rsh".equals(a)) {
                if (i + 1 < argv.size()) {
                    argv.set(i + 1, rewriteSsh(argv.get(i + 1)));
                    hasRsh = true;
                }
            }
        }
        if (!hasRsh) {
            // rsync 默认 rsh=ssh，但 PATH 里没有可执行的 ssh：显式补 -e
            argv.add(1, "-e");
            argv.add(2, nativeLibDir() + "/libssh.so");
        }

        // ---- 远程主机参数：域名 → IP（v1.0.19）----
        // 静态 musl 二进制（dbclient）不走 Android 系统 DNS，解析域名会失败；
        // 由 App 端（Java，走系统解析器）把 user@host:path / host:path 中的域名换成 IP。
        for (int i = 1; i < argv.size(); i++) {
            String tok = argv.get(i);
            if (isRemotePathToken(tok)) {
                argv.set(i, resolveRemoteHost(tok));
            }
        }

        List<String> cmd = new ArrayList<>(argv);
        Log.i(TAG, "exec: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.put("HOME", ctx.getFilesDir().getAbsolutePath());
        if (password != null && !password.isEmpty()) {
            env.put("DROPBEAR_PASSWORD", password);
        }
        // 工作目录=共享根，与 SFTP 服务一致
        Settings s = Settings.load(ctx);
        File cwd = new File(s.rootPath);
        if (cwd.isDirectory()) {
            pb.directory(cwd);
        }
        process = pb.start();
        // v1.0.20：同步期间持锁，防止放后台/息屏时 CPU 休眠挂起 rsync 进程导致 TCP 超时断开
        acquireSyncLocks();

        Thread out = pump(process.getInputStream(), listener);
        Thread err = pump(process.getErrorStream(), listener);
        Thread waiter = new Thread(() -> {
            try {
                int code = process.waitFor();
                out.join(1000);
                err.join(1000);
                listener.onExit(code, code == 0 ? "完成（exit 0）" : "失败（exit " + code + "）");
            } catch (Exception e) {
                listener.onExit(-1, e.toString());
            } finally {
                process = null;
                releaseSyncLocks();
            }
        }, "rsync-client-waiter");
        waiter.start();
    }

    /** 判定是否为远程主机路径参数：含 ':'，且不含空格，且要么含 '@' 要么首个 ':' 之前不含 '/' */
    static boolean isRemotePathToken(String tok) {
        if (tok == null || tok.isEmpty() || tok.indexOf(' ') >= 0) {
            return false;
        }
        if (tok.indexOf(':') < 0) {
            return false;
        }
        if (tok.indexOf('@') >= 0) {
            return true;
        }
        int colon = tok.indexOf(':');
        return colon >= 0 && tok.substring(0, colon).indexOf('/') < 0;
    }

    /** 把 user@host:path / host:path 中的域名解析为 IP（已解析过的或 IP 字面量则原样返回） */
    static String resolveRemoteHost(String token) throws IOException {
        String rest = token;
        String prefix = "";
        int at = token.lastIndexOf('@');
        if (at >= 0) {
            prefix = token.substring(0, at + 1);
            rest = token.substring(at + 1);
        }
        int colon = rest.indexOf(':');
        if (colon < 0) {
            return token; // 不是远程路径，不动
        }
        String host = rest.substring(0, colon);
        if (host.isEmpty() || host.startsWith("[")) {
            return token; // 空 host 或 IPv6 字面量，不动
        }
        if (host.matches("[0-9.]+") || host.equalsIgnoreCase("localhost")) {
            // IPv4 字面量 / localhost 无需处理
            return token;
        }
        try {
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
            String ip = null;
            for (java.net.InetAddress a : addrs) {
                if (a instanceof java.net.Inet4Address) {
                    ip = a.getHostAddress();
                    break;
                }
            }
            if (ip == null && addrs.length > 0) {
                ip = addrs[0].getHostAddress();
            }
            if (ip != null) {
                Log.i(TAG, "resolve " + host + " -> " + ip);
                return prefix + ip + rest.substring(colon);
            }
            // 解析到了地址但没有 IPv4——不要报错，fallback 保留原始域名
            Log.w(TAG, "resolve " + host + ": no IPv4 address, fallback to original hostname");
            return token;
        } catch (Exception e) {
            // v1.0.24：不要直接报错退出——打印异常类型后 fallback 保留原始域名
            // 万一 dbclient 自己能解析（某些网络环境下 musl 也能读 /etc/resolv.conf）
            Log.w(TAG, "resolve " + host + " failed: " + e.getClass().getName() + ": " + e.getMessage()
                    + ", fallback to original hostname");
            return token;
        }
    }

    /** 重写 rsh 字符串：ssh → nativeLibraryDir/libssh.so 绝对路径，并自动补 -y / -i / -W / -c */
    String rewriteSsh(String rsh) {
        if (rsh == null) {
            return null;
        }
        String t = rsh.trim();
        if (t.isEmpty()) {
            return rsh;
        }
        String[] parts = t.split("\\s+");
        if (!"ssh".equals(parts[0])) {
            // 非 ssh（如用户已写绝对路径）原样返回
            return rsh;
        }
        StringBuilder sb = new StringBuilder(nativeLibDir()).append("/libssh.so");
        boolean hasY = false, hasI = false, hasW = false, hasC = false;
        for (int i = 1; i < parts.length; i++) {
            if ("-y".equals(parts[i])) {
                hasY = true;
            }
            if ("-i".equals(parts[i])) {
                hasI = true;
            }
            if ("-W".equals(parts[i])) {
                hasW = true;
            }
            if ("-c".equals(parts[i])) {
                hasC = true;
            }
        }
        if (!hasY) {
            sb.append(" -y");
        }
        // v1.0.21：大接收窗口（默认 24KB 在移动网络高延迟下把吞吐锁死在 ~500KB/s；
        // 调到 4MB 后，RTT 50ms 时理论吞吐 ≈ 4MB/0.05s = 80MB/s，跑满带宽不再被窗口卡住）
        if (!hasW) {
            sb.append(" -W 4194304");
        }
        // v1.0.21：优先 chacha20-poly1305（纯软件加密在 ARM64 上比软 AES-CTR 快约 60%+）
        if (!hasC) {
            sb.append(" -c chacha20-poly1305@openssh.com");
        }
        File key = new File(getActiveKeyPath());
        if (!hasI && key.exists()) {
            sb.append(" -i ").append(key.getAbsolutePath());
        }
        for (int i = 1; i < parts.length; i++) {
            sb.append(' ').append(parts[i]);
        }
        Log.i(TAG, "rsh -> " + sb);
        return sb.toString();
    }

    // ---------- 命令历史（最近 10 条） ----------
    public static final String PREFS = "rsync_history";
    private static final int MAX_HISTORY = 10;

    /** 记录一条命令，去重后置顶，最多保留 MAX_HISTORY 条 */
    public void addHistory(String command) {
        if (command == null || command.trim().isEmpty()) {
            return;
        }
        try {
            android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String json = p.getString("list", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(json);
            org.json.JSONArray out = new org.json.JSONArray();
            out.put(command);
            for (int i = 0; i < arr.length() && out.length() < MAX_HISTORY; i++) {
                String s = arr.optString(i);
                if (!command.equals(s)) {
                    out.put(s);
                }
            }
            p.edit().putString("list", out.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "history save failed", e);
        }
    }

    /** 返回历史命令（新 → 旧），空则返回空数组 */
    public java.util.List<String> getHistory() {
        java.util.List<String> list = new ArrayList<>();
        try {
            android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String json = p.getString("list", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.optString(i));
            }
        } catch (Exception e) {
            Log.w(TAG, "history load failed", e);
        }
        return list;
    }

    public void stop() {
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
            Log.i(TAG, "rsync client stopped by user");
        }
        releaseSyncLocks();
    }

    /**
     * v1.0.20：同步期间持有 CPU 唤醒锁 + WiFi 高性能锁。
     *  不持锁时，App 切后台/息屏后系统会挂起 CPU（rsync native 进程被冻结），
     *  远端在等数据 → TCP 超时 → "connection unexpectedly closed"。
     *  持锁后 CPU 与 WiFi 在息屏期间保持活跃，同步可正常跑完；任务结束/停止即释放，不影响日常省电。
     */
    private synchronized void acquireSyncLocks() {
        // v1.0.25：启动前台服务通知——这是防止后台/锁屏后系统限制网络的关键。
        // 仅靠 WakeLock+WifiLock 不够：Doze 模式下即使 CPU 醒着，蜂窝/WiFi 网络也会被暂停，
        // 导致 TCP 连接 abort。前台服务让系统知道 App 在做用户可见的数据传输，不限制网络。
        try {
            RsyncClientService.start(ctx);
        } catch (Throwable t) {
            Log.e(TAG, "start rsync foreground service failed", t);
        }
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) ctx.getApplicationContext()
                        .getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LanSftp:rsync-sync");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire();
                Log.i(TAG, "wakeLock acquired");
            }
        } catch (Throwable t) {
            Log.e(TAG, "acquire wakeLock failed", t);
        }
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) ctx.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LanSftp:rsync-wifi");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
                Log.i(TAG, "wifiLock acquired");
            }
        } catch (Throwable t) {
            Log.e(TAG, "acquire wifiLock failed", t);
        }
    }

    private synchronized void releaseSyncLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.i(TAG, "wakeLock released");
            }
        } catch (Throwable t) {
            Log.e(TAG, "release wakeLock failed", t);
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                Log.i(TAG, "wifiLock released");
            }
        } catch (Throwable t) {
            Log.e(TAG, "release wifiLock failed", t);
        }
        // 停止前台服务通知
        try {
            RsyncClientService.stop(ctx);
        } catch (Throwable t) {
            Log.e(TAG, "stop rsync foreground service failed", t);
        }
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    private Thread pump(java.io.InputStream src, OutputListener listener) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(src, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    listener.onLine(line);
                }
            } catch (IOException ignored) {
            }
        }, "rsync-client-pump");
        t.start();
        return t;
    }

    private void runAndWait(String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            while ((line = r.readLine()) != null) {
                sb.append(line).append("\n");
            }
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("命令失败 code=" + code + ": " + sb);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断");
        }
    }
}
