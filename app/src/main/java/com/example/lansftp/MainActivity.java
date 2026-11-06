package com.example.lansftp;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSION = 100;

    private EditText etUser, etPass, etPort, etRoot, etKeys;
    private Button btnToggle;
    private TextView tvStatus;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            refreshStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        etPort = findViewById(R.id.etPort);
        etRoot = findViewById(R.id.etRoot);
        etKeys = findViewById(R.id.etKeys);
        btnToggle = findViewById(R.id.btnToggle);
        tvStatus = findViewById(R.id.tvStatus);
        findViewById(R.id.btnRsyncClient).setOnClickListener(
                v -> startActivity(new android.content.Intent(this, RsyncClientActivity.class)));

        Settings s = Settings.load(this);
        etUser.setText(s.username);
        etPass.setText(s.password);
        etPort.setText(String.valueOf(s.port));
        etRoot.setText(s.rootPath);
        etKeys.setText(s.authorizedKeys);

        // 公钥框输入即自动保存：配合服务端实时读取，粘贴公钥后无需重启服务器、无需点按钮，
        // 下次 ssh/rsync 连接立即生效
        etKeys.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence cs, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence cs, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                // 同步落盘（commit + 私有文件 fsync）：App 从后台被杀进程后公钥仍保留
                Settings t = Settings.load(MainActivity.this);
                t.authorizedKeys = e.toString();
                t.saveAuthorizedKeysSync(MainActivity.this, t.authorizedKeys);
            }
        });

        btnToggle.setOnClickListener(v -> {
            if (SftpServerManager.getInstance(this).isRunning()) {
                SftpServerService.stop(this);
            } else {
                startServer();
            }
        });
    }

    private void startServer() {
        if (!ensurePermissions()) {
            return;
        }
        Settings s = new Settings();
        s.username = etUser.getText().toString().trim();
        s.password = etPass.getText().toString();
        try {
            s.port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "端口必须是数字", Toast.LENGTH_SHORT).show();
            return;
        }
        s.rootPath = etRoot.getText().toString().trim();
        s.authorizedKeys = etKeys.getText().toString().trim();

        if (s.username.isEmpty()) {
            Toast.makeText(this, "用户名不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (s.password.length() < 6) {
            Toast.makeText(this, "密码至少需要 6 位", Toast.LENGTH_SHORT).show();
            return;
        }
        if (s.port < 1 || s.port > 65535) {
            Toast.makeText(this, "端口号无效（1-65535）", Toast.LENGTH_SHORT).show();
            return;
        }
        if (s.rootPath.isEmpty()) {
            Toast.makeText(this, "共享根目录不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        SftpServerService.start(this, s.port, s.username, s.password, s.rootPath);
    }

    /** 检查并引导授权：Android 11+ 全文件访问、10- 存储权限、13+ 通知权限 */
    private boolean ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
                Toast.makeText(this, "请在“所有文件访问权限”中允许本应用后重试", Toast.LENGTH_LONG).show();
                return false;
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQ_PERMISSION);
                return false;
            }
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            // 通知权限缺失时提示一次，不阻塞启动
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISSION);
        }
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(SftpServerService.STATUS_BROADCAST);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception ignored) {
        }
    }

    private void refreshStatus() {
        boolean running = SftpServerManager.getInstance(this).isRunning();
        if (running) {
            Settings s = Settings.load(this);
            tvStatus.setText("● 运行中\n" + NetworkUtils.buildConnectionAddressText(s.username, s.port));
            btnToggle.setText("停止服务器");
        } else {
            tvStatus.setText("○ 已停止");
            btnToggle.setText("启动服务器");
        }
    }
}
