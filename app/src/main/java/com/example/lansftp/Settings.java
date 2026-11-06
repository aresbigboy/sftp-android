package com.example.lansftp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 服务器配置的持久化存储 */
public class Settings {
    private static final String PREFS = "sftp_settings";
    private static final String TAG = "Settings";

    public String username;
    public String password;
    public int port;
    public String rootPath;
    /** 客户端公钥列表（每行一个 ssh-rsa/ssh-ed25519 公钥），用于免密登录 */
    public String authorizedKeys;

    public static Settings load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Settings s = new Settings();
        s.username = sp.getString("username", "admin");
        s.password = sp.getString("password", "123456");
        s.port = sp.getInt("port", 2222);
        s.rootPath = sp.getString("rootPath", Environment.getExternalStorageDirectory().getAbsolutePath());
        s.authorizedKeys = sp.getString("authorizedKeys", "");
        // prefs 为空但私有文件里有公钥时（历史写文件模式/进程被杀导致 apply 未落盘），以文件为准
        if ((s.authorizedKeys == null || s.authorizedKeys.trim().isEmpty()) && readKeysFile(ctx) != null) {
            s.authorizedKeys = readKeysFile(ctx);
        }
        return s;
    }

    public void save(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("username", username)
                .putString("password", password)
                .putInt("port", port)
                .putString("rootPath", rootPath)
                .putString("authorizedKeys", authorizedKeys == null ? "" : authorizedKeys)
                .apply();
    }

    /** 同步持久化客户端公钥：prefs 用 commit（同步落盘，避免杀进程丢数据）+ 私有文件 fsync 双写 */
    public void saveAuthorizedKeysSync(Context ctx, String keys) {
        this.authorizedKeys = keys == null ? "" : keys;
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString("authorizedKeys", authorizedKeys)
                    .commit();
            writeKeysFile(ctx, authorizedKeys);
        } catch (Exception e) {
            Log.e(TAG, "saveAuthorizedKeysSync failed", e);
        }
    }

    /** 认证时实时读取公钥：优先读私有文件（最新同步落盘），空则回退 prefs */
    public static String readAuthorizedKeys(Context ctx) {
        String fromFile = readKeysFile(ctx);
        if (fromFile != null && !fromFile.trim().isEmpty()) {
            return fromFile;
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getString("authorizedKeys", "");
    }

    private static File keysFile(Context ctx) {
        return new File(ctx.getFilesDir(), "authorized_keys");
    }

    private static String readKeysFile(Context ctx) {
        File f = keysFile(ctx);
        if (!f.exists()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 65536)];
            int n = in.read(buf);
            if (n <= 0) {
                return null;
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.e(TAG, "readKeysFile failed", e);
            return null;
        }
    }

    /** 同步写文件并 fsync，保证进程被杀后公钥仍在磁盘上 */
    private static void writeKeysFile(Context ctx, String keys) {
        File f = keysFile(ctx);
        File tmp = new File(ctx.getFilesDir(), "authorized_keys.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(keys.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            Log.e(TAG, "writeKeysFile failed", e);
            return;
        }
        if (!tmp.renameTo(f)) {
            Log.e(TAG, "rename authorized_keys.tmp -> authorized_keys failed");
        }
    }
}
