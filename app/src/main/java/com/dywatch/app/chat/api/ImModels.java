package com.dywatch.app.chat.api;

/**
 * IM 链路的数据模型（纯数据，无 Android 依赖，可 JVM 单测）。
 *
 * 字段来源见 `调研\19` §3 与 `调研\20` §2：
 *   会话 = get_message_by_init 响应的 f1（ConversationMessage.conversations）
 *   消息 = 会话内 f2 列表 / get_message_by_index_v2_range 响应的 f1.f2
 */
public final class ImModels {

    private ImModels() {}

    /** 一个会话（含最近若干条消息） */
    public static final class Conv {
        /** conversation_id，形如 "0:1:&lt;自己uid&gt;:&lt;对方uid&gt;" */
        public String id = "";
        public long shortId;
        /** 会话级 ticket（121 字符）；发送时必须带 */
        public String ticket = "";
        /** 对方 uid（从参与者列表里排除自己后剩下的那个） */
        public long peerUid;
        /** 对方 sec_uid */
        public String peerSecUid = "";
        /** 自己 uid */
        public long selfUid;
        /** 昵称（由 ImUserInfo 批量补，可能为空） */
        public String nickname = "";
        /** 最近消息（旧→新） */
        public final java.util.List<Msg> recent = new java.util.ArrayList<>();

        void sortRecent() {
            java.util.Collections.sort(recent, new java.util.Comparator<Msg>() {
                @Override
                public int compare(Msg a, Msg b) {
                    return Long.compare(a.indexInConversation, b.indexInConversation);
                }
            });
        }

        /** 列表页展示用：最后一条消息的摘要 */
        public String lastText() {
            return recent.isEmpty() ? "" : recent.get(recent.size() - 1).text;
        }

        /** 列表页展示用：最后一条消息的时间戳 */
        public long lastTimeMs() {
            return recent.isEmpty() ? 0L : recent.get(recent.size() - 1).timeMs;
        }

        /** 当前已知的最小 index（翻更早历史用） */
        public long minIndex() {
            long min = Long.MAX_VALUE;
            for (Msg m : recent) if (m.indexInConversation > 0 && m.indexInConversation < min) min = m.indexInConversation;
            return min == Long.MAX_VALUE ? 0L : min;
        }

        /** 展示名：昵称优先，拿不到就退化成 uid */
        public String displayName() {
            if (!nickname.isEmpty()) return nickname;
            return peerUid != 0 ? String.valueOf(peerUid) : id;
        }
    }

    /** 一条消息 */
    public static final class Msg {
        public static final int IN = 0, OUT = 1;

        public int direction = IN;
        /** 文本内容（message_type=7 时有效；其他类型是占位文案） */
        public String text = "";
        public long timeMs;
        /** 原始 message_type：7=文本 5=表情 17=语音 27=图片 8=分享视频 50001=已读回执 */
        public int type;
        public String conversationId = "";
        public long conversationShortId;
        public long senderUid;
        /** 发送者 sec_uid（MessageBody.f14） */
        public String secSender = "";
        /** MessageBody.f4 index_in_conversation（排序与翻页都靠它） */
        public long indexInConversation;
    }

    /**
     * init 响应的分页游标（MessageByInitResponseBody）：f4 version / f5 user_cursor / f6 cmd_index / f7 readconv_version。
     * 目前读链路用不到增量（每次全量 init 拿最近 20 条即够），保留结构以便日后需要。
     */
    public static final class Cursor {
        public long version;
        public long cmdIndex;
        public long readVersion;
        public long userCursor;
        public String source = "0";

        public boolean isEmpty() {
            return version == 0 && cmdIndex == 0 && readVersion == 0;
        }
    }
}
