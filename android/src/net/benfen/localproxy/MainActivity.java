package net.benfen.localproxy;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 本地代理主界面：一颗按钮管开关，状态一眼看完，其余什么都不放。 */
public class MainActivity extends Activity {
    private TextView status;
    private Button toggle;
    private Button permBtn;

    private boolean needPerm() {
        return Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int paper = Color.rgb(0xee, 0xe9, 0xdf);
        int ink = Color.rgb(0x17, 0x17, 0x14);
        int muted = Color.rgb(0x5f, 0x5c, 0x55);
        int seal = Color.rgb(0xa6, 0x2e, 0x24);
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(paper);
        root.setPadding((int) (28 * dp), (int) (40 * dp), (int) (28 * dp), (int) (28 * dp));

        TextView badge = new TextView(this);
        badge.setText("代");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(34);
        badge.setTypeface(Typeface.SERIF, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(seal);
        bg.setCornerRadius(14 * dp);
        badge.setBackground(bg);
        int badgeSize = (int) (68 * dp);
        root.addView(badge, new LinearLayout.LayoutParams(badgeSize, badgeSize));

        TextView title = new TextView(this);
        title.setText("本地代理");
        title.setTextColor(ink);
        title.setTextSize(28);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = (int) (18 * dp);
        root.addView(title, tlp);

        TextView intro = new TextView(this);
        intro.setText("电视机的小帮手。有些频道在电视机里打不开，把它打开就能看了。它只在这台手机上工作，不把你的东西传到外面去。");
        intro.setTextColor(muted);
        intro.setTextSize(15.5f);
        intro.setLineSpacing(0, 1.5f);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = (int) (10 * dp);
        root.addView(intro, ilp);

        status = new TextView(this);
        status.setTextSize(18);
        status.setTypeface(Typeface.SERIF, Typeface.BOLD);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = (int) (30 * dp);
        root.addView(status, slp);

        /* 先讲清再请用户点允许：没这一步，系统框弹得没头没脑，老人家不敢点 */
        permBtn = new Button(this);
        permBtn.setText("先点这里：允许通知（能在通知栏看到它在运行）");
        permBtn.setTextSize(15);
        permBtn.setTextColor(ink);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(Color.rgb(0xe4, 0xde, 0xd2));
        pbg.setCornerRadius(10 * dp);
        permBtn.setBackground(pbg);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (52 * dp));
        plp.topMargin = (int) (14 * dp);
        root.addView(permBtn, plp);
        permBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        });

        toggle = new Button(this);
        toggle.setTextSize(18);
        toggle.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (58 * dp));
        blp.topMargin = (int) (14 * dp);
        root.addView(toggle, blp);
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, ProxyService.class);
                if (ProxyService.isRunning()) {
                    i.setAction(ProxyService.ACTION_STOP);
                    startService(i);
                } else {
                    i.setAction(ProxyService.ACTION_START);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                    else startService(i);
                }
                v.postDelayed(new Runnable() {
                    public void run() { refresh(); }
                }, 350);
            }
        });

        TextView steps = new TextView(this);
        steps.setText("怎么用：\n1. 点上面的按钮把它打开（通知栏会出现一条「运行中」）；\n2. 回到浏览器打开电视机，在设置里打开「本地代理」开关；\n3. 看完，点「关掉代理」就行。");
        steps.setTextColor(muted);
        steps.setTextSize(15);
        steps.setLineSpacing(0, 1.6f);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = (int) (26 * dp);
        root.addView(steps, stlp);

        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (status == null || toggle == null) return;
        if (permBtn != null) permBtn.setVisibility(needPerm() ? View.VISIBLE : View.GONE);
        boolean on = ProxyService.isRunning();
        int seal = Color.rgb(0xa6, 0x2e, 0x24);
        int ink = Color.rgb(0x17, 0x17, 0x14);
        status.setText(on ? "● 正在运行" : "○ 还没打开");
        status.setTextColor(on ? seal : ink);
        toggle.setText(on ? "关掉代理" : "打开代理");
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? ink : seal);
        bg.setCornerRadius(10 * getResources().getDisplayMetrics().density);
        toggle.setBackground(bg);
    }
}
