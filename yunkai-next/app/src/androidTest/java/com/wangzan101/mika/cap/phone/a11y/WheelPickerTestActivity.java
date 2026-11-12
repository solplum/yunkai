package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import java.util.Locale;

/**
 * 滚轮测试页:两个原版数字滚轮(0–59,两位数显示,循环),都从 0 开始。
 * 「分」可以直接输入;「锁住」挡住了里面的输入框(拿不到焦点,写进去的字不会被采用),只能一格一格滚。
 * 测试 APK 的页面运行在测试包自己的进程里、不带 Kotlin 标准库,所以用 Java 写。
 */
public class WheelPickerTestActivity extends Activity {
    public static final String MINUTES = "分";
    public static final String LOCKED = "锁住";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addPicker(row, MINUTES, false);
        addPicker(row, LOCKED, true);
        setContentView(row);
    }

    private void addPicker(LinearLayout row, String label, boolean block) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(20f);
        col.addView(title);
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(0);
        picker.setMaxValue(59);
        picker.setValue(0);
        picker.setFormatter(v -> String.format(Locale.ROOT, "%02d", v));
        picker.setContentDescription(label);
        if (block) picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        col.addView(picker);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }
}
