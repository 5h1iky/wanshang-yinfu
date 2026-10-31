package com.dywatch.app.ui;

import android.graphics.Outline;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.dywatch.app.R;

import java.util.ArrayList;
import java.util.List;

/** 视频、用户、文件夹和通知共用的媒体行。 */
public class MediaRowAdapter extends RecyclerView.Adapter<MediaRowAdapter.Holder> {

    public static final int KIND_VIDEO = 0;
    public static final int KIND_USER = 1;
    public static final int KIND_FOLDER = 2;
    public static final int KIND_NOTICE = 3;
    /** 纯文字行（搜索历史）：不显示左侧图标，避免出现一个大感叹号/信息图标。 */
    public static final int KIND_HISTORY = 4;

    public static class Item {
        public final String title;
        public final String meta;
        public final String summary;
        public final String imageUrl;
        public final Object payload;
        public final int kind;

        public Item(String title, String meta, String summary, String imageUrl,
                    Object payload, int kind) {
            this.title = title;
            this.meta = meta;
            this.summary = summary;
            this.imageUrl = imageUrl;
            this.payload = payload;
            this.kind = kind;
        }
    }

    public interface OnRowClick {
        void onClick(Object payload);
    }

    private final List<Item> mItems = new ArrayList<>();
    private final OnRowClick mClick;

    public MediaRowAdapter(OnRowClick click) {
        mClick = click;
    }

    /** 整批替换，列表数据以传入内容为准。 */
    public void submitList(List<Item> list) {
        mItems.clear();
        if (list != null) mItems.addAll(list);
        notifyDataSetChanged();
    }

    /** 纯追加，避免分页时打断当前滚动位置。 */
    public void append(List<Item> list) {
        if (list == null || list.isEmpty()) return;
        int from = mItems.size();
        mItems.addAll(list);
        notifyItemRangeInserted(from, list.size());
    }

    public void clear() {
        int count = mItems.size();
        mItems.clear();
        if (count > 0) notifyItemRangeRemoved(0, count);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_media_row, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final Item item = mItems.get(position);
        holder.title.setSingleLine(item.kind != KIND_VIDEO);
        holder.title.setMaxLines(item.kind == KIND_VIDEO ? 2 : 1);
        // 搜索历史是纯文字行：没有图标列，标题占满整行。
        holder.image.setVisibility(item.kind == KIND_HISTORY ? View.GONE : View.VISIBLE);
        holder.title.setText(item.title == null ? "" : item.title);
        holder.meta.setText(item.meta == null ? "" : item.meta);
        holder.summary.setText(item.summary == null ? "" : item.summary);
        holder.summary.setVisibility(TextUtils.isEmpty(item.summary) ? View.GONE : View.VISIBLE);

        // 统一按界面草案的媒体卡字号：标题 12sp、元信息 10sp、摘要 11sp。
        Fonts.scale(holder.title, R.dimen.t_card_title);
        Fonts.scale(holder.meta, R.dimen.t_card_meta);
        Fonts.scale(holder.summary, R.dimen.t_card_summary);
        bindImage(holder, item);

        holder.itemView.setOnClickListener(v -> {
            if (mClick != null) mClick.onClick(item.payload);
        });
    }

    private void bindImage(@NonNull Holder holder, @NonNull Item item) {
        if (item.kind == KIND_HISTORY) {
            Glide.with(holder.image).clear(holder.image);
            return;
        }
        boolean video = item.kind == KIND_VIDEO;
        int width = video ? 89 : 38;
        int height = video ? 63 : 38;
        holder.image.getLayoutParams().width = Rows.dp(holder.image.getContext(), width);
        holder.image.getLayoutParams().height = Rows.dp(holder.image.getContext(), height);
        holder.image.requestLayout();

        holder.image.setBackgroundColor(androidx.core.content.ContextCompat.getColor(
                holder.image.getContext(), R.color.bg_surface));
        if (item.kind == KIND_FOLDER || item.kind == KIND_NOTICE) {
            // 取消上一个复用项的图片请求，避免迟到的网络图覆盖当前图标。
            Glide.with(holder.image).clear(holder.image);
            holder.image.setClipToOutline(false);
            holder.image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            holder.image.setImageResource(item.kind == KIND_FOLDER
                    ? android.R.drawable.ic_menu_agenda : android.R.drawable.ic_dialog_info);
            return;
        }

        holder.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (video) {
            // 草案 .thumb：8px 圆角
            holder.image.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            Rows.dp(view.getContext(), 8));
                }
            });
            holder.image.setClipToOutline(true);
        } else {
            // 用户头像：圆形
            holder.image.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            holder.image.setClipToOutline(true);
        }
        int placeholder = item.kind == KIND_USER ? R.drawable.ic_avatar : R.drawable.ic_play;
        if (item.kind == KIND_USER) {
            Glide.with(holder.image)
                    .load(item.imageUrl)
                    .circleCrop()
                    .placeholder(placeholder)
                    .error(placeholder)
                    .into(holder.image);
        } else {
            Glide.with(holder.image)
                    .load(item.imageUrl)
                    .centerCrop()
                    .placeholder(placeholder)
                    .error(placeholder)
                    .into(holder.image);
        }
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    public static class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title;
        final TextView meta;
        final TextView summary;

        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.media_row_image);
            title = itemView.findViewById(R.id.media_row_title);
            meta = itemView.findViewById(R.id.media_row_meta);
            summary = itemView.findViewById(R.id.media_row_summary);
            image.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
        }
    }
}
