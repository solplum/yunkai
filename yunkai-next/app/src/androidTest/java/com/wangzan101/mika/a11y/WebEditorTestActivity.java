package com.wangzan101.mika.a11y;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 网页里的富文本编辑区(contenteditable),很多 App 的笔记、评论框是这样做的。
 * 编辑器只认浏览器的 input 事件:上方文字显示收到的 input 事件次数和当前内容,测试据此判断编辑器是否真的收到了输入。
 * 测试 APK 单独运行在自己的进程里、不带 Kotlin 标准库,所以这个页面用 Java 写。
 */
public class WebEditorTestActivity extends Activity {
    private TextView status;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setText("inputs=0 text=");
        status.setTextSize(20f);
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.addJavascriptInterface(new Bridge(), "bridge");
        web.loadDataWithBaseURL(null,
            "<html><body><div id=\"ed\" contenteditable=\"true\" aria-label=\"正文\" style=\"min-height:200px;border:1px solid #888\"></div>"
                + "<script>var n=0;document.getElementById('ed').addEventListener('input',function(e){n++;bridge.report(n,e.target.innerText);});</script>"
                + "</body></html>",
            "text/html", "utf-8", null);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(status);
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 800));
        setContentView(root);
    }

    private class Bridge {
        @JavascriptInterface
        public void report(int n, String text) {
            runOnUiThread(() -> status.setText("inputs=" + n + " text=" + text.trim()));
        }
    }
}
