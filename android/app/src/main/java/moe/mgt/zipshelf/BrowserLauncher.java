package moe.mgt.zipshelf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** 用 EinkBro 打开 URL；没装就退回系统默认浏览器。 */
public final class BrowserLauncher {

    private BrowserLauncher() {
    }

    public static boolean open(Context context, String url) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        PackageManager pm = context.getPackageManager();

        ResolveInfo pick = null;
        try {
            List<ResolveInfo> candidates = pm.queryIntentActivities(view, 0);
            // EinkBro 主包是 info.plateaukao.einkbro，另有 .g 变体；按包名包含匹配更稳
            for (ResolveInfo ri : candidates) {
                if (ri.activityInfo != null
                        && ri.activityInfo.packageName.toLowerCase(Locale.ROOT).contains("einkbro")) {
                    pick = ri;
                    break;
                }
            }
            if (pick == null && !candidates.isEmpty()) {
                pick = candidates.get(0);
            }
        } catch (Exception ignored) {
            // 继续走隐式 intent
        }

        Intent target = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        if (pick != null && pick.activityInfo != null) {
            target.setClassName(pick.activityInfo.packageName, pick.activityInfo.name);
        }
        if (!(context instanceof Activity)) {
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            context.startActivity(target);
            return true;
        } catch (Exception e) {
            Toast.makeText(context, "没有可用的浏览器：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
