package com.dywatch.app.ui;

// 表冠可滚的 RecyclerView。
//
// 为什么要有它：评论页原本用 RotaryScrollView（extends ScrollView）。迁到 RecyclerView 后
// 那个类用不上了，但表冠是手表上最省力的滚动方式，不能因为换列表实现就丢掉。
// RecyclerView 不是 ScrollView 子类，两者的滚动语义也不同（RV 有回收、有 fling、
// 有 LayoutManager），所以这里照 RotaryScrollView 的思路单独实现一份给 RV 用。
//
// 与 RotaryScrollView 保持一致的两个约定：
// ① 默认关（Settings.rotaryEnabled）——有些表的表冠本来就能靠焦点导航滚动，再抢一次会双重滚；
// ② 只认 ACTION_SCROLL + SOURCE_ROTARY_ENCODER，灵敏度读同一个设置。

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.core.view.ViewConfigurationCompat;
import androidx.recyclerview.widget.RecyclerView;

public class RotaryRecyclerView extends RecyclerView {

    public RotaryRecyclerView(Context context) {
        super(context);
    }

    public RotaryRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RotaryRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
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
                // RV 的滚动走自己的 scrollBy：用 smoothScrollBy 会和回收/fling 打架
                scrollBy(0, Math.round(delta * multiple));
                return true;
            }
        });
    }
}
