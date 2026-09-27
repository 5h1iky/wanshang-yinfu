package com.dywatch.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.dywatch.app.R;

/**
 * 关于页（方案 F，2026-09-27）：
 * 应用身份（图标/名称/版本读包信息）+ GitHub 仓库 + 作者个人主页 + GPL-3.0 + 免责声明。
 *
 * ⚠️ 素材口径（用户更正过，别写错）：
 * - GitHub 账号 = 5h1iky（发布仓库 5h1iky/wanshang-yinfu）
 * - 个人主页 = https://5h1iky.github.io/portfolio/
 * - www.cetools.com 是项目名（SAChat）不是个人网站，禁止当链接用。
 */
public class AboutActivity extends UiActivity {

    private static final String REPO_URL = "https://github.com/5h1iky/wanshang-yinfu";
    private static final String HOMEPAGE_URL = "https://5h1iky.github.io/portfolio/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        setPageTitle("关于");

        TextView version = findViewById(R.id.tv_about_version);
        version.setText("v" + versionName());

        TextView github = findViewById(R.id.row_github);
        github.setText("GitHub：@5h1iky\n" + REPO_URL.replace("https://", ""));
        github.setOnClickListener(open(REPO_URL));

        TextView homepage = findViewById(R.id.row_homepage);
        homepage.setText("作者主页\n" + HOMEPAGE_URL.replace("https://", ""));
        homepage.setOnClickListener(open(HOMEPAGE_URL));

        TextView license = findViewById(R.id.row_license);
        license.setText("开源协议：GPL-3.0\n本应用基于 GPL-3.0 发布，源码公开");
        license.setOnClickListener(open("https://www.gnu.org/licenses/gpl-3.0.html"));

        // 免责声明：复用首启那份六条全文（不重复维护两份文案）
        findViewById(R.id.row_disclaimer).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                com.dywatch.app.util.Disclaimer.show(AboutActivity.this);
            }
        });
    }

    private View.OnClickListener open(final String url) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    // 手表上多半没有浏览器，兜底尝试一次；失败只提示不崩
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    android.widget.Toast.makeText(AboutActivity.this,
                            "本机没有可打开链接的应用", android.widget.Toast.LENGTH_SHORT).show();
                    com.dywatch.app.util.AppLog.i("about", "打开链接失败 " + url + ": " + e);
                }
            }
        };
    }

    private String versionName() {
        try {
            return String.valueOf(getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception e) {
            return "0.0.0";
        }
    }
}
