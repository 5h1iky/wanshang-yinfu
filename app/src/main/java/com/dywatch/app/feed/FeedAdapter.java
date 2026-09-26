package com.dywatch.app.feed;

// 源自 DKVideoPlayer demo 的 Tiktok2Adapter（Apache-2.0，Copyright Doikki）——
// 按本项目数据模型改写：视图复用池 + 预加载任务绑定保留，Glide 封面加载保留；
// 新增：操作栏绑定（赞/评/藏计数 + 互动状态）与互动回调。
// License: Apache-2.0（见 lib/LICENSE-DKVideoPlayer.txt）

import android.content.Context;
import android.graphics.Color;
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

    /** 互动回调（点赞/评论/收藏/分享） */
    public interface ActionListener {
        void onLike(FeedVideo video, ViewHolder holder);
        void onComment(FeedVideo video);
        void onCollect(FeedVideo video, ViewHolder holder);
        void onShare(FeedVideo video);
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
                .into(viewHolder.mThumb);
        viewHolder.mTitle.setText(item.title);
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
        container.addView(view);
        return view;
    }

    /** 绑定操作栏数据与互动状态（互动回调后可局部刷新复用） */
    public static void bindActions(ViewHolder h, FeedVideo v) {
        h.mTvLikeCount.setText(FeedVideo.formatCount(v.diggCount));
        h.mTvCommentCount.setText(FeedVideo.formatCount(v.commentCount));
        h.mTvCollectCount.setText(v.collected ? "已藏" : "收藏");
        h.mIvLike.setColorFilter(v.liked ? Color.parseColor("#FFFF3B5C") : Color.WHITE);
        h.mIvCollect.setColorFilter(v.collected ? Color.parseColor("#FFFFC107") : Color.WHITE);
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
            mTvLikeCount = mTikTokView.findViewById(R.id.tv_like_count);
            mTvCommentCount = mTikTokView.findViewById(R.id.tv_comment_count);
            mTvCollectCount = mTikTokView.findViewById(R.id.tv_collect_count);
            itemView.setTag(this);
        }
    }
}
