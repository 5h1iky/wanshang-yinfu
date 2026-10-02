package com.dywatch.app.feed;

// 源自 DKVideoPlayer demo 的 Tiktok2Adapter（Apache-2.0，Copyright Doikki）——
// 按本项目数据模型改写：视图复用池 + 预加载任务绑定保留，Glide 封面加载保留；
// 新增：操作栏绑定（赞/评/藏计数 + 互动状态）与互动回调。
// License: Apache-2.0（见 lib/LICENSE-DKVideoPlayer.txt）

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;

import com.bumptech.glide.Glide;
import com.dywatch.app.R;
import com.dywatch.app.cache.PreloadManager;

import java.util.ArrayList;
import java.util.List;

public class FeedAdapter extends PagerAdapter {

    /** 互动回调（点赞/评论/收藏/分享/进作者主页） */
    public interface ActionListener {
        void onLike(FeedVideo video, ViewHolder holder);
        void onComment(FeedVideo video);
        void onCollect(FeedVideo video, ViewHolder holder);
        void onShare(FeedVideo video);
        /** 点作者头像 → 进主页（只读，走原生 API） */
        void onAvatar(FeedVideo video);
    }

    /** View 缓存池：从 ViewPager 移除的 item 存这里复用（沿用 demo 方案） */
    private final List<View> mViewPool = new ArrayList<>();

    private final List<FeedVideo> mVideos;
    private final ActionListener mActionListener;

    public FeedAdapter(List<FeedVideo> videos, ActionListener listener) {
        this.mVideos = videos;
        this.mActionListener = listener;
    }

    @Override
    public int getCount() {
        return mVideos == null ? 0 : mVideos.size();
    }

