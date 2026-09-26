package com.dywatch.app.ui;

// 作者主页：作品列表 + 用户信息。
//
// 纯只读：/aweme/v1/web/aweme/post/（作品，实测 5 条）+ /aweme/v1/web/user/profile/other/（信息）。
// 都走原生 API 直连——风控严的是写入，读取宽松（本项目 2026-09-26 实测结论）。
// 播放复用 FeedActivity 的"预置列表"模式，不必再写一套播放页。

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.dywatch.app.R;
import com.dywatch.app.feed.FeedVideo;
import com.dywatch.app.login.LoginManager;
import com.dywatch.app.net.DouyinApi;
import com.dywatch.app.sign.Signer;
import com.dywatch.app.util.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class UserActivity extends UiActivity {

    public static final String EXTRA_SEC_UID = "sec_uid";
    public static final String EXTRA_NAME = "name";

    private androidx.recyclerview.widget.RecyclerView mList;
    private RowAdapter mAdapter;
    private TextView mHint, mName, mStats;
    private DouyinApi mApi;
    private String mSecUid;
    /** 当前作品列表（点条目进播放页要用） */
    private final List<FeedVideo> mVideos = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user);

        mSecUid = getIntent().getStringExtra(EXTRA_SEC_UID);
        if (mSecUid == null) mSecUid = "";
        setPageTitle("主页");

        mList = findViewById(R.id.rv_user);
        mList.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        mAdapter = new RowAdapter(new RowAdapter.OnRowClick() {
            @Override
            public void onClick(Object payload) {
                if (payload instanceof Integer) playAt((Integer) payload);
            }
        });
        mList.setAdapter(mAdapter);

        mHint = findViewById(R.id.tv_user_hint);
        mName = findViewById(R.id.tv_user_name);
        mStats = findViewById(R.id.tv_user_stats);

        // 先用上一页带来的昵称占位，避免头部空着（信息接口要等一下）
        String name = getIntent().getStringExtra(EXTRA_NAME);
        mName.setText(name == null || name.isEmpty() ? "作者" : name);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        if (mSecUid.isEmpty()) {
            hint("拿不到作者标识，无法打开主页");
            return;
        }
        load();
    }

    private void load() {
        hint("正在拉取作品…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final DouyinApi api = ensureApi();
                    // 两个请求都做，但任何一个失败都不该让整页空白
                    final List<FeedVideo> posts = api.fetchUserPosts(mSecUid, 0);
                    DouyinApi.UserInfo info = null;
                    try {
                        info = api.fetchUserInfo(mSecUid);
                    } catch (Exception e) {
                        AppLog.i("user", "作者信息失败（不影响作品列表）: " + e);
                    }
                    final DouyinApi.UserInfo finfo = info;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            if (finfo != null) {
                                mName.setText(finfo.nickname.isEmpty() ? "作者" : finfo.nickname);
                                StringBuilder sb = new StringBuilder();
                                sb.append("粉丝 ").append(FeedVideo.formatCount(finfo.followerCount));
                                sb.append(" · 作品 ").append(finfo.awemeCount);
                                sb.append(" · 获赞 ").append(FeedVideo.formatCount(finfo.totalFavorited));
                                mStats.setText(sb.toString());
                                setPageTitle(finfo.nickname.isEmpty() ? "主页" : finfo.nickname);
                            }
                            show(posts);
                        }
                    });
                } catch (final Exception e) {
                    AppLog.i("user", "作品列表失败: " + e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            hint("拉取失败: " + e.getMessage());
                        }
                    });
                }
            }
        }, "user-load").start();
    }

    private void show(List<FeedVideo> list) {
        mVideos.clear();
        mVideos.addAll(list);
        List<RowAdapter.Item> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            FeedVideo v = list.get(i);
            String meta = v.diggCount > 0 ? (FeedVideo.formatCount(v.diggCount) + " 赞") : "";
            items.add(new RowAdapter.Item(
                    v.title.isEmpty() ? "(无标题)" : v.title, meta, "", i));
        }
        mAdapter.submitList(items);
        if (list.isEmpty()) {
            hint("这个作者没有作品（或接口没返回）");
        } else {
            clearHint();
        }
    }

    /** 点条目 → 用预置列表模式播放该主页的全部作品 */
    private void playAt(int index) {
        if (index < 0 || index >= mVideos.size()) return;
        android.content.Intent it = new android.content.Intent(this,
                com.dywatch.app.feed.FeedActivity.class);
        it.putExtra(com.dywatch.app.feed.FeedActivity.EXTRA_VIDEO_LIST,
                new ArrayList<>(mVideos));
        it.putExtra(com.dywatch.app.feed.FeedActivity.EXTRA_START_INDEX, index);
        startActivity(it);
    }

    private DouyinApi ensureApi() throws Exception {
        if (mApi != null) return mApi;
        mApi = new DouyinApi(new Signer(Arrays.asList(
                readAll(getAssets().open("sign/utils.js")),
                readAll(getAssets().open("sign/sm3.js")),
                readAll(getAssets().open("sign/vm_decode.js")))));
        mApi.setSessionCookie(LoginManager.getCookies(this));
        return mApi;
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private void hint(String text) {
        if (mHint == null) return;
        mHint.setText(text);
        mHint.setVisibility(View.VISIBLE);
    }

    private void clearHint() {
        if (mHint != null) mHint.setVisibility(View.GONE);
    }
}
