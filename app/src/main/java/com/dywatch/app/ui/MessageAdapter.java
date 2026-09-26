package com.dywatch.app.ui;

// 聊天消息适配器：气泡按方向左右分栏（我靠右 / 对方靠左）。
// 复用 item_message.xml，方向靠 LayoutParams 的 gravity 与 margin 切换，
// 避免再维护一份镜像布局。

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.dywatch.app.R;
import com.dywatch.app.chat.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.Holder> {

    private final List<ChatMessage> mItems = new ArrayList<>();

    /** 整批替换（桥每 5s 回全量；条数有限，重建开销可接受） */
    public void submitList(List<ChatMessage> list) {
        mItems.clear();
        if (list != null) mItems.addAll(list);
        notifyDataSetChanged();
    }

    /**
     * 本地回声：刚发出的消息先显示出来（等桥轮询回来才有真数据，那之前不能空白）。
     * 只追加不落 mAll——下次 submitList 会用服务端数据整批替换，不会重复。
     */
    public void appendLocal(List<ChatMessage> list) {
        if (list == null || list.isEmpty()) return;
        int from = mItems.size();
        mItems.addAll(list);
        notifyItemRangeInserted(from, list.size());
    }

    public int size() {
        return mItems.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        ChatMessage m = mItems.get(position);
        boolean out = m.direction == ChatMessage.OUT;

        // 我方靠右、对方靠左：根布局本身就是 LinearLayout，直接设 gravity 即可。
        // ⚠️ 不要去改 getLayoutParams()——RV 给 item 根的是 RecyclerView.LayoutParams，
        //    强转 LinearLayout.LayoutParams 会 ClassCastException（真机踩过）。
        h.col.setGravity(out ? Gravity.END : Gravity.START);
        int side = Rows.dp(h.col.getContext(), 80);
        int edge = Rows.dp(h.col.getContext(), 8);
        h.col.setPadding(out ? side : edge, edge, out ? edge : side, edge);

        h.text.setBackgroundResource(out ? R.drawable.bg_bubble_out : R.drawable.bg_bubble_in);
        h.text.setText(m.text);
        h.text.setGravity(out ? Gravity.END : Gravity.START);
        h.text.setTextColor(ContextCompat.getColor(h.text.getContext(), R.color.text_primary));

        h.meta.setText((out ? "我" : "对方")
                + (m.timeText == null || m.timeText.isEmpty() ? "" : (" · " + m.timeText)));
        h.meta.setGravity(out ? Gravity.END : Gravity.START);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    public static class Holder extends RecyclerView.ViewHolder {
        public final LinearLayout col;
        public final TextView meta, text;

        Holder(View v) {
            super(v);
            col = v.findViewById(R.id.msg_col);
            meta = v.findViewById(R.id.msg_meta);
            text = v.findViewById(R.id.msg_text);
        }
    }
}
