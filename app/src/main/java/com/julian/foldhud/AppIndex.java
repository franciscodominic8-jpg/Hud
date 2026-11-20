package com.julian.foldhud;


import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The list of installed apps, their small cached icons, and how often you open them. */
final class AppIndex {
    static final class App { String label, pkg; ComponentName cn; }

    final Context ctx;
    final PackageManager pm;
    final SharedPreferences sp;
    ArrayList<App> apps = new ArrayList<>();
    private final LruCache<String, Bitmap> icons = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    AppIndex(Context c) {
        ctx = c;
        pm = c.getPackageManager();
        sp = Prefs.sp(c);
        reload();
    }

    void reload() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> l = pm.queryIntentActivities(i, 0);
        ArrayList<App> out = new ArrayList<>();
        for (ResolveInfo r : l) {
            ActivityInfo ai = r.activityInfo;
            if (ai.packageName.equals(ctx.getPackageName())) continue;
            App a = new App();
            a.label = String.valueOf(r.loadLabel(pm));
            a.pkg = ai.packageName;
            a.cn = new ComponentName(ai.packageName, ai.name);
            out.add(a);
        }
        Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
        apps = out;
    }

    Bitmap icon(String pkg, ComponentName cn) {
        Bitmap b = icons.get(pkg);
        if (b != null) return b;
        Drawable d;
        try {
            d = cn != null ? pm.getActivityIcon(cn) : pm.getApplicationIcon(pkg);
        } catch (Exception e) { return null; }
        int sz = 96;
        b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, sz, sz);
        d.draw(new Canvas(b));
        icons.put(pkg, b);
        return b;
    }

    Bitmap icon(App a) { return icon(a.pkg, a.cn); }
    Bitmap icon(String pkg) { return icon(pkg, null); }

    int count(String pkg) { return sp.getInt("n_" + pkg, 0); }
    long last(String pkg) { return sp.getLong("l_" + pkg, 0); }

    void launchPkg(String pkg) {
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        sp.edit().putInt("n_" + pkg, count(pkg) + 1).putLong("l_" + pkg, System.currentTimeMillis()).apply();
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); } catch (Exception e) { /* app cannot be opened */ }
    }

    void launch(App a) { launchPkg(a.pkg); }
}
