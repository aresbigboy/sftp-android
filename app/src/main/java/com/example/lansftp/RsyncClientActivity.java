package com.example.lansftp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** rsync 客户端命令窗口：输入 rsync 命令，从远程同步文件到本机 */
public class RsyncClientActivity extends Activity {

    private static final int REQ_PICK_KEY = 1001;

    private final Handler main = new Handler(Looper.getMainLooper());

    private RsyncClientRunner runner;
    private EditText etCommand;
    private EditText etImportKey;
    private EditText etPassword;
    private TextView tvOutput;
    private TextView tvPubkey;
    private TextView tvKeyState;
    private Button btnRun;
    private Button btnStop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rsync_client);

        etCommand = findViewById(R.id.etCommand);
        etImportKey = findViewById(R.id.etImportKey);
        etPassword = findViewById(R.id.etPassword);
        tvOutput = findViewById(R.id.tvOutput);
        // v1.0.15 方案：TextView 内置滚动（maxHeight 240dp），配合 appendOutput 的 scrollTo 自动滚到底
        tvOutput.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        tvOutput.setVerticalScrollBarEnabled(true);
        tvPubkey = findViewById(R.id.tvPubkey);
        tvKeyState = findViewById(R.id.tvKeyState);
        btnRun = findViewById(R.id.btnRun);
        btnStop = findViewById(R.id.btnStop);
        Button btnCopyKey = findViewById(R.id.btnCopyKey);
        Button btnExample = findViewById(R.id.btnExample);
        Button btnImport = findViewById(R.id.btnImport);
        Button btnImportFile = findViewById(R.id.btnImportFile);
        Button btnResetKey = findViewById(R.id.btnResetKey);
        Button btnHistory = findViewById(R.id.btnHistory);

        runner = new RsyncClientRunner(this);
        prepare();

        btnRun.setOnClickListener(v -> runCommand());
        btnStop.setOnClickListener(v -> {
            runner.stop();
            appendOutput("== 已发送停止信号 ==");
        });
        btnCopyKey.setOnClickListener(v -> {
            String pub = tvPubkey.getText().toString();
            if (pub.startsWith("ssh-")) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("rsync pubkey", pub));
                toast("公钥已复制");
            } else {
                toast("公钥还未就绪");
            }
        });
        btnExample.setOnClickListener(v -> fillExample());
        btnImport.setOnClickListener(v -> importFromText());
        btnImportFile.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(i, REQ_PICK_KEY);
            } catch (Exception e) {
                toast("无法打开文件选择器");
            }
        });
        btnResetKey.setOnClickListener(v -> {
            runner.resetToBuiltinKey();
            refreshKeyUi("已恢复内置密钥");
        });
        btnHistory.setOnClickListener(v -> showHistory());
    }

    /** 弹出历史命令列表（新 → 旧），点击填入输入框 */
    private void showHistory() {
        java.util.List<String> hist = runner.getHistory();
        if (hist.isEmpty()) {
            toast("暂无历史命令");
            return;
        }
        String[] items = hist.toArray(new String[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle("最近命令（点选填入）")
                .setItems(items, (d, which) -> etCommand.setText(items[which]))
                .setNegativeButton("关闭", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK_KEY && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                new Thread(() -> {
                    try {
                        String text = readUri(uri);
                        main.post(() -> importFromText(text));
                    } catch (Exception e) {
                        main.post(() -> toast("读取文件失败：" + e.getMessage()));
                    }
                }, "read-key-file").start();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private String readUri(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             BufferedReader r = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    /** 部署二进制 + 生成密钥 + 显示公钥（后台线程） */
    private void prepare() {
        btnRun.setEnabled(false);
        new Thread(() -> {
            try {
                runner.ensureBins();
                String keyPath = runner.ensureKey();
                main.post(() -> {
                    if (!isFinishing()) {
                        refreshKeyUi(runner.hasImportedKey()
                                ? "已导入用户私钥（user_key）" : "内置密钥（id_ed25519）");
                        btnRun.setEnabled(true);
                        appendOutput("== 就绪：rsync/ssh/dropbearkey 已部署（nativeLibraryDir）==");
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (!isFinishing()) {
                        tvPubkey.setText("（初始化失败：" + e.getMessage() + "）");
                        appendOutput("== 初始化失败：" + e + " ==");
                    }
                });
            }
        }, "rsync-prepare").start();
    }

    private void importFromText() {
        importFromText(etImportKey.getText().toString().trim());
    }

    private void importFromText(String text) {
        if (text.isEmpty()) {
            toast("请先粘贴私钥内容或选择私钥文件");
            return;
        }
        new Thread(() -> {
            try {
                runner.importPrivateKey(text);
                main.post(() -> {
                    refreshKeyUi("已导入用户私钥（user_key）");
                    etImportKey.setText("");
                    toast("私钥导入成功，当前公钥已更新");
                    appendOutput("== 已导入用户私钥；请将下方公钥添加到远程 authorized_keys ==");
                });
            } catch (Exception e) {
                main.post(() -> {
                    refreshKeyUi("导入失败：" + e.getMessage());
                    toast("导入失败：" + e.getMessage());
                });
            }
        }, "rsync-import").start();
    }

    /** 刷新公钥 + 密钥状态（主线程） */
    private void refreshKeyUi(String state) {
        tvKeyState.setText(state);
        String pub = runner.getPublicKeyText();
        tvPubkey.setText(pub == null ? "（公钥读取失败）" : pub);
    }

    private void runCommand() {
        String cmd = etCommand.getText().toString().trim();
        if (cmd.isEmpty()) {
            toast("请先输入 rsync 命令");
            return;
        }
        if (!runner.isReady()) {
            toast("正在初始化，请稍候");
            return;
        }
        btnRun.setEnabled(false);
        btnStop.setEnabled(true);
        String password = etPassword.getText().toString();
        appendOutput("$ " + cmd);
        try {
            runner.run(cmd, password, new RsyncClientRunner.OutputListener() {
                @Override
                public void onLine(String line) {
                    main.post(() -> appendOutput(line));
                }

                @Override
                public void onExit(int exitCode, String summary) {
                    main.post(() -> {
                        appendOutput("== " + summary + " ==");
                        btnRun.setEnabled(true);
                        btnStop.setEnabled(false);
                        if (exitCode == 0) {
                            runner.addHistory(cmd);
                            etPassword.setText("");
                        }
                    });
                }
            });
        } catch (IOException e) {
            appendOutput("== 无法启动：" + e.getMessage() + " ==");
            btnRun.setEnabled(true);
            btnStop.setEnabled(false);
        }
    }

    private void fillExample() {
        Settings s = Settings.load(this);
        String key = runner.getActiveKeyPath();
        String example = "rsync -avzP -e \"ssh -y -i " + key + " -p 22\" "
                + "user@192.168.1.100:/home/user/photos/ "
                + "/storage/emulated/0/Download/";
        etCommand.setText(example);
        toast("已填入示例，请修改远程主机 IP / 用户 / 路径");
    }

    private void appendOutput(String line) {
        String old = tvOutput.getText().toString();
        if (old.length() > 40000) {
            old = old.substring(old.length() - 30000);
        }
        tvOutput.setText(old + "\n" + line);
        // v1.0.15 方案：自动滚动到底部，让最新输出始终可见
        tvOutput.post(() -> {
            android.text.Layout layout = tvOutput.getLayout();
            if (layout != null) {
                int last = tvOutput.getLineCount();
                int pos = layout.getLineTop(last) - tvOutput.getHeight();
                if (pos > 0) {
                    tvOutput.scrollTo(0, pos);
                }
            }
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        runner.stop();
        super.onDestroy();
    }
}
