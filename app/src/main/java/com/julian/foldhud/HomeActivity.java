package com.julian.foldhud;


import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** The home screen of the phone. */
public class HomeActivity extends Activity {
    HomeView home;
    DrawerView drawer;
    AppIndex apps;
    final Data data = new Data();
    FrameLayout root;
    String[] dock = new String[5];

    private final BroadcastReceiver tickRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (home != null) home.invalidate(); }
    };
    private final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            apps.reload();
            drawer.refresh();
            home.invalidate();
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        apps = new AppIndex(this);
        data.load(Prefs.sp(this));
        data.calcHot();
        initDock();
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        home = new HomeView(this, data);
        drawer = new DrawerView(this, apps);
        drawer.setVisibility(View.GONE);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(tickRx, new IntentFilter(Intent.ACTION_TIME_TICK));
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgRx, f);
        if (drawer.getVisibility() != View.VISIBLE) home.onResumeVisible();
        askPermissionsOnce();
        refreshAll(false);
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(tickRx); } catch (Exception e) { /* not registered */ }
        try { unregisterReceiver(pkgRx); } catch (Exception e) { /* not registered */ }
        home.stopLoop();
    }

    void refreshAll(boolean force) {
        Steps.snapshot(this, new Runnable() {
            @Override public void run() { data.loadSteps(Prefs.sp(HomeActivity.this)); home.invalidate(); }
        });
        Refresher.run(getApplicationContext(), data, force, new Runnable() {
            @Override public void run() { home.invalidate(); }
        });
    }

    void askPermissionsOnce() {
        if (Prefs.flag(this, "asked", false)) return;
        Prefs.setFlag(this, "asked", true);
        ArrayList<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission("android.permission.ACTIVITY_RECOGNITION") != PackageManager.PERMISSION_GRANTED)
            need.add("android.permission.ACTIVITY_RECOGNITION");
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 7);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        super.onRequestPermissionsResult(rc, p, r);
        refreshAll(true);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        home.resetView();
    }

    @Override public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        // on the home screen Back does nothing, as on any launcher
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        home.requestLayout();
        drawer.requestLayout();
        home.invalidate();
    }

    // ---------------- drawer ----------------
    void openDrawer() {
        if (drawer.getVisibility() == View.VISIBLE) return;
        drawer.onOpen();
        drawer.setVisibility(View.VISIBLE);
        drawer.setAlpha(0f);
        drawer.setTranslationY(root.getHeight() / 8f);
        drawer.animate().alpha(1f).translationY(0f).setDuration(160).start();
        home.stopLoop();
    }

    void closeDrawer() {
        drawer.onClose();
        drawer.animate().alpha(0f).setDuration(120).withEndAction(new Runnable() {
            @Override public void run() {
                drawer.setVisibility(View.GONE);
                home.startLoop();
                home.invalidate();
            }
        }).start();
    }

    // ---------------- dock ----------------
    void initDock() {
        String csv = Prefs.sp(this).getString("dock", "");
        String[] p = csv.isEmpty() ? new String[0] : csv.split(",", -1);
        dock = new String[5];
        for (int i = 0; i < 5; i++) dock[i] = i < p.length && !p[i].isEmpty() ? p[i] : null;
        Intent[] defs = {
                new Intent(Intent.ACTION_DIAL),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS)};
        for (int i = 0; i < 5; i++) {
            if (dock[i] != null) continue;
            ResolveInfo r = getPackageManager().resolveActivity(defs[i], PackageManager.MATCH_DEFAULT_ONLY);
            if (r != null && r.activityInfo != null && !"android".equals(r.activityInfo.packageName)) dock[i] = r.activityInfo.packageName;
        }
    }

    void setDock(int slot, String pkg) {
        dock[slot] = pkg;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(i > 0 ? "," : "").append(dock[i] == null ? "" : dock[i]);
        Prefs.sp(this).edit().putString("dock", sb.toString()).apply();
        home.invalidate();
        Toast.makeText(this, "Dock updated", Toast.LENGTH_SHORT).show();
    }

    void launchDock(int slot) {
        if (slot >= 0 && slot < 5 && dock[slot] != null) apps.launchPkg(dock[slot]);
    }

    // ---------------- dialogs ----------------
    void showNews(final NewsItem it) {
        String body = (it.desc == null || it.desc.isEmpty() ? "No summary was provided by this source." : it.desc)
                + "\n\n" + it.source + "  \u00B7  " + HomeView.ago(it.ts) + " ago";
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(it.title).setMessage(body).setNegativeButton("Close", null);
        if (it.link != null && !it.link.isEmpty()) {
            b.setPositiveButton("Open article", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { Open.web(HomeActivity.this, it.link); }
            });
        }
        b.show();
    }

    void showCountry(final String name, final NewsItem fallback) {
        int id = -1;
        WorldData.init();
        for (int i = 0; i < WorldData.NAMES.length; i++) if (WorldData.NAMES[i].equals(name)) id = i;
        final String q = id >= 0 ? WorldData.QUERY[id] : name;
        Toast.makeText(this, "Loading news for " + name + "\u2026", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<NewsItem> l = Net.countryNews(q, name.equals("Myanmar"));
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (l.isEmpty()) {
                            if (fallback != null) showNews(fallback);
                            else Toast.makeText(HomeActivity.this, "No recent stories found for " + name, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        CharSequence[] t = new CharSequence[l.size()];
                        for (int i = 0; i < t.length; i++) t[i] = l.get(i).title + "\n" + l.get(i).source + " \u00B7 " + HomeView.ago(l.get(i).ts);
                        new AlertDialog.Builder(HomeActivity.this).setTitle(name.toUpperCase())
                                .setItems(t, new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) { showNews(l.get(w)); }
                                }).setNegativeButton("Close", null).show();
                    }
                });
            }
        }).start();
    }

    void showMenu() {
        final String[] modes = {"Saver (still picture)", "Balanced (default)", "Full (smooth)"};
        CharSequence[] items = {
                "Power mode: " + modes[Prefs.userMode(this)] + " \u2014 tap to change",
                "Globe grid lines: " + (Prefs.flag(this, "grid", true) ? "on" : "off"),
                "News hotspots on the globe: " + (Prefs.flag(this, "hot", true) ? "on" : "off"),
                "Slowly spin the globe by itself: " + (Prefs.flag(this, "rot", false) ? "on" : "off"),
                "Refresh news, stocks and weather now",
                "Choose this as your home app (Android settings)"};
        new AlertDialog.Builder(this).setTitle("Fold HUD").setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                if (w == 0) {
                    int m = (Prefs.userMode(HomeActivity.this) + 1) % 3;
                    Prefs.sp(HomeActivity.this).edit().putInt("mode", m).apply();
                    Toast.makeText(HomeActivity.this, "Mode: " + modes[m], Toast.LENGTH_SHORT).show();
                } else if (w == 1) Prefs.setFlag(HomeActivity.this, "grid", !Prefs.flag(HomeActivity.this, "grid", true));
                else if (w == 2) Prefs.setFlag(HomeActivity.this, "hot", !Prefs.flag(HomeActivity.this, "hot", true));
                else if (w == 3) Prefs.setFlag(HomeActivity.this, "rot", !Prefs.flag(HomeActivity.this, "rot", false));
                else if (w == 4) refreshAll(true);
                else Open.start(HomeActivity.this, new Intent(Settings.ACTION_HOME_SETTINGS));
                home.stopLoop();
                home.startLoop();
                home.invalidate();
            }
        }).setNegativeButton("Close", null).show();
    }
}
