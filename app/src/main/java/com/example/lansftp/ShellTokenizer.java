package com.example.lansftp;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 POSIX shell 风格分词器：把一行命令拆成 argv 数组（支持单引号/双引号/反斜杠转义），
 * 供 ProcessBuilder 直接执行，不经过 shell，避免 shell 元字符注入。
 */
public class ShellTokenizer {

    /**
     * 把命令行拆成参数列表。无法闭合的引号返回 null（视为非法输入）。
     */
    public static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inWord = false;
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (inWord) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inWord = false;
                }
                i++;
                continue;
            }
            if (c == '\'') {
                // 单引号：内部原样
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < n && line.charAt(j) != '\'') {
                    sb.append(line.charAt(j));
                    j++;
                }
                if (j >= n) {
                    return null; // 引号未闭合
                }
                cur.append(sb);
                inWord = true;
                i = j + 1;
                continue;
            }
            if (c == '"') {
                // 双引号：支持反斜杠转义 \" \\ \$ \` 与空白（其他保留）
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < n && line.charAt(j) != '"') {
                    char d = line.charAt(j);
                    if (d == '\\' && j + 1 < n) {
                        char e = line.charAt(j + 1);
                        if (e == '"' || e == '\\' || e == '$' || e == '`') {
                            sb.append(e);
                            j += 2;
                            continue;
                        }
                    }
                    sb.append(d);
                    j++;
                }
                if (j >= n) {
                    return null; // 引号未闭合
                }
                cur.append(sb);
                inWord = true;
                i = j + 1;
                continue;
            }
            if (c == '\\') {
                if (i + 1 < n) {
                    cur.append(line.charAt(i + 1));
                    i += 2;
                } else {
                    cur.append('\\');
                    i++;
                }
                inWord = true;
                continue;
            }
            cur.append(c);
            inWord = true;
            i++;
        }
        if (inWord) {
            out.add(cur.toString());
        }
        return out;
    }
}
