package com.example.lansftp;

import android.app.Application;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局未捕获异常处理：把崩溃堆栈写入可访问的日志文件，
 * 便于无法连接 adb 时也能排查问题。
 * 日志位置：/storage/emulated/0/Download/LanSftpServer_crash.log
 *          （写失败时退回应用私有目录 files/crash.log）
 */
public class App extends Application {

    private static final String TAG = "LanSftpCrash";
    private static final String CRASH_FILE_NAME = "LanSftpServer_crash.log";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrashLog(thread, throwable);
            } catch (Exception ignored) {
            }
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }

    private void writeCrashLog(Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("===== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + " =====");
        pw.println("thread: " + thread.getName());
        throwable.printStackTrace(pw);
        pw.println();
        pw.flush();
        String content = sw.toString();
        Log.e(TAG, content);

        File target = null;
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null) {
                File f = new File(dir, CRASH_FILE_NAME);
                if (dir.exists() || dir.mkdirs()) {
                    appendTo(f, content);
                    target = f;
                }
            }
        } catch (Exception ignored) {
        }
        if (target == null) {
            try {
                appendTo(new File(getFilesDir(), "crash.log"), content);
            } catch (Exception ignored) {
            }
        }
    }

    private void appendTo(File file, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            fos.write(content.getBytes("UTF-8"));
        }
    }
}
