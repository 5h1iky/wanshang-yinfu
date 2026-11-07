package com.dywatch.app.chat;

/**
 * 发送在途状态机（纯逻辑，JVM 可测）。
 *
 * 目的：同一时刻只允许一条发送；超时/失败后旧回调（迟到的 success/fail）
 * 不能改到下一次发送的状态。每次 begin 分配递增 token，finish 必须带对 token。
 */
public final class ChatSendGuard {

    private boolean mSending;
    private long mToken;

    /** @return 非 0 = 成功拿到本次发送的 token；0 = 上一条还在途 */
    public long begin() {
        if (mSending) return 0L;
        mSending = true;
        return ++mToken;
    }

    public boolean isSending() {
        return mSending;
    }

    public long currentToken() {
        return mToken;
    }

    public boolean isCurrent(long token) {
        return mSending && token != 0L && token == mToken;
    }

    /** @return false = token 不匹配（迟到/重复回调），调用方应直接丢弃 */
    public boolean finish(long token) {
        if (!isCurrent(token)) return false;
        mSending = false;
        return true;
    }

    /** 页面销毁：解除在途状态，并让之后所有旧 token 失效。 */
    public void cancel() {
        mSending = false;
        mToken++;
    }
}
