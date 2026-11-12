package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 * 普通页面,用来模拟另一个 App 抢到前台。
 * 测试 APK 的页面运行在测试包自己的进程里、不带 Kotlin 标准库,所以用 Java 写。
 */
public class PlainTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("plain page");
        text.setTextSize(32f);
        setContentView(text);
    }
}
