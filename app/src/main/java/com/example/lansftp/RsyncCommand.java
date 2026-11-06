package com.example.lansftp;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 响应 SSH exec channel 上的 rsync --server 命令：
 * 校验命令合法性 → 把远程路径映射到共享根（并拒绝越权）→ 以共享根为工作目录
 * 执行打包的静态 rsync 二进制，三路转发 stdin/stdout/stderr 与客户端通信。
 *
 * 客户端用法（电脑上）：
 *   rsync -av -e "ssh -p 端口 用户名@手机IP" 本地目录/ 用户名@手机IP:/目标目录/
 */
public class RsyncCommand implements Command {
    private final String fullCommand;
    private final String sharedRoot;
    private final String rsyncPath;

    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback exitCallback;
    private Process process;

    public RsyncCommand(String fullCommand, String sharedRoot, String rsyncPath) {
        this.fullCommand = fullCommand;
        this.sharedRoot = sharedRoot;
        this.rsyncPath = rsyncPath;
    }

    /**
     * 命令白名单：必须是 "rsync --server" 开头，且不含任何 shell 元字符
     * （防注入；rsync 客户端正常生成的 --server 命令不含这些字符）。
     */
    public static boolean isSafeRsyncCommand(String command) {
        if (command == null || !command.startsWith("rsync --server")) {
            return false;
        }
        for (char c : command.toCharArray()) {
            if (";|&<>$`\"'\n\r".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把 rsync --server 命令中的远程路径（最后一个参数）映射到共享根内，
     * 并拒绝任何试图逃逸共享根（..）的路径。返回 null 表示拒绝执行。
     *
     * 注意保留 rsync 的尾斜杠语义：/a/b/ 表示同步目录内容，/a/b 表示同步目录本身。
     * Path.normalize() 会去掉末尾斜杠，映射后必须按原参数补回，否则下载场景会把
     * "同步 v1.0.9 目录里的文件" 变成 "把 v1.0.9 整个目录同步下来"。
     */
    static String mapRemotePath(String command, String sharedRoot) {
        String cmd = command.trim();
        int lastSpace = cmd.lastIndexOf(' ');
        String lastToken = lastSpace >= 0 ? cmd.substring(lastSpace + 1) : "";
        if (lastToken.isEmpty() || lastToken.equals(".")) {
            return cmd; // 无路径参数或为模块标记，交给 CWD 处理
        }
        if (lastToken.contains("\\") || lastToken.contains("'") || lastToken.contains("\"")) {
            return null; // 转义/引号路径暂不支持，拒绝
        }
        if (!lastToken.startsWith("/")) {
            return cmd; // 相对路径：交给 CWD（共享根）处理
        }
        boolean trailingSlash = lastToken.endsWith("/");
        Path rootAbs = Paths.get(sharedRoot).toAbsolutePath().normalize();
        Path real = Paths.get(sharedRoot + lastToken).toAbsolutePath().normalize();
        if (!real.startsWith(rootAbs)) {
            return null; // 越权
        }
        String mapped = real.toString();
        if (trailingSlash && !mapped.endsWith("/")) {
            mapped = mapped + "/"; // 补回被 normalize 吞掉的尾斜杠，保留 rsync 目录内容语义
        }
        return cmd.substring(0, lastSpace + 1) + mapped;
    }

    @Override
    public void setInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
        this.err = err;
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.exitCallback = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment env) throws IOException {
        String mapped = mapRemotePath(fullCommand, sharedRoot);
        if (mapped == null) {
            if (exitCallback != null) {
                exitCallback.onExit(1, "path not allowed");
            }
            return;
        }
        // 经 shell 执行，让 rsync 客户端发送的转义路径（如 \ 空格）被正确解析；
        // 命令已通过 isSafeRsyncCommand 校验，不含 shell 元字符。
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c",
                rsyncPath + " " + mapped.substring("rsync".length()));
        pb.redirectErrorStream(false);
        pb.directory(new File(sharedRoot)); // 工作目录=共享根，处理相对路径
        try {
            process = pb.start();
        } catch (IOException e) {
            // 启动失败：把确切原因写给客户端 stderr，便于真机排查
            String reason = "rsync failed to start: " + e.getMessage() + " (" + rsyncPath + ")";
            try {
                if (err != null) {
                    err.write((reason + "\n").getBytes("UTF-8"));
                    err.flush();
                }
            } catch (IOException ignored) {
            }
            if (exitCallback != null) {
                exitCallback.onExit(1, reason);
            }
            return;
        }

        Thread t1 = new Thread(() -> pump(in, process.getOutputStream(), true), "rsync-in");
        Thread t2 = new Thread(() -> pump(process.getInputStream(), out, false), "rsync-out");
        Thread t3 = new Thread(() -> pump(process.getErrorStream(), err, false), "rsync-err");
        t1.start();
        t2.start();
        t3.start();

        Thread waiter = new Thread(() -> {
            try {
                int code = process.waitFor();
                // 等待转发线程把残留数据写完（短暂）
                t2.join(2000);
                t3.join(2000);
                if (exitCallback != null) {
                    exitCallback.onExit(code, code == 0 ? "" : "rsync exited with " + code);
                }
            } catch (Exception e) {
                if (exitCallback != null) {
                    exitCallback.onExit(-1, e.toString());
                }
            }
        }, "rsync-waiter");
        waiter.start();
    }

    private static void pump(InputStream src, OutputStream dst, boolean closeDstOnEnd) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = src.read(buf)) >= 0) {
                if (n == 0) {
                    continue;
                }
                dst.write(buf, 0, n);
                dst.flush();
            }
        } catch (IOException ignored) {
        } finally {
            if (closeDstOnEnd) {
                try {
                    dst.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public void destroy(ChannelSession channel) {
        if (process != null) {
            process.destroy();
        }
    }
}
