package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 带防截屏标志的页面:虚拟屏上画成黑色,但无障碍树仍可读。
 * 测试 APK 的页面运行在测试包自己的进程里、不带 Kotlin 标准库,所以用 Java 写。
 */
public class SecureTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        TextView text = new TextView(this);
        text.setText("secure page");
        text.setTextSize(32f);
        setContentView(text);
    }
}
