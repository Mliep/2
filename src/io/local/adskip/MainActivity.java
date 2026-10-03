package io.local.adskip;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends Activity {

    private TextView statusTitle;
    private TextView statusDesc;
    private TextView footer;
    private Button openBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusTitle = (TextView) findViewById(R.id.status_title);
        statusDesc = (TextView) findViewById(R.id.status_desc);
        footer = (TextView) findViewById(R.id.footer);
        openBtn = (Button) findViewById(R.id.btn_open);
        openBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        footer.setText((version.isEmpty() ? "" : "版本 " + version + " (Android 16 专属定制版)\n\n") + getString(R.string.footer));
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = isServiceEnabled();
        statusTitle.setText(on ? R.string.status_on : R.string.status_off);
        statusTitle.setBackgroundResource(on ? R.drawable.bg_status_on : R.drawable.bg_status_off);

        if (on) {
            SharedPreferences sp = getSharedPreferences(SkipAdService.PREF_NAME, Context.MODE_PRIVATE);
            long count = sp.getLong(SkipAdService.KEY_SKIP_COUNT, 0);
            if (count > 0) {
                statusDesc.setText("已累计自动跳过 " + count + " 次广告！\n检测到开屏广告时会自动帮你秒速跳过。");
            } else {
                statusDesc.setText("服务准备就绪！检测到开屏广告时会自动为你秒速跳过。");
            }
            openBtn.setText(R.string.btn_settings);
        } else {
            statusDesc.setText(R.string.status_off_desc);
            openBtn.setText(R.string.btn_open);
        }
    }

    private boolean isServiceEnabled() {
        // 1. 系统 Settings.Secure 检测
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String pkg = getPackageName();
        if (enabled != null) {
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(enabled);
            for (String line : splitter) {
                if (line.toLowerCase().contains(pkg.toLowerCase())) return true;
            }
        }

        // 2. AccessibilityManager 双重校验
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (list != null) {
                    for (AccessibilityServiceInfo info : list) {
                        if (info.getId() != null && info.getId().toLowerCase().contains(pkg.toLowerCase())) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }
}
