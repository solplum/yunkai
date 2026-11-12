package com.wangzan101.mika.cap.phone.a11y;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * 在单独的进程里打开 App 的启动页,打开就停。
 * 测试 APK 的组件运行在测试包自己的进程里、不带 Kotlin 标准库,所以用 Java 写。
 */
public class SpillLaunchService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String target = intent != null ? intent.getStringExtra(SpillTestActivity.EXTRA_TARGET) : null;
        Intent launch = target != null ? getPackageManager().getLaunchIntentForPackage(target) : null;
        if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
