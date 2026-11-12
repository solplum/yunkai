package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 读屏与输入核实的测试页:
 * - 「结果」文字被标成对无障碍不重要(像计算器的显示区),按「7」只改它;
 * - 「空输入框」点一下只换输入焦点;
 * - 「热词示例」是只显示提示语的搜索框;
 * - 「会换的框」输入后被换成一个新的输入控件(有些 App 内容一变就重建输入视图);
 * - 「假搜索框」只是一段可点的文字,点了才出现真正的输入框并拿到焦点(很多首页的搜索条就是这样)。
 * 测试 APK 的页面运行在测试包自己的进程里、不带 Kotlin 标准库,所以用 Java 写。
 */
public class FieldsTestActivity extends Activity {
    private LinearLayout holder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView result = new TextView(this);
        result.setText("0");
        result.setTextSize(32f);
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(result);
        Button seven = new Button(this);
        seven.setText("7");
        seven.setOnClickListener(v -> result.append("7"));
        root.addView(seven);
        EditText empty = new EditText(this);
        empty.setHint("空输入框");
        empty.setContentDescription(null);
        root.addView(empty);
        EditText hinted = new EditText(this);
        hinted.setHint("热词示例");
        root.addView(hinted);
        holder = new LinearLayout(this);
        root.addView(holder);
        holder.addView(swapping(""), fullWidth());
        EditText real = new EditText(this);
        real.setHint("真正的输入框");
        real.setVisibility(View.GONE);
        TextView fake = new TextView(this);
        fake.setText("假搜索框");
        fake.setTextSize(24f);
        fake.setOnClickListener(v -> {
            real.setVisibility(View.VISIBLE);
            real.requestFocus();
        });
        root.addView(fake);
        root.addView(real);
        setContentView(root);
    }

    private static LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private EditText swapping(CharSequence initial) {
        EditText field = new EditText(this);
        field.setHint("会换的框");
        field.setText(initial);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String text = s == null ? "" : s.toString();
                if (text.isEmpty()) return;
                field.post(() -> {
                    holder.removeAllViews();
                    EditText next = swapping(text);
                    holder.addView(next, fullWidth());
                    next.requestFocus();
                });
            }
        });
        return field;
    }
}
