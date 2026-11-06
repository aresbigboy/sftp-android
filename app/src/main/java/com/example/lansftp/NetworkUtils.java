package com.example.lansftp;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 获取本机局域网 IPv4 地址，用于展示连接地址 */
public class NetworkUtils {

    /**
     * 当前首选 IPv4 地址（保持与旧版一致的单个返回），用于通知栏简要展示。
     * 首选规则：跳过 loopback/未启用/虚拟接口后取第一个非 127 的 IPv4。
     */
    public static String getLocalIPv4() {
        List<String> all = getAllLocalIPv4s();
        return all.isEmpty() ? "未知" : all.get(0);
    }

    /**
     * 枚举本机全部可达的 IPv4 地址（排除 127.0.0.1/loopback 与未启用接口），
     * 覆盖 WiFi / 热点 / USB 共享 / VPN 等所有网卡。返回结果已去重并按字典序排序。
     */
    public static List<String> getAllLocalIPv4s() {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()
                            && !a.isAnyLocalAddress() && !a.isLinkLocalAddress()) {
                        String ip = a.getHostAddress();
                        if (seen.add(ip)) {
                            out.add(ip);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 忽略，返回已有结果
        }
        Collections.sort(out);
        return out;
    }

    /**
     * 枚举本机全部有效的公网 IPv6 地址（v1.0.22）。
     * 过滤规则：
     *  - 跳过 loopback（::1）、链路本地（fe80::/10）、唯一本地地址（fc00::/7，即 ULA）
     *  - 只保留真正的公网 IPv6（2000::/3 段）
     *  - 覆盖 WiFi / 蜂窝网络等所有网卡
     */
    public static List<String> getPublicIPv6s() {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet6Address)) {
                        continue;
                    }
                    if (a.isLoopbackAddress() || a.isAnyLocalAddress()
                            || a.isLinkLocalAddress() || a.isSiteLocalAddress()) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    // 去掉 % 后缀（zone id，如 fe80::1%wlan0）
                    int pct = ip.indexOf('%');
                    if (pct >= 0) {
                        ip = ip.substring(0, pct);
                    }
                    // 进一步过滤 ULA（fc00::/7）和非公网段
                    if (isPublicIPv6(ip) && seen.add(ip)) {
                        out.add(ip);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out);
        return out;
    }

    /** 判断是否为有效的公网 IPv6（2000::/3 段） */
    private static boolean isPublicIPv6(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        String lower = ip.toLowerCase();
        // 排除已知的非公网段
        if (lower.startsWith("fe80") || lower.startsWith("fec0") || lower.startsWith("fc")
                || lower.startsWith("fd") || lower.startsWith("::1")) {
            return false;
        }
        // 公网 IPv6 在 2000::/3 段（即首字节 0x20-0x3F）
        // 取第一个冒号前的段，解析为十六进制
        String firstSeg = lower.split(":")[0];
        try {
            int first = Integer.parseInt(firstSeg, 16);
            return (first & 0xe000) == 0x2000; // 2000::/3
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 生成"连接地址"文本：本机所有 IPv4 + 公网 IPv6 各一行，供主界面和通知栏展示。
     * IPv6 地址用方括号包裹（sftp://user@[ipv6]:port）。
     */
    public static String buildConnectionAddressText(String username, int port) {
        List<String> ips = getAllLocalIPv4s();
        List<String> v6s = getPublicIPv6s();
        StringBuilder sb = new StringBuilder();
        if (ips.isEmpty() && v6s.isEmpty()) {
            sb.append("连接地址：sftp://").append(username).append("@未知:").append(port);
        } else {
            for (String ip : ips) {
                sb.append("连接地址：sftp://").append(username).append("@").append(ip)
                        .append(":").append(port).append("\n");
            }
            for (String ip : v6s) {
                sb.append("连接地址：sftp://").append(username).append("@[").append(ip)
                        .append("]:").append(port).append("\n");
            }
            sb.setLength(sb.length() - 1); // 去掉末尾换行
        }
        return sb.toString();
    }
}
