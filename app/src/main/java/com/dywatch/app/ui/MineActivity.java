package com.dywatch.app.ui;

// 「我的」页：喜欢（服务端只读接口） + 看过（本地账本）。
//
// 为什么这两个能走原生 API 而不是 WebView 引擎：
// 风控严的是**写入**（裸 digg 实测 403 → 会话当场死亡），读取宽松。
// 2026-09-26 实测 /aweme/v1/web/aweme/favorite/ 直连拿到 13 条真数据。
// 而"看过"压根没有服务端接口（抖音 Web 端无观看历史 API），用本地 HistoryStore 拼。

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.dywatch.app.R;
import com.dywatch.app.feed.FeedVideo;
import com.dywatch.app.feed.HistoryStore;
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

public class MineActivity extends UiActivity {

    /** 0 = 喜欢，1 = 看过 */
    private static final int TAB_LIKE = 0, TAB_HISTORY = 1;

    private androidx.recyclerview.widget.RecyclerView mList;
    private RowAdapter mAdapter;
    private TextView mHint;
    private Button mTabLike, mTabHistory;
    private DouyinApi mApi;
    private int mTab = TAB_LIKE;
    private boolean mLoading;
    /** 当前 tab 的数据源（点击后进播放页要用） */
    private final List<FeedVideo> mVideos = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mine);
        setPageTitle("我的");

        mList = findViewById(R.id.rv_mine);
        mList.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        mAdapter = new RowAdapter(new RowAdapter.OnRowClick() {
            @Override
            public void onClick(Object payload) {
                if (!(payload instanceof Integer)) return;
                openAt((Integer) payload);
            }
        });
        mList.setAdapter(mAdapter);
        mHint = findViewById(R.id.tv_mine_hint);
        mTabLike = findViewById(R.id.btn_tab_like);
        mTabHistory = findViewById(R.id.btn_tab_history);

        mTabLike.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { switchTab(TAB_LIKE); }
        });
        mTabHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { switchTab(TAB_HISTORY); }
        });

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        switchTab(TAB_LIKE);
    }

    private void switchTab(int tab) {
        if (mTab == tab && !mVideos.isEmpty()) return;
        mTab = tab;
        mTabLike.setTextColor(getColorCompat(mTab == TAB_LIKE
                ? R.color.text_primary : R.color.text_secondary));
        mTabHistory.setTextColor(getColorCompat(mTab == TAB_HISTORY
                ? R.color.text_primary : R.color.text_secondary));
        mVideos.clear();
        mAdapter.clear();
        if (tab == TAB_LIKE) loadLikes();
        else loadHistory();
    }

    private int getColorCompat(int res) {
        return androidx.core.content.ContextCompat.getColor(this, res);
    }

    // ---- 喜欢：服务端只读接口 ----

    private void loadLikes() {
        if (!LoginManager.hasSession(this)) {
            hint("未登录，看不到喜欢列表（主屏→登录→扫码）");
            return;
        }
        if (mLoading) return;
        mLoading = true;
        hint("正在拉取喜欢…");
        Loading.show(this, true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 顺带修：原来写成 ensureApi().fetchFavorite(ensureApi().fetchSelfSecUid(), 0)，
                    // 同一个 api 对象取两遍（第一次还要读 3 个 assets 建签名器），没必要
                    final DouyinApi api = ensureApi();
                    final List<FeedVideo> list = api.fetchFavorite(api.fetchSelfSecUid(), 0);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mLoading = false;
                            // 成功路径也落一行：只记失败的话，"列表是空的"到底是没数据还是没拉到就分不清
                            AppLog.i("mine", "喜欢列表拉到 " + list.size() + " 条");
                            show(list, "还没有喜欢过的视频");
                        }
                    });
                } catch (final Exception e) {
                    AppLog.i("mine", "喜欢列表失败: " + e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mLoading = false;
                            Loading.show(MineActivity.this, false);
                            hint("拉取失败: " + e.getMessage());
                        }
                    });
                }
            }
        }, "mine-like").start();
    }

    // ---- 看过：本地账本 ----

    private void loadHistory() {
        // 本地账本，没有网络等待 → 不需要转圈（上一段的圈要确保收掉）
        Loading.show(this, false);
        List<FeedVideo> out = new ArrayList<>();
        for (HistoryStore.Entry e : HistoryStore.read(this)) {
            // 看过只有 id/标题/封面，没有播放地址——点击后要去换址，
            // 这里先按"可点进详情"处理，播放地址留空由播放页兜底。
            out.add(new FeedVideo(e.title, "", e.coverUrl, e.awemeId, "", 0, 0, 0));
        }
        show(out, "还没有看过记录");
    }

    private void show(List<FeedVideo> list, String emptyText) {
        mVideos.clear();
        mVideos.addAll(list);
        List<RowAdapter.Item> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            FeedVideo v = list.get(i);
            String meta = v.authorName == null || v.authorName.isEmpty()
                    ? "" : v.authorName;
            items.add(new RowAdapter.Item(
                    v.title.isEmpty() ? "(无标题)" : v.title, meta, "", i));
        }
        mAdapter.submitList(items);
        Loading.show(this, false);
        if (list.isEmpty()) {
            // 空态也要把页名切过来，否则上一段的"喜欢 13"会一直挂着，看着像切了 tab 没生效
            setPageTitle(mTab == TAB_LIKE ? "喜欢" : "看过");
            hint(emptyText);
        } else {
            clearHint();
            setPageTitle((mTab == TAB_LIKE ? "喜欢 " : "看过 ") + list.size());
        }
    }

    /** 点条目 → 进播放页（把整批传过去，可以上下滑着看） */
    private void openAt(int index) {
        if (index < 0 || index >= mVideos.size()) return;
        // 看过的记录没有播放地址，直接播不了——提示而不是给个黑屏
        if (mTab == TAB_HISTORY) {
            hint("看过记录需要重新取播放地址，暂不支持直接播放");
            return;
        }
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
