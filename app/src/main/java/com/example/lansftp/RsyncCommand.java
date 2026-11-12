package com.example.lansftp;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 响应 SSH exec channel 上的 rsync --server 命令：
 * 校验命令合法性 → 把远程路径映射到共享根（并拒绝越权）→ 以共享根为工作目录
 * 执行打包的静态 rsync 二进制，三路转发 stdin/stdout/stderr 与客户端通信。
 *
 * 客户端用法（电脑上）：
 *   rsync -av -e "ssh -p 端口 用户名@手机IP" 本地目录/ 用户名@手机IP:/目标目录/
 *
 * v1.0.29：命令解析改为完整 POSIX 词法拆解（见 {@link RsyncServerArgs}），
 * 支持带空格的远程路径（rsync 会把它转义成 /a/b\ c\ d 的形式），并且不再经过 sh -c 执行，
 * 彻底避免空格/引号导致的路径截断与注入风险。
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
     * 命令白名单：经 POSIX 词法解析后必须是 rsync --server 形式。
     * 因为执行时不再经过 shell，路径里的空格、引号、反斜杠都只是普通字符，无需禁用。
     */
    public static boolean isSafeRsyncCommand(String command) {
        return RsyncServerArgs.parse(command) != null;
    }

    /**
     * 把 rsync --server 命令中的远程路径参数映射到共享根内，
     * 并拒绝任何试图逃逸共享根（..）的路径。返回 null 表示拒绝执行。
     *
     * 支持带空格路径（rsync 客户端会用反斜杠转义空格），也保留尾斜杠语义。
     */
    static List<String> mapCommand(String command, String sharedRoot) {
        return RsyncServerArgs.mapPaths(RsyncServerArgs.parse(command), sharedRoot);
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
        List<String> mapped = mapCommand(fullCommand, sharedRoot);
        if (mapped == null || mapped.isEmpty()) {
            if (exitCallback != null) {
                exitCallback.onExit(1, "path not allowed");
            }
            return;
        }
        // v1.0.29：直接以 argv 执行，不经过 sh -c。
        // argv 已由 POSIX 词法解析还原（空格/引号/反斜杠都已解开），
        // 因此带空格的路径是一个完整参数，不会被 shell 再拆开；也彻底排除注入。
        List<String> cmd = new ArrayList<>(mapped);
        cmd.set(0, rsyncPath);
        ProcessBuilder pb = new ProcessBuilder(cmd);
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
