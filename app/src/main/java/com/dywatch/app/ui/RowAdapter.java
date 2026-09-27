package com.dywatch.app.ui;

// 通用列表适配器：评论行与会话行形状相同（昵称 / 元信息 / 摘要），共用一份。
//
// 关于稳定 id：桥那边给不出可信的唯一 id —— 评论只有"昵称+正文+时间"，
// 会话的 key 是 DOM 下标（chat_bridge.js 里 String(i)），列表重排后全变。
// 所以这里不做 DiffUtil（没有稳定 id 它检测不了 move，只会退化成全量 remove+insert），
// 而是让调用方按语义选择通知方式：
//   追加用 notifyItemRangeInserted（评论分页就是纯追加，位置天然不丢）
//   整批替换用 submitList 全量通知（会话/聊天）

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.dywatch.app.R;

import java.util.ArrayList;
import java.util.List;

public class RowAdapter extends RecyclerView.Adapter<RowAdapter.Holder> {

    /** 一行的数据：标题 / 右侧元信息 / 下方摘要 */
    public static class Item {
        public final String title, meta, summary;
        /** 点击回调携带的载荷（会话对象 / 评论对象） */
        public final Object payload;

        public Item(String title, String meta, String summary, Object payload) {
            this.title = title;
            this.meta = meta;
            this.summary = summary;
            this.payload = payload;
        }
    }

    public interface OnRowClick {
        void onClick(Object payload);
    }

    private final List<Item> mItems = new ArrayList<>();
    private final OnRowClick mClick;

    public RowAdapter(OnRowClick click) {
        mClick = click;
    }

    /** 整批替换（会话列表：每次回来重拉，条数不大） */
    public void submitList(List<Item> list) {
        mItems.clear();
        if (list != null) mItems.addAll(list);
        notifyDataSetChanged();
    }

    /** 追加一批（评论分页：增量抓取，靠它天然保住滚动位置） */
    public void append(List<Item> list) {
        if (list == null || list.isEmpty()) return;
        int from = mItems.size();
        mItems.addAll(list);
        notifyItemRangeInserted(from, list.size());
    }

    public void clear() {
        int n = mItems.size();
        mItems.clear();
        if (n > 0) notifyItemRangeRemoved(0, n);
    }

    public int size() {
        return mItems.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_row, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        final Item it = mItems.get(position);
        h.title.setText(it.title);
        h.meta.setText(it.meta == null ? "" : it.meta);
        if (it.summary == null || it.summary.isEmpty()) {
            h.summary.setVisibility(View.GONE);
        } else {
            h.summary.setVisibility(View.VISIBLE);
            h.summary.setText(it.summary);
        }
        // 方案 C：行正文（昵称/摘要）= 阅读面，吃「字体大小」设置；右侧元信息（时间戳）是
        // chrome，不跟——否则整行高度跟着涨，列表可视行数变少。
        Fonts.scale(h.title, R.dimen.t_body);
        Fonts.scale(h.summary, R.dimen.t_body);
        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mClick != null) mClick.onClick(it.payload);
            }
        });
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    public static class Holder extends RecyclerView.ViewHolder {
        public final TextView title, meta, summary;

        Holder(View v) {
            super(v);
            title = v.findViewById(R.id.row_title);
            meta = v.findViewById(R.id.row_meta);
            summary = v.findViewById(R.id.row_summary);
        }
    }
}
