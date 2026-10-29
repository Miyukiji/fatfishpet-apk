package com.dsha.whalepet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用内配置页。
 *
 * 排版原则：屏幕小的时候整页可滚动、区块之间不堆空白（上一版被吐槽"界面太长"），
 * 所有「要你授权一次」的东西（悬浮窗、全屏收起、Key、三击目标、数据源）都放在同一屏。
 */
public class MainActivity extends Activity {

    private TextView overlayState;
    private TextView keyState;
    private TextView targetValue;
    private TextView accessState;
    private TextView a11yState;
    private TextView batteryState;
    private TextView acctState;
    private TextView acctBtn;
    private EditText baseInput;

    private TextView pluginState;
    private TextView startBtn;

    private EditText keyInput;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 把自定义数据源应用到静态客户端（冷启动也要生效）
        AccountSession.rotateIfNeeded(this);
        Bridge.setBase(prefs().getString(PetService.KEY_BASE_URL, ""));
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        refreshBridgeState();
        refreshPluginState();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ==========================================================================

    /** 外壳：固定标题 + 可切页的内容区 + 固定底栏（召唤/休息 + 三个分类）。 */
    private View buildUi() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(0xFF0F1420);
        int pad = dp(16);

        TextView title = new TextView(this);
        title.setText(getString(R.string.main_title));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(pad, dp(14), pad, dp(2));
        outer.addView(title);

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.main_subtitle));
        sub.setTextColor(0xFF93A2BF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        sub.setPadding(pad, 0, pad, dp(6));
        outer.addView(sub);

        FrameLayout content = new FrameLayout(this);
        outer.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        final View[] pages = new View[]{pageLook(), pagePerm(), pageData()};
        for (View page : pages) {
            page.setVisibility(View.GONE);
            content.addView(page);
        }

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xFF131A28);
        bottom.setPadding(pad, dp(8), pad, dp(12));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = button(getString(R.string.main_btn_summon), R.drawable.btn_primary, Color.WHITE);
        startBtn.setOnClickListener(v -> {
            AccountSession.saveApiKey(this, keyInput.getText().toString().trim());
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, getString(R.string.main_toast_need_overlay), Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, PetService.class);
            i.setAction(PetService.ACTION_START);
            startService(i);
            Toast.makeText(this,
                    prefs().getBoolean("enabled", false)
                            ? getString(R.string.main_toast_already)
                            : getString(R.string.main_toast_summoned),
                    Toast.LENGTH_SHORT).show();
        });
        actionRow.addView(startBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView stop = button(getString(R.string.main_btn_rest), R.drawable.btn_ghost, 0xFFD6E1F5);
        stop.setOnClickListener(v -> {
            if (!prefs().getBoolean("enabled", false)) {
                Toast.makeText(this, getString(R.string.main_toast_not_running), Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, PetService.class);
            i.setAction(PetService.ACTION_STOP);
            startService(i);
            Toast.makeText(this, getString(R.string.main_toast_resting), Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f);
        slp.leftMargin = dp(6);
        actionRow.addView(stop, slp);
        bottom.addView(actionRow);

        final String[] names = {getString(R.string.main_tab_home),
                getString(R.string.main_tab_perm), getString(R.string.main_tab_data)};
        final TextView[] tabs = new TextView[names.length];
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tabRowLp.topMargin = dp(8);
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            tabs[i] = button(names[i], R.drawable.btn_ghost, 0xFF93A2BF);
            tabs[i].setOnClickListener(v -> showPage(pages, tabs, index));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) tlp.leftMargin = dp(6);
            tabRow.addView(tabs[i], tlp);
        }
        bottom.addView(tabRow, tabRowLp);
        outer.addView(bottom);

        showPage(pages, tabs, 0);
        return outer;
    }

    private void showPage(View[] pages, TextView[] tabs, int index) {
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            tabs[i].setBackgroundResource(i == index
                    ? R.drawable.btn_primary : R.drawable.btn_ghost);
            tabs[i].setTextColor(i == index ? Color.WHITE : 0xFF93A2BF);
        }
    }

    /** 包一层滚动容器。 */
    private View page(LinearLayout body) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body);
        return scroll;
    }

    private LinearLayout body() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(6), pad, dp(16));
        return root;
    }

    // ---- 页 1：外观 ----
    private View pageLook() {
        LinearLayout root = body();

        section(root, getString(R.string.main_sec_size));
        final int[] tiers = PetService.SIZE_TIERS;
        final LinearLayout tierRow = new LinearLayout(this);
        tierRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] cells = new TextView[tiers.length];
        int saved = PetService.snapSize(
                prefs().getInt(PetService.KEY_SIZE, PetService.SIZE_DEFAULT));
        for (int i = 0; i < tiers.length; i++) {
            final int dpVal = tiers[i];
            final int index = i;
            // 名称按档位序号取，别写死 dp 阈值（改档位时这里不用跟着改）
            String name = index == 0 ? getString(R.string.main_size_small)
                    : index == tiers.length - 1 ? getString(R.string.main_size_large)
                    : getString(R.string.main_size_mid);
            TextView cell = button(name, R.drawable.btn_ghost, 0xFFD6E1F5);
            cell.setOnClickListener(v -> {
                prefs().edit().putInt(PetService.KEY_SIZE, dpVal).apply();
                paintTiers(cells, dpVal);
                Intent i2 = new Intent(MainActivity.this, PetService.class);
                i2.setAction(PetService.ACTION_RESIZE);
                try {
                    startService(i2);
                } catch (Throwable t) { Log.ignored("MainActivity", t);
                    // 服务没跑就下次启动按新尺寸建窗口
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (index > 0) clp.leftMargin = dp(5);
            tierRow.addView(cell, clp);
            cells[i] = cell;
        }
        root.addView(tierRow, lp(0, dp(6), 0, 0));
        paintTiers(cells, saved);

        section(root, getString(R.string.main_sec_triple_app));
        targetValue = new TextView(this);
        style(targetValue);
        root.addView(targetValue);
        TextView pick = button(getString(R.string.main_btn_pick_app), R.drawable.btn_ghost, 0xFFD6E1F5);
        pick.setOnClickListener(v -> pickApp());
        root.addView(pick, lp(0, dp(6), 0, 0));

        section(root, getString(R.string.main_sec_actions));
        final LinearLayout actionBox = new LinearLayout(this);
        actionBox.setOrientation(LinearLayout.VERTICAL);
        actionBox.setVisibility(View.GONE);
        final TextView actionToggle = button(getString(R.string.main_btn_expand_actions),
                R.drawable.btn_ghost, 0xFFD6E1F5);
        actionToggle.setOnClickListener(v -> {
            boolean show = actionBox.getVisibility() != View.VISIBLE;
            actionBox.setVisibility(show ? View.VISIBLE : View.GONE);
            actionToggle.setText(getString(show ? R.string.main_btn_collapse_actions_count
                    : R.string.main_btn_expand_actions_count, actionBox.getChildCount()));
        });
        root.addView(actionToggle, lp(0, dp(6), 0, 0));
        root.addView(actionBox);
        fillActionList(actionBox);
        actionToggle.setText(getString(R.string.main_btn_expand_actions_count,
                actionBox.getChildCount()));

        section(root, getString(R.string.main_sec_assets));
        TextView credit = new TextView(this);
        credit.setText(getString(R.string.main_credit));
        credit.setTextColor(0xFF93A2BF);
        credit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        credit.setLineSpacing(dp(2), 1f);
        root.addView(credit);
        TextView repo = new TextView(this);
        repo.setText("https://github.com/PC2005-cloud/dsh-pet");
        repo.setTextColor(0xFF7FB2FF);
        repo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        repo.setPadding(0, dp(4), 0, 0);
        repo.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/PC2005-cloud/dsh-pet")));
            } catch (Throwable t) { Log.ignored("MainActivity", t);
                // 没有浏览器
            }
        });
        root.addView(repo);

        // 另一个上游也要署名；资产许可详见「使用说明 → 素材与许可」
        TextView repo2 = new TextView(this);
        repo2.setText("https://github.com/yanzwzz/dsh-whale-girl-pet");
        repo2.setTextColor(0xFF7FB2FF);
        repo2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        repo2.setPadding(0, dp(2), 0, 0);
        repo2.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/yanzwzz/dsh-whale-girl-pet")));
            } catch (Throwable t) { Log.ignored("MainActivity", t);
                // 没有浏览器
            }
        });
        root.addView(repo2);

        TextView help = button(getString(R.string.main_btn_help), R.drawable.btn_ghost, 0xFFD6E1F5);
        help.setOnClickListener(v -> startActivity(new Intent(this, HelpActivity.class)));
        root.addView(help, lp(0, dp(14), 0, 0));
        return page(root);
    }

    // ---- 页 2：权限与收纳 ----
    private View pagePerm() {
        LinearLayout root = body();

        section(root, getString(R.string.main_sec_overlay));
        overlayState = new TextView(this);
        style(overlayState);
        root.addView(overlayState);
        TextView grant = button(getString(R.string.main_btn_grant), R.drawable.btn_ghost, 0xFFD6E1F5);
        grant.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                Toast.makeText(this, getString(R.string.main_toast_no_overlay_settings), Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(grant, lp(0, dp(6), 0, 0));

        section(root, getString(R.string.main_sec_usage_access));
        accessState = new TextView(this);
        style(accessState);
        root.addView(accessState);
        TextView usageBtn = button(getString(R.string.main_btn_grant_usage), R.drawable.btn_ghost, 0xFFD6E1F5);
        usageBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, getString(R.string.main_toast_no_usage_settings), Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(usageBtn, lp(0, dp(6), 0, 0));

        TextView usageNote = new TextView(this);
        usageNote.setText(getString(R.string.main_note_usage_access));
        usageNote.setTextColor(0xFF93A2BF);
        usageNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        usageNote.setLineSpacing(dp(2), 1f);
        usageNote.setPadding(0, dp(8), 0, 0);
        root.addView(usageNote);

        section(root, getString(R.string.main_sec_a11y));
        a11yState = new TextView(this);
        style(a11yState);
        root.addView(a11yState);
        TextView a11yBtn = button(getString(R.string.main_btn_grant_a11y), R.drawable.btn_ghost, 0xFFD6E1F5);
        a11yBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, getString(R.string.main_toast_no_a11y_settings), Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(a11yBtn, lp(0, dp(6), 0, 0));

        final TextView a11yToggle = button("", R.drawable.btn_ghost, 0xFFD6E1F5);
        paintA11yToggle(a11yToggle);
        a11yToggle.setOnClickListener(v -> {
            boolean next = !prefs().getBoolean(PetService.KEY_A11Y_HIDE, true);
            prefs().edit().putBoolean(PetService.KEY_A11Y_HIDE, next).apply();
            paintA11yToggle(a11yToggle);
        });
        root.addView(a11yToggle, lp(0, dp(6), 0, 0));

        section(root, getString(R.string.main_sec_battery));
        batteryState = new TextView(this);
        style(batteryState);
        root.addView(batteryState);
        TextView batteryBtn = button(getString(R.string.main_btn_grant_battery), R.drawable.btn_ghost, 0xFFD6E1F5);
        batteryBtn.setOnClickListener(v -> requestBatteryWhitelist());
        root.addView(batteryBtn, lp(0, dp(6), 0, 0));

        section(root, getString(R.string.main_sec_location));
        TextView locBtn = button(getString(R.string.main_btn_allow_location), R.drawable.btn_ghost, 0xFFD6E1F5);
        locBtn.setOnClickListener(v -> {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, getString(R.string.common_granted), Toast.LENGTH_SHORT).show();
                return;
            }
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, 42);
        });
        root.addView(locBtn, lp(0, dp(6), 0, 0));

        return page(root);
    }

    // ---- 页 3：数据与插件 ----
    private View pageData() {
        LinearLayout root = body();

        section(root, getString(R.string.main_sec_api_key));
        keyInput = input("sk-…", AccountSession.apiKey(this), true);
        TextView saveKey = button(getString(R.string.common_save), R.drawable.btn_ghost, 0xFFD6E1F5);
        saveKey.setOnClickListener(v -> {
            AccountSession.saveApiKey(this, keyInput.getText().toString().trim());
            keyState.setText(getString(R.string.common_saved));
            refreshBridgeState();
        });
        root.addView(inline(keyInput, saveKey));
        keyState = new TextView(this);
        keyState.setTextColor(0xFF93A2BF);
        keyState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        keyState.setPadding(0, dp(2), 0, 0);
        root.addView(keyState);

        section(root, getString(R.string.main_sec_data_source));
        baseInput = input("http://192.0.2.10:3080", prefs().getString(PetService.KEY_BASE_URL, ""), false);
        TextView saveBase = button(getString(R.string.common_save), R.drawable.btn_ghost, 0xFFD6E1F5);
        saveBase.setOnClickListener(v -> {
            String v2 = baseInput.getText().toString().trim();
            prefs().edit().putString(PetService.KEY_BASE_URL, v2).apply();
            Bridge.setBase(v2);
            Toast.makeText(this, v2.isEmpty()
                    ? getString(R.string.main_toast_restored_local)
                    : getString(R.string.common_saved), Toast.LENGTH_SHORT).show();
            refreshBridgeState();
        });
        root.addView(inline(baseInput, saveBase));

        section(root, getString(R.string.main_sec_account));
        acctState = new TextView(this);
        style(acctState);
        root.addView(acctState);
        acctBtn = button(getString(R.string.common_login), R.drawable.btn_ghost, 0xFFD6E1F5);
        acctBtn.setOnClickListener(v -> {
            if (AccountSession.has(this)) {
                AccountSession.clear(this);
                clearWebSession();
                prefs().edit().remove("acctBal").apply();   // 退出登录别再用旧账户余额
                updateStatus();
            } else {
                try {
                    startActivity(new Intent(this, AccountLoginActivity.class));
                } catch (Throwable t) {
                    Toast.makeText(this, getString(R.string.main_toast_no_login_page), Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(acctBtn, lp(0, dp(6), 0, 0));

        section(root, getString(R.string.main_sec_website));
        TextView openSite = button(getString(R.string.main_btn_open_website), R.drawable.btn_ghost, 0xFFD6E1F5);
        openSite.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://platform.deepseek.com/usage")));
            } catch (Throwable t) {
                Toast.makeText(this, getString(R.string.main_toast_no_browser), Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(openSite, lp(0, dp(6), 0, 0));

        section(root, getString(R.string.main_sec_plugin));
        pluginState = new TextView(this);
        style(pluginState);
        root.addView(pluginState);
        TextView exportBtn = button(getString(R.string.main_btn_export_plugin), R.drawable.btn_ghost, 0xFFD6E1F5);
        exportBtn.setOnClickListener(v -> exportBridgePlugin());
        TextView copyBtn = button(getString(R.string.main_btn_install_cmd), R.drawable.btn_ghost, 0xFFD6E1F5);
        copyBtn.setOnClickListener(v -> copyInstallCmd());
        LinearLayout pluginRow = new LinearLayout(this);
        pluginRow.setOrientation(LinearLayout.HORIZONTAL);
        pluginRow.addView(exportBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        plp.leftMargin = dp(6);
        pluginRow.addView(copyBtn, plp);
        root.addView(pluginRow, lp(0, dp(6), 0, 0));

        TextView foot = new TextView(this);
        foot.setText(getString(R.string.main_foot, versionName()));
        foot.setTextColor(0xFF5C6B85);
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        foot.setPadding(0, dp(10), 0, 0);
        root.addView(foot);
        return page(root);
    }

    /**
     * 选应用的对话框：搜索框 + 按最近使用排序（以前是一长串字母序，找一个要翻半天）。
     */
    private void pickApp() {
        final java.util.List<String> labels = new ArrayList<>();
        final java.util.List<String> pkgs = new ArrayList<>();
        try {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(main, 0);
            java.util.Map<String, Long> lastUsed = recentUsage();
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || pkgs.contains(pkg)) continue;
                try {
                    android.content.pm.ApplicationInfo ai =
                            getPackageManager().getApplicationInfo(pkg, 0);
                    boolean system = (ai.flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM
                            | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                    if (system) continue;   // 系统应用不进列表
                } catch (Throwable t) { Log.ignored("MainActivity", t);
                    continue;
                }
                pkgs.add(pkg);
                labels.add(labelOf(ri));
            }
            // 按最近使用时间倒序（拿不到使用情况时退回字母序）
            Integer[] order = new Integer[pkgs.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            final java.util.Map<String, Long> lu = lastUsed;
            java.util.Arrays.sort(order, (a, b) -> {
                long ta = lu.containsKey(pkgs.get(a)) ? lu.get(pkgs.get(a)) : 0L;
                long tb = lu.containsKey(pkgs.get(b)) ? lu.get(pkgs.get(b)) : 0L;
                if (ta != tb) return Long.compare(tb, ta);
                return labels.get(a).compareToIgnoreCase(labels.get(b));
            });
            java.util.List<String> sl = new ArrayList<>();
            java.util.List<String> sp = new ArrayList<>();
            for (Integer i : order) {
                sl.add(labels.get(i));
                sp.add(pkgs.get(i));
            }
            labels.clear(); labels.addAll(sl);
            pkgs.clear(); pkgs.addAll(sp);
        } catch (Throwable t) { Log.ignored("MainActivity", t);
            // 列表拿不到就什么都不做
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, getString(R.string.main_toast_no_apps), Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(4));

        final EditText search = input(getString(R.string.main_hint_search), "", false);
        box.addView(search, lp(0, 0, 0, 8));

        final android.widget.ListView lv = new android.widget.ListView(this);
        final java.util.List<Integer> visible = new ArrayList<>();
        final android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                        new ArrayList<String>());
        lv.setAdapter(adapter);
        box.addView(lv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360)));

        final Runnable refilter = new Runnable() {
            @Override
            public void run() {
                String q = search.getText().toString().trim().toLowerCase(Locale.US);
                visible.clear();
                adapter.clear();
                for (int i = 0; i < labels.size(); i++) {
                    if (q.isEmpty()
                            || labels.get(i).toLowerCase(Locale.US).contains(q)
                            || pkgs.get(i).toLowerCase(Locale.US).contains(q)) {
                        visible.add(i);
                        adapter.add(labels.get(i));
                    }
                }
                adapter.notifyDataSetChanged();
            }
        };
        refilter.run();

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) { refilter.run(); }
        });

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.main_dlg_pick_title))
                .setView(box)
                .setNegativeButton(getString(R.string.common_cancel), null)
                .create();

        lv.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visible.size()) return;
            int i = visible.get(position);
            prefs().edit().putString(PetService.KEY_TARGET_PKG, pkgs.get(i)).apply();
            updateTargetLabel();
            dialog.dismiss();
        });
        dialog.show();
    }

    /** 包名 → 最近使用时间（毫秒）。需要「使用情况访问」权限，没授权就返回空表。 */
    private java.util.Map<String, Long> recentUsage() {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        if (!Foreground.hasPermission(this)) return out;
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (usm == null) return out;
            long now = System.currentTimeMillis();
            java.util.List<android.app.usage.UsageStats> stats =
                    usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                            now - 7L * 24 * 3600 * 1000, now);
            if (stats == null) return out;
            for (android.app.usage.UsageStats u : stats) {
                if (u == null) continue;
                out.put(u.getPackageName(), Math.max(
                        out.containsKey(u.getPackageName()) ? out.get(u.getPackageName()) : 0L,
                        u.getLastTimeUsed()));
            }
        } catch (Throwable t) { Log.ignored("MainActivity", t);
            // 没权限/异常：退回字母序
        }
        return out;
    }

    private String labelOf(ResolveInfo ri) {
        try {
            CharSequence l = ri.loadLabel(getPackageManager());
            if (l != null && l.length() > 0) return l.toString();
        } catch (Throwable t) { Log.ignored("MainActivity", t);
            // 退到包名
        }
        return ri.activityInfo == null ? "?" : ri.activityInfo.packageName;
    }

    private void updateTargetLabel() {
        String pkg = prefs().getString(PetService.KEY_TARGET_PKG, "");
        String label = pkg;
        try {
            label = String.valueOf(getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Throwable t) { Log.ignored("MainActivity", t);
            // 没装就显示包名
        }
        boolean installed = false;
        try {
            installed = getPackageManager().getLaunchIntentForPackage(pkg) != null;
        } catch (Throwable t) { Log.ignored("MainActivity", t);
            installed = false;
        }
        if (pkg == null || pkg.trim().isEmpty()) {
            targetValue.setText(getString(R.string.main_target_unset));
            return;
        }
        targetValue.setText(getString(R.string.main_target_line, label, pkg,
                installed ? "" : getString(R.string.main_target_uninstalled)));
    }

    /** 导出文件名带版本号，必须与 app/assets/plugin/whalepet-bridge.zip 里的 package.json 一致。 */
    private static final String PLUGIN_ZIP = "whalepet-bridge-0.2.7.zip";

    /** 把打进 APK 的桥插件导出到系统下载目录（MediaStore，不需要存储权限）。 */
    private void exportBridgePlugin() {
        Uri uri = null;
        try (InputStream in = getAssets().open("plugin/whalepet-bridge.zip")) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, PLUGIN_ZIP);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DSH");
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                Toast.makeText(this, getString(R.string.main_toast_no_write_target), Toast.LENGTH_SHORT).show();
                return;
            }
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("openOutputStream 为空");
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.main_toast_export_failed,
                    t.getClass().getSimpleName()), Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.main_dlg_exported_title))
                .setMessage(getString(R.string.main_dlg_exported_msg, PLUGIN_ZIP))
                .setPositiveButton(getString(R.string.main_btn_copy_cmd), (d, w) -> copyInstallCmd())
                .setNegativeButton(getString(R.string.common_ok), null)
                .show();
    }

    private void copyInstallCmd() {
        String cmd = getString(R.string.main_install_cmd, PLUGIN_ZIP);
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("whalepet-bridge", cmd));
            Toast.makeText(this, getString(R.string.main_toast_cmd_copied), Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.main_toast_copy_failed,
                    t.getClass().getSimpleName()), Toast.LENGTH_SHORT).show();
        }
    }

    /** 探一下本机数据桥通不通（决定本机消耗那一段显不显示）。 */
    private void refreshPluginState() {
        io.execute(() -> {
            boolean ok = false;
            try {
                String body = Http.get(Bridge.baseUrl() + "/api/whalepet/ping",
                        new String[]{"Accept", "application/json"}, 1500);
                ok = body != null && body.contains("\"ok\":true");
            } catch (Throwable t) { Log.ignored("MainActivity", t);
                ok = false;
            }
            final boolean alive = ok;
            runOnUiThread(() -> {
                if (pluginState == null) return;
                pluginState.setText(alive
                        ? getString(R.string.main_state_plugin_ok)
                        : getString(R.string.main_state_plugin_off));
            });
        });
    }

    private void section(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Ui.LABEL);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(12), 0, dp(4));
        root.addView(t);
    }

    private void style(TextView t) {
        t.setTextColor(0xFFD6E1F5);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
    }

    private EditText input(String hint, String value, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFF5C6B85);
        e.setTextColor(Color.WHITE);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT
                | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        e.setBackgroundResource(R.drawable.panel_bg);
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        e.setText(value);
        return e;
    }

    private TextView button(String text, int bg, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setBackgroundResource(bg);
        t.setPadding(dp(8), dp(9), dp(8), dp(9));
        t.setClickable(true);
        return t;
    }

    private LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    /** 首页的动作列表：点一个就在桌宠上播一次。 */
    private void fillActionList(LinearLayout box) {
        try {
            Anim anim = Anim.load(this);
            java.util.Set<String> seen = new java.util.HashSet<>();
            java.util.List<Anim.Act> rows = new java.util.ArrayList<>();
            if (anim.idle != null && seen.add(anim.idle.dir)) rows.add(anim.idle);
            for (Anim.Act a : anim.all) {
                if (!a.menu) continue;   // 只做状态联动的素材不进点播列表
                if (seen.add(a.dir)) rows.add(a);
            }
            final java.text.Collator coll = java.text.Collator.getInstance(Locale.CHINA);
            rows.sort((x, y) -> coll.compare(
                    x.display == null ? x.dir : x.display, y.display == null ? y.dir : y.display));
            for (Anim.Act a : rows) {
                TextView row = new TextView(this);
                row.setText(a.display == null || a.display.isEmpty() ? a.dir : a.display);
                row.setTextColor(0xFFD6E1F5);
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                row.setPadding(dp(2), dp(7), dp(2), dp(7));
                row.setClickable(true);
                row.setBackgroundResource(R.drawable.btn_ghost);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(3);
                row.setOnClickListener(v -> {
                    Intent i = new Intent(MainActivity.this, PetService.class);
                    i.setAction(PetService.ACTION_PLAY);
                    i.putExtra(PetService.EXTRA_DIR, a.dir);
                    try {
                        startService(i);
                    } catch (Throwable t) { Log.ignored("MainActivity", t);
                        // 服务没跑就先不管
                    }
                });
                box.addView(row, rlp);
            }
        } catch (Throwable t) { Log.ignored("MainActivity", t);
            // 清单读不到就不显示
        }
    }

    /** 一个输入框 + 一个按钮并排（省一行高度）。 */
    private LinearLayout inline(View field, TextView action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(field, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(6);
        row.addView(action, blp);
        return row;
    }



    /** 版本号从清单读，避免每次发版忘改文案。 */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private void paintA11yToggle(TextView t) {
        boolean on = prefs().getBoolean(PetService.KEY_A11Y_HIDE, true);
        t.setText(getString(R.string.main_a11y_toggle,
                getString(on ? R.string.main_state_on : R.string.main_state_off)));
    }

    private boolean ignoringBattery() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 直接弹系统的「忽略电池优化」确认框；失败就退到设置页。 */
    private void requestBatteryWhitelist() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                Toast.makeText(this, getString(R.string.main_toast_no_battery_settings), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void paintTiers(TextView[] cells, int selected) {
        String[] names = {getString(R.string.main_size_small),
                getString(R.string.main_size_mid), getString(R.string.main_size_large)};
        int sel = 0;
        for (int i = 0; i < PetService.SIZE_TIERS.length; i++) {
            if (PetService.SIZE_TIERS[i] == selected) sel = i;
        }
        for (int i = 0; i < cells.length; i++) {
            TextView c = cells[i];
            String raw = names[Math.min(i, names.length - 1)];
            boolean on = (i == sel);
            c.setText(on ? "✓ " + raw : raw);
            c.setBackgroundResource(on ? R.drawable.btn_primary : R.drawable.btn_ghost);
            c.setTextColor(on ? Color.WHITE : 0xFFD6E1F5);
            c.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void updateStatus() {
        overlayState.setText(Settings.canDrawOverlays(this)
                ? getString(R.string.common_granted) : getString(R.string.main_state_overlay_off));
        startBtn.setText(getString(R.string.main_btn_summon));
        if (accessState != null) {
            if (a11yState != null) {
                a11yState.setText(PetAccessibility.isConnected()
                        ? getString(R.string.main_state_a11y_on) : getString(R.string.main_state_a11y_off));
            }
            if (acctState != null) {
                boolean on = AccountSession.has(this);
                acctState.setText(on
                        ? getString(R.string.main_state_account_on) : getString(R.string.main_state_account_off));
                if (acctBtn != null) acctBtn.setText(on
                        ? getString(R.string.common_logout) : getString(R.string.common_login));
            }
            if (batteryState != null) {
                batteryState.setText(ignoringBattery()
                        ? getString(R.string.main_state_battery_on) : getString(R.string.main_state_battery_off));
            }
            accessState.setText(Foreground.hasPermission(this)
                    ? getString(R.string.main_state_usage_on)
                    : getString(R.string.main_state_usage_off));
        }
        updateTargetLabel();
    }


    /** 拉两样：用量（本机桥）+ 余额（官方接口）。 */
    private void refreshBridgeState() {
        final String key = AccountSession.apiKey(this);
        io.execute(() -> {
            // 两个请求并行（串行时最坏 2.5s + 7s，并行后取最大值）
            final java.util.concurrent.atomic.AtomicReference<BalanceClient.Result> balRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread balanceThread = new Thread(
                    () -> balRef.set(BalanceClient.fetch(key, 7000)), "acct-balance");
            balanceThread.setDaemon(true);
            balanceThread.start();
            final Bridge.Usage usage = Bridge.usage(2500);
            try {
                // Http 的连接、读取各有一个 7s 超时，最坏 14s；等不够会把成功的结果当成超时
                balanceThread.join(14500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            final BalanceClient.Result bal = balRef.get() != null
                    ? balRef.get() : BalanceClient.Result.failed(getString(R.string.main_error_timeout));
            runOnUiThread(() -> {
                // 查到了币种说明 Key 本身有效，只是账户不可用（如余额为 0）
                boolean keyOk = bal.ok || !bal.items.isEmpty();
                keyState.setText(bal.ok ? getString(R.string.main_key_ok)
                        : keyOk ? getString(R.string.main_key_ok_reason, bal.error)
                        : getString(R.string.main_key_state, bal.error));
            });
        });
    }

    private android.content.SharedPreferences prefs() {
        return Prefs.pet(this);
    }

    /** 退出登录：prefs 之外，WebView 的 cookie / localStorage 也要清，否则旧会话还留在磁盘上。 */
    private void clearWebSession() {
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            android.webkit.WebStorage.getInstance().deleteAllData();
        } catch (Throwable t) { Log.ignored("MainActivity.logout", t); }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
