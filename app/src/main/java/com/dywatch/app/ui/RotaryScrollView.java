package com.dywatch.app.ui;

// 表冠可滚的 ScrollView（移植自 BiliClient 的 RotaryScrollView，GPL-3.0）。
//
// 为什么默认关：部分手表的表冠本来就能通过焦点导航滚动，这里再抢一次事件会双重滚动。
// 所以做成设置里的开关 + 灵敏度，而不是无条件接管。
// 方案 §10.5：表冠是加分项，功能上不依赖它——没有表冠的设备这些页面靠触摸照常滚。

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

import androidx.core.view.ViewConfigurationCompat;

public class RotaryScrollView extends ScrollView {

    public RotaryScrollView(Context context) {
        super(context);
    }

    public RotaryScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RotaryScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        final float multiple = Settings.rotarySensitivity(getContext());
        if (!Settings.rotaryEnabled(getContext()) || multiple <= 0f) {
            setOnGenericMotionListener(null);
            return;
        }
        setOnGenericMotionListener(new OnGenericMotionListener() {
            @Override
            public boolean onGenericMotion(View v, MotionEvent ev) {
                if (ev.getAction() != MotionEvent.ACTION_SCROLL) return false;
                if ((ev.getSource() & InputDevice.SOURCE_ROTARY_ENCODER) == 0) return false;
                float delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL)
                        * ViewConfigurationCompat.getScaledVerticalScrollFactor(
                                ViewConfiguration.get(getContext()), getContext()) * 2;
                smoothScrollBy(0, Math.round(delta * multiple));
                requestFocus();
                return true;
            }
        });
    }
}
