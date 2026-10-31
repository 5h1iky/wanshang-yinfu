package com.dywatch.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.dywatch.app.R;
import com.dywatch.app.net.AnnounceApi;
import com.dywatch.app.net.AnnounceStore;
import com.dywatch.app.net.UpdateChecker;
import com.dywatch.app.util.AppLog;

/** 设置 → 检查更新：公告 + 最新 Release 下载入口。 */
public class UpdateActivity extends UiActivity {

    private static final String RELEASES_PAGE =
            "https://github.com/5h1iky/wanshang-yinfu/releases/latest";

    private TextView mCurrent, mStatus, mNotes, mAnnounceTitle, mAnnounceText, mUrl;
    private Button mDownload;
    private String mDownloadUrl = RELEASES_PAGE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);
        setPageTitle("检查更新");

        mCurrent = findViewById(R.id.tv_update_current);
        mStatus = findViewById(R.id.tv_update_status);
        mNotes = findViewById(R.id.tv_update_notes);
        mAnnounceTitle = findViewById(R.id.tv_announce_title);
        mAnnounceText = findViewById(R.id.tv_announce_text);
        mUrl = findViewById(R.id.tv_update_url);
        mDownload = findViewById(R.id.btn_update_download);

        mCurrent.setText("当前版本 v" + versionName());
        mDownload.setEnabled(true);
        mDownload.setText("打开 Releases 页面");
        mDownload.setOnClickListener(v -> open(mDownloadUrl));

        renderAnnouncement();
        // 设置页已经拉过一次；这里强制刷新，保证“检查更新”看到的是最新公告。
        AnnounceStore.refreshInBackground(this, true, () -> runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) renderAnnouncement();
        }));
        checkLatest();
    }

    private void renderAnnouncement() {
        AnnounceApi.Announcement a = AnnounceStore.current(this);
        if (a == null) {
            mAnnounceTitle.setText("暂无公告");
            mAnnounceText.setText("当前没有新的公告。");
            return;
        }
        mAnnounceTitle.setText(a.title == null || a.title.isEmpty() ? "公告" : a.title);
        mAnnounceText.setText(a.text == null || a.text.isEmpty() ? "（无正文）" : a.text);
    }

    private void checkLatest() {
        mStatus.setText("正在检查更新…");
        new Thread(() -> {
            UpdateChecker.UpdateInfo info = new UpdateChecker().fetchLatest();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (info == null) {
                    mStatus.setText("暂时无法检查更新");
                    mNotes.setText("可以直接打开 Releases 页面查看最新版本。");
                    mNotes.setVisibility(View.VISIBLE);
                    mDownloadUrl = RELEASES_PAGE;
                    mDownload.setText("打开 Releases 页面");
                    updateUrlText();
                    return;
                }
                String current = versionName();
                boolean newer = UpdateChecker.isNewer(info.version, current);
                if (newer) {
                    mStatus.setText("发现新版本 v" + info.version);
                } else if (info.version.equals(current)) {
                    mStatus.setText("当前已是最新版本 v" + info.version);
                } else {
                    mStatus.setText("当前版本 v" + current
                            + " 高于线上最新 v" + info.version);
                }
                if (newer && info.notes != null && !info.notes.isEmpty()) {
                    mNotes.setText(info.notes);
                    mNotes.setVisibility(View.VISIBLE);
                } else {
                    mNotes.setVisibility(View.GONE);
                }
                if (newer) {
                    mDownloadUrl = info.apkUrl == null || info.apkUrl.isEmpty()
                            ? (info.htmlUrl == null || info.htmlUrl.isEmpty()
                                    ? RELEASES_PAGE : info.htmlUrl)
                            : info.apkUrl;
                    mDownload.setText("下载最新版");
                } else {
                    // 已是最新时不去重复下载同版本，直接给 Release 页面。
                    mDownloadUrl = info.htmlUrl == null || info.htmlUrl.isEmpty()
                            ? RELEASES_PAGE : info.htmlUrl;
                    mDownload.setText("打开 Releases 页面");
                }
                updateUrlText();
                AppLog.i("update", "检查完成 latest=" + info.version
                        + " newer=" + newer + " download=" + (!mDownloadUrl.isEmpty()));
            });
        }, "update-page-check").start();
    }

    private void updateUrlText() {
        if (mDownloadUrl == null || mDownloadUrl.isEmpty()) {
            mUrl.setVisibility(View.GONE);
            return;
        }
        mUrl.setText(mDownloadUrl);
        mUrl.setVisibility(View.VISIBLE);
    }

    private void open(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "本机没有可打开链接的应用",
                    android.widget.Toast.LENGTH_SHORT).show();
            AppLog.i("update", "打开下载链接失败: " + e.getClass().getSimpleName());
        }
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
