package com.example.lansftp;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * rsync 命令行里远程路径参数（host:path / user@host:path）的识别与域名→IP 替换（纯 JDK 逻辑，便于单测）。
 *
 * 背景：内置静态 musl 的 dbclient 不走 Android 系统 DNS，域名必须由 App 端 Java 解析后替换成 IP。
 * v1.0.29 修正：旧实现遇到带空格的 token 直接返回 false，导致
 *   user@host:/dir with space/
 * 这种"域名 + 带空格路径"的组合跳过解析，退化成"域名解析失败"。
 */
public class RemotePathTokens {

    private RemotePathTokens() {
    }

    /** 解析结果：token 为替换后的参数（未变更时与入参相同） */
    public static class Result {
        public final String token;   // 替换（或原样）后的参数
        public final String host;    // 识别出的主机名（null 表示不是远程路径）
        public final String ip;      // 解析到的 IP（未解析/无需解析时为 null）
        public final boolean changed;

        Result(String token, String host, String ip, boolean changed) {
            this.token = token;
            this.host = host;
            this.ip = ip;
            this.changed = changed;
        }
    }

    /**
     * 判定是否为远程主机路径参数：含 ':'，且要么含 '@'，要么首个 ':' 之前不含 '/'（排除本地绝对路径）。
     * 注意：路径里允许出现空格（rsync 会把整个路径作为单个参数交给远端）。
     */
    public static boolean isRemotePathToken(String tok) {
        if (tok == null || tok.isEmpty()) {
            return false;
        }
        if (tok.indexOf(':') < 0) {
            return false;
        }
        if (tok.indexOf('@') >= 0) {
            return true;
        }
        int colon = tok.indexOf(':');
        return tok.substring(0, colon).indexOf('/') < 0;
    }

    /** 把 user@host:path / host:path 中的域名解析为 IP（IP 字面量、IPv6、localhost 原样返回） */
    public static Result resolve(String token) {
        String rest = token;
        String prefix = "";
        int at = token.lastIndexOf('@');
        if (at >= 0) {
            prefix = token.substring(0, at + 1);
            rest = token.substring(at + 1);
        }
        int colon = rest.indexOf(':');
        if (colon < 0) {
            return new Result(token, null, null, false);
        }
        String host = rest.substring(0, colon);
        // host 本身含空格或为空、或为 IPv6 字面量：不做替换
        if (host.isEmpty() || host.indexOf(' ') >= 0 || host.startsWith("[")) {
            return new Result(token, host.isEmpty() ? null : host, null, false);
        }
        if (host.matches("[0-9.]+") || host.equalsIgnoreCase("localhost")) {
            return new Result(token, host, null, false);
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            String ip = null;
            for (InetAddress a : addrs) {
                if (a instanceof Inet4Address) {
                    ip = a.getHostAddress();
                    break;
                }
            }
            if (ip == null && addrs.length > 0) {
                ip = addrs[0].getHostAddress();
            }
            if (ip == null) {
                return new Result(token, host, null, false); // 无地址，保留原始域名
            }
            // rest.substring(colon) 含 ":path"，原样保留（路径里的空格不受影响）
            return new Result(prefix + ip + rest.substring(colon), host, ip, true);
        } catch (Exception e) {
            // 解析失败：保留原始域名（万一 dbclient 自己能解析）
            return new Result(token, host, null, false);
        }
    }
}
