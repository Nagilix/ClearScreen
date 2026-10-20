package com.clearscreen.app.ui;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.clearscreen.app.R;
import com.clearscreen.app.data.AppPreferences;
import com.clearscreen.app.data.InterceptDatabaseHelper;
import com.clearscreen.app.data.InterceptRecord;
import com.clearscreen.app.service.KeepAliveForegroundService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private View viewStatusDot;
    private TextView tvStatusTitle;
    private TextView tvTotalCount;
    private Button btnToggleService;
    private Button btnHonorKeepalive;
    private Switch switchShield;
    private TextView tvClearRecords;
    private ListView lvStats;

    private InterceptDatabaseHelper dbHelper;
    private AppPreferences appPreferences;
    private final List<InterceptRecord> recordList = new ArrayList<>();
    private StatsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dbHelper = InterceptDatabaseHelper.getInstance(this);
        appPreferences = AppPreferences.getInstance(this);

        try {
            initViews();
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            KeepAliveForegroundService.start(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initViews() {
        viewStatusDot = findViewById(R.id.view_status_dot);
        tvStatusTitle = findViewById(R.id.tv_status_title);
        tvTotalCount = findViewById(R.id.tv_total_count);
        btnToggleService = findViewById(R.id.btn_toggle_service);
        btnHonorKeepalive = findViewById(R.id.btn_honor_keepalive);
        switchShield = findViewById(R.id.switch_shield);
        tvClearRecords = findViewById(R.id.tv_clear_records);
        lvStats = findViewById(R.id.lv_stats);

        adapter = new StatsAdapter();
        lvStats.setAdapter(adapter);

        // 无障碍设置跳转
        btnToggleService.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "请在已下载服务中开启【清屏】", Toast.LENGTH_SHORT).show();
        });

        // 荣耀专属保活跳转
        btnHonorKeepalive.setOnClickListener(v -> {
            HonorSystemHelper.openHonorAutoStartSettings(this);
            Toast.makeText(this, "建议将【清屏】设为手动管理：开启自启、关联启动与后台运行", Toast.LENGTH_LONG).show();
        });

        // 防误触护盾开关
        switchShield.setChecked(appPreferences.isTouchShieldEnabled());
        switchShield.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, "需开启悬浮窗权限以展示防误触透明护盾", Toast.LENGTH_LONG).show();
            }
            appPreferences.setTouchShieldEnabled(isChecked);
        });

        // 清空记录
        tvClearRecords.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("清空统计数据")
                    .setMessage("确定要清空所有应用的广告拦截计数吗？")
                    .setPositiveButton("清空", (dialog, which) -> {
                        dbHelper.clearAllRecords();
                        updateUIState();
                        Toast.makeText(this, "已重置拦截数据", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        // 长按条目加入或移出白名单
        lvStats.setOnItemLongClickListener((parent, view, position, id) -> {
            InterceptRecord record = recordList.get(position);
            boolean isWhitelisted = appPreferences.isPackageWhitelisted(record.getPackageName());
            String actionName = isWhitelisted ? "移出白名单 (恢复跳过)" : "加入白名单 (不再跳过此App)";

            new AlertDialog.Builder(this)
                    .setTitle(record.getAppName())
                    .setItems(new CharSequence[]{actionName, "删除该条统计"}, (dialog, which) -> {
                        if (which == 0) {
                            if (isWhitelisted) {
                                appPreferences.removeWhitelistPackage(record.getPackageName());
                                Toast.makeText(this, "已恢复对 " + record.getAppName() + " 的拦截跳过", Toast.LENGTH_SHORT).show();
                            } else {
                                appPreferences.addWhitelistPackage(record.getPackageName());
                                Toast.makeText(this, "已将 " + record.getAppName() + " 设为白名单", Toast.LENGTH_SHORT).show();
                            }
                            adapter.notifyDataSetChanged();
                        } else if (which == 1) {
                            dbHelper.deleteRecord(record.getPackageName());
                            updateUIState();
                        }
                    })
                    .show();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUIState();
    }

    private void updateUIState() {
        boolean isServiceEnabled = isAccessibilityServiceEnabled();
        if (isServiceEnabled) {
            viewStatusDot.setBackgroundResource(R.drawable.dot_status_active);
            tvStatusTitle.setText("清屏静默守护中");
            btnToggleService.setText("服务运行正常 (点击进入系统无障碍)");
        } else {
            viewStatusDot.setBackgroundResource(R.drawable.dot_status_inactive);
            tvStatusTitle.setText("无障碍服务未开启");
            btnToggleService.setText("立即授权无障碍服务");
        }

        int total = dbHelper.getTotalInterceptCount();
        tvTotalCount.setText(String.valueOf(total));

        recordList.clear();
        recordList.addAll(dbHelper.getAllRecords());
        adapter.notifyDataSetChanged();
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC);
        for (AccessibilityServiceInfo info : services) {
            if (info.getId() != null && info.getId().contains(getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private class StatsAdapter extends BaseAdapter {
        private final SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

        @Override
        public int getCount() {
            return recordList.size();
        }

        @Override
        public Object getItem(int position) {
            return recordList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_intercept_stat, parent, false);
            }
            InterceptRecord record = recordList.get(position);

            TextView tvAppName = convertView.findViewById(R.id.tv_app_name);
            TextView tvPkgName = convertView.findViewById(R.id.tv_pkg_name);
            TextView tvCount = convertView.findViewById(R.id.tv_intercept_count);
            TextView tvTime = convertView.findViewById(R.id.tv_last_time);

            boolean isWhitelisted = appPreferences.isPackageWhitelisted(record.getPackageName());
            tvAppName.setText(record.getAppName() + (isWhitelisted ? " [已加白名单]" : ""));
            tvPkgName.setText(record.getPackageName());
            tvCount.setText(record.getCount() + " 次");
            tvTime.setText("最近跳过: " + sdf.format(new Date(record.getLastInterceptTime())));

            return convertView;
        }
    }
}
