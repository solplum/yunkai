package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

/**
 * 点一下按钮,交给另一个进程里的服务({@link SpillLaunchService})带 NEW_TASK 打开 {@link #EXTRA_TARGET} 那个 App 的启动页:
 * 和 App 经后台的进程(服务、SDK、跳转)打开另一个 App 一样,发起的进程在 Mika 的屏上没有页面,系统按默认放到主屏。
 * 用来重现「开到用户主屏上」。(同一进程里从应用上下文打开时,安卓按这个进程最上面页面所在的屏放,不会外溢。)
 * 测试 APK 的页面运行在测试包自己的进程里、不带 Kotlin 标准库,所以用 Java 写。
 */
public class SpillTestActivity extends Activity {
    public static final String EXTRA_TARGET = "target";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String extra = getIntent().getStringExtra(EXTRA_TARGET);
        String target = extra != null ? extra : "com.android.settings";
        Button button = new Button(this);
        button.setText("open elsewhere");
        button.setTextSize(32f);
        button.setOnClickListener(v ->
            startService(new Intent(this, SpillLaunchService.class).putExtra(EXTRA_TARGET, target)));
        setContentView(button);
    }
}