    /**
     * 数据变更后的位置映射（真机踩坑：不覆写它 = notifyDataSetChanged 对已有页面不重绑，
     * 旧标题/旧封面留在页面上、播放器却播新列表的 URL = "内容与简介不一致"）。
     * 按条目身份追踪：条目还在新列表 → 返回新位置（页面跟着挪）；不在（列表被替换）→ POSITION_NONE 重建。
     */
    @Override
    public int getItemPosition(@NonNull Object object) {
        View view = (View) object;
        Object tag = view.getTag();
        if (!(tag instanceof ViewHolder)) return POSITION_NONE;
        FeedVideo item = ((ViewHolder) tag).mItem;
        int idx = mVideos == null ? -1 : mVideos.indexOf(item);
        return idx >= 0 ? idx : POSITION_NONE;
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object o) {
        return view == o;
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        Context context = container.getContext();
        // 边界护栏：数据变更与 populate 竞态时 position 可能瞬时越界
        final int pos = Math.min(position, Math.max(0, mVideos.size() - 1));
        View view = null;
        if (mViewPool.size() > 0) {
            view = mViewPool.get(0);
            mViewPool.remove(0);
        }

        final ViewHolder viewHolder;
        if (view == null) {
            view = LayoutInflater.from(context).inflate(R.layout.item_feed, container, false);
            viewHolder = new ViewHolder(view);
        } else {
            viewHolder = (ViewHolder) view.getTag();
        }

        final FeedVideo item = mVideos.get(pos);
        // 复用视图可能残留上一页的播放器容器内容 → 清掉（防串页）
        viewHolder.mPlayerContainer.removeAllViews();
        // 开始预加载
        PreloadManager.getInstance(context).addPreloadTask(item.playUrl, position);
        Glide.with(context)
                .load(item.coverUrl)
                .placeholder(android.R.color.black)
                .listener(imageLog("封面", item.awemeId))
                .into(viewHolder.mThumb);
        // 作者头像（2026-09-27 修）：此前这里只加载了封面，一行代码都没给 iv_avatar 加载过图，
        // 所以视频页的头像永远是布局里那张静态占位矢量图 = 用户报的"头像一直没加载出来"。
        // 头像 URL 来自 author.avatar_thumb（douyinpic.com，实测裸链可取而无需 Referer）。
        // circleCrop：抖音头像本身是方的，不裁圆在圆屏上很硬。
        if (viewHolder.mIvAvatar != null) {
            if (item.authorAvatar == null || item.authorAvatar.isEmpty()) {
                viewHolder.mIvAvatar.setImageResource(R.drawable.ic_avatar);
            } else {
                Glide.with(context)
                        .load(item.authorAvatar)
                        .circleCrop()
                        .placeholder(R.drawable.ic_avatar)
                        .error(R.drawable.ic_avatar)
                        .listener(imageLog("头像", item.awemeId))
                        .into(viewHolder.mIvAvatar);
            }
        }
        // 标题带作者名（抖音本体口径）：@昵称 + 标题。作者名缺失时退回纯标题。
        viewHolder.mTitle.setText(item.authorName == null || item.authorName.isEmpty()
                ? item.title : "@" + item.authorName + "  " + item.title);
        // 方案 C：视频标题 = 阅读面，吃「字体大小」设置（MarqueeTextView.onMeasure 会随
        // 字号重算滚动范围，行高锁死由固定 padding 保证）
        com.dywatch.app.ui.Fonts.scale(viewHolder.mTitle, R.dimen.t_lede);
        viewHolder.mPosition = pos;
        viewHolder.mItem = item;
        bindActions(viewHolder, item);
        // 每次绑定重设监听（闭包引用当前条目，防复用串页）
        viewHolder.mBtnLike.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mActionListener != null) mActionListener.onLike(item, viewHolder);
            }
        });
        viewHolder.mBtnComment.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mActionListener != null) mActionListener.onComment(item);
            }
        });
        viewHolder.mBtnCollect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mActionListener != null) mActionListener.onCollect(item, viewHolder);
            }
        });
        viewHolder.mBtnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mActionListener != null) mActionListener.onShare(item);
            }
        });
        // 点头像进作者主页（只读接口，风控宽松）
        if (viewHolder.mIvAvatar != null) {
            viewHolder.mIvAvatar.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mActionListener != null) mActionListener.onAvatar(item);
                }
            });
        }
        container.addView(view);
        return view;
    }

    /** 绑定操作栏数据与互动状态（互动回调后可局部刷新复用） */
    public static void bindActions(ViewHolder h, FeedVideo v) {
        h.mTvLikeCount.setText(FeedVideo.formatCount(v.diggCount));
        h.mTvCommentCount.setText(FeedVideo.formatCount(v.commentCount));
        h.mTvCollectCount.setText(v.collected ? "已藏" : "收藏");
        // 激活态：2026-09-27 v2 恢复彩色激活（方案 A.4）——品牌点缀色 Bili 粉点亮 icon，
        // 未激活保持 45% 白。v7 的 setImageAlpha 亮度方案随单色方案一起退役：
        // 点缀色上 icon 的可读性经真机对比确认够好（深色视频画面上粉色实心 icon 清晰）。
        // 实现注意：setColorFilter 必须在未激活时 clear 掉，否则复用视图会把粉色带到下一页。
        bindActivation(h.mIvLike, v.liked);
        bindActivation(h.mIvCollect, v.collected);
    }

    /** 激活 = 品牌点缀色染色；未激活 = 清除滤镜 + 降透明度 */
    private static void bindActivation(ImageView iv, boolean active) {
        if (iv == null) return;
        if (active) {
            // ⚠️ 2026-10-02 修（代码审计 L1）：激活分支原本只 setColorFilter，**没把透明度调回来**。
            // 而 STATE_OFF_ALPHA 是设在 ImageView 自己身上的（不是 drawable），setColorFilter(null)
            // 并不会重置它 —— 于是"点过赞 → 取消 → 再点赞"或者复用视图时，图标会停在
            // 45% 透明上：明明已激活，看着却发暗，用户会以为没点上。
            iv.setImageAlpha(255);
            iv.setColorFilter(androidx.core.content.ContextCompat
                    .getColor(iv.getContext(), R.color.accent));
        } else {
            iv.setColorFilter(null);
            iv.setImageAlpha(STATE_OFF_ALPHA);
        }
    }

    /** 未激活 icon 的透明度（45% 白：与激活态的实色点缀形成明显但仍柔和的亮度差） */
    private static final int STATE_OFF_ALPHA = 115;

    /**
     * 图片加载的成功/失败回调，只为了**能在日志里取证**。
     *
     * 为什么必须记：封面与头像这两个字段历史上都是坏的（封面被拼成播放端点、头像压根没解析），
     * 而"没有报错"并不等于"加载成功"——Glide 加载失败是静默的，界面上看起来只是"没图"。
     * 不落日志就只剩截图一条证据，而截图对本项目是二等证据（见交接文档）。
     */
    private static com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>
    imageLog(final String what, final String awemeId) {
        return new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
            @Override
            public boolean onLoadFailed(@androidx.annotation.Nullable
                                        com.bumptech.glide.load.engine.GlideException e,
                                        Object model,
                                        @NonNull com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                        boolean isFirstResource) {
                com.dywatch.app.util.AppLog.i("feed", what + "加载失败 aweme=" + awemeId
                        + " err=" + (e == null ? "?" : e.getMessage()));
                return false;   // 交回 Glide 继续走 error 占位图
            }

            @Override
            public boolean onResourceReady(android.graphics.drawable.Drawable resource,
                                           Object model,
                                           @NonNull com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                           @NonNull com.bumptech.glide.load.DataSource dataSource,
                                           boolean isFirstResource) {
                com.dywatch.app.util.AppLog.i("feed", what + "加载成功 aweme=" + awemeId
                        + " from=" + dataSource);
                return false;
            }
        };
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        View itemView = (View) object;
        container.removeView(itemView);
        // ⚠️ PagerAdapter 契约：notifyDataSetChanged 后回收旧页面时，传入的 position 是
        // 数据变更前的旧序号——用 mVideos.get(position) 取数必然越界/错位（真机 4 次
        // IndexOutOfBounds 闪退的根因，2026-09-26）。数据一律从视图标签取。
        Object tag = itemView.getTag();
        if (tag instanceof ViewHolder) {
            FeedVideo item = ((ViewHolder) tag).mItem;
            if (item != null) {
                PreloadManager.getInstance(container.getContext()).removePreloadTask(item.playUrl);
            }
        }
        mViewPool.add(itemView);
    }

    public static class ViewHolder {

        public int mPosition;
        /** 当前绑定的数据条目（getItemPosition 按它追踪位置，防数据错位） */
        public FeedVideo mItem;
        public TextView mTitle;
        public ImageView mThumb;
        public TikTokView mTikTokView;
        public FrameLayout mPlayerContainer;
        public View mBtnLike, mBtnComment, mBtnCollect, mBtnShare;
        public ImageView mIvLike, mIvCollect;
        /** 作者头像（点了进主页） */
        public ImageView mIvAvatar;
        public TextView mTvLikeCount, mTvCommentCount, mTvCollectCount;

        ViewHolder(View itemView) {
            mTikTokView = itemView.findViewById(R.id.tiktok_view);
            mTitle = mTikTokView.findViewById(R.id.tv_title);
            mThumb = mTikTokView.findViewById(R.id.iv_thumb);
            mPlayerContainer = itemView.findViewById(R.id.container);
            mBtnLike = mTikTokView.findViewById(R.id.btn_like);
            mBtnComment = mTikTokView.findViewById(R.id.btn_comment);
            mBtnCollect = mTikTokView.findViewById(R.id.btn_collect);
            mBtnShare = mTikTokView.findViewById(R.id.btn_share);
            mIvLike = mTikTokView.findViewById(R.id.iv_like);
            mIvCollect = mTikTokView.findViewById(R.id.iv_collect);
            mIvAvatar = mTikTokView.findViewById(R.id.iv_avatar);
            mTvLikeCount = mTikTokView.findViewById(R.id.tv_like_count);
            mTvCommentCount = mTikTokView.findViewById(R.id.tv_comment_count);
            mTvCollectCount = mTikTokView.findViewById(R.id.tv_collect_count);
            itemView.setTag(this);
        }
    }
}
