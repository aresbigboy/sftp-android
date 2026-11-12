package com.example.lansftp;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 rsync over SSH 命令解析与路径映射（纯逻辑，无 Android/SSHD 依赖，便于单测）。
 *
 * 背景（v1.0.29）：
 * rsync 客户端把远程路径交给远端 shell 时，会对含空格的路径做反斜杠转义，例如
 *   rsync --server --sender -vlogDtpre.iLsfxC . /Download/PC/DRAGON\ QUEST\ VII\ Reimagined
 * 旧实现按"最后一个空格"取路径 token，遇到转义空格就会只取到 "Reimagined"，
 * 导致判定为非绝对路径 → 跳过共享根映射 → 原始绝对路径交给 rsync，必然
 *   change_dir "/Download/PC" failed: No such file or directory
 *
 * 现在改为：先用 POSIX 词法规则把整条命令拆成 argv（反斜杠转义、单/双引号都还原），
 * 再对 "." 之后的每个路径参数单独做共享根映射（保留 rsync 的尾斜杠语义），
 * 最后直接以 argv 数组执行，不再经过 sh，路径里的空格/引号因此完全安全。
 */
public class RsyncServerArgs {

    private RsyncServerArgs() {
    }

    /**
     * 解析 SSH exec 收到的远端命令。返回 null 表示不是可执行的白名单 rsync --server 命令。
     */
    public static List<String> parse(String command) {
        if (command == null) {
            return null;
        }
        List<String> argv = ShellTokenizer.tokenize(command.trim());
        if (argv == null || argv.isEmpty()) {
            return null;
        }
        // 只允许 rsync 二进制（兼容客户端传 "/usr/bin/rsync" 这类绝对路径写法）
        String prog = argv.get(0);
        int slash = prog.lastIndexOf('/');
        if (slash >= 0) {
            prog = prog.substring(slash + 1);
        }
        if (!"rsync".equals(prog)) {
            return null;
        }
        if (argv.size() < 2 || !"--server".equals(argv.get(1))) {
            return null;
        }
        return argv;
    }

    /**
     * 把 "." 之后的绝对路径参数映射到共享根内；相对路径原样保留（由工作目录=共享根兜底）。
     * 任何试图逃逸共享根的路径返回 null（拒绝执行）。
     *
     * 尾斜杠语义：rsync 用 /a/b/ 表示"同步目录内容"、/a/b 表示"同步目录本身"。
     * Path.normalize() 会吞掉尾斜杠，必须按原参数补回。
     */
    public static List<String> mapPaths(List<String> argv, String sharedRoot) {
        if (argv == null) {
            return null;
        }
        int dot = -1;
        for (int i = 0; i < argv.size(); i++) {
            if (".".equals(argv.get(i))) {
                dot = i;
                break;
            }
        }
        // 没有 "." 标记（少见）时不改写，交给 rsync 自行处理
        if (dot < 0) {
            return new ArrayList<>(argv);
        }
        Path rootAbs = Paths.get(sharedRoot).toAbsolutePath().normalize();
        List<String> out = new ArrayList<>(argv);
        for (int i = dot + 1; i < out.size(); i++) {
            String p = out.get(i);
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (!p.startsWith("/")) {
                continue; // 相对路径：交给 CWD（共享根）处理
            }
            boolean trailingSlash = p.endsWith("/");
            Path real;
            try {
                real = Paths.get(sharedRoot + p).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                return null; // 非法路径字符
            }
            if (!real.startsWith(rootAbs)) {
                return null; // 越权：/../ 逃逸共享根
            }
            String mapped = real.toString();
            if (trailingSlash && !mapped.endsWith("/")) {
                mapped = mapped + "/"; // 补回被 normalize 吞掉的尾斜杠
            }
            out.set(i, mapped);
        }
        return out;
    }

    /** 一步完成：解析 + 映射。返回 null 表示拒绝。 */
    public static List<String> parseAndMap(String command, String sharedRoot) {
        return mapPaths(parse(command), sharedRoot);
    }
}
