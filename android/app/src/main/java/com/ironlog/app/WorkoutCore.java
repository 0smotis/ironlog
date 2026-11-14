package com.ironlog.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * Everything the phone does on its own for IronLog: the ongoing workout notification (visible on the
 * lock screen), the rest-over alarm that rings with the screen off, the "workout left running" nudge
 * and daily routine reminders. The web app sends its state through WorkoutNotificationPlugin; this
 * class keeps a copy in SharedPreferences so alarms and notification buttons work even if the app
 * process has been killed.
 */
final class WorkoutCore {
    static final String CH_WORKOUT = "ironlog_workout";
    static final String CH_REST = "ironlog_rest_over";
    static final String CH_REMIND = "ironlog_reminders";

    static final int ID_WORKOUT = 1001;
    static final int ID_IDLE = 1002;
    static final int ID_TEST = 1003;

    static final String A_MINUS = "com.ironlog.app.REST_MINUS";
    static final String A_PLUS = "com.ironlog.app.REST_PLUS";
    static final String A_SKIP = "com.ironlog.app.REST_SKIP";
    static final String A_COMPLETE = "com.ironlog.app.COMPLETE_SET";
    static final String A_REST_ALARM = "com.ironlog.app.REST_ALARM";
    static final String A_IDLE = "com.ironlog.app.IDLE";
    static final String A_DAILY = "com.ironlog.app.DAILY";
    static final String A_TEST = "com.ironlog.app.TEST";

    static final String EXTRA_ROUTINE = "ironlog_routine";
    private static final String PREFS = "ironlog_native";

    /** True while IronLog is on screen; then the web app plays its own sounds and the phone stays quiet. */
    static volatile boolean appVisible = false;
    /** Routine id from a tapped daily reminder, handed to the web app once via takeLaunchRoutine(). */
    static volatile String pendingRoutine = null;

    private WorkoutCore() {}

    // ---------------------------------------------------------------- state

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static JSONObject state(Context c) {
        String s = prefs(c).getString("state", null);
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    static void saveState(Context c, JSONObject st) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (st == null) e.remove("state"); else e.putString("state", st.toString());
        e.apply();
    }

    // ---------------------------------------------------------------- channels & permission

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel w = new NotificationChannel(CH_WORKOUT, "Workout in progress", NotificationManager.IMPORTANCE_LOW);
        w.setDescription("Live workout timer, current set and rest countdown. Shown on the lock screen.");
        w.setShowBadge(false);
        w.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        w.setSound(null, null);
        w.enableVibration(false);

        NotificationChannel r = new NotificationChannel(CH_REST, "Rest over", NotificationManager.IMPORTANCE_HIGH);
        r.setDescription("Pops up when a rest timer ends. IronLog plays its own sound and vibration (set them in IronLog's settings).");
        r.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        r.setSound(null, null);
        r.enableVibration(false);

        NotificationChannel m = new NotificationChannel(CH_REMIND, "Reminders", NotificationManager.IMPORTANCE_DEFAULT);
        m.setDescription("Daily routine reminders, and a nudge when a workout is left running.");
        m.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        nm.createNotificationChannel(w);
        nm.createNotificationChannel(r);
        nm.createNotificationChannel(m);
    }

    static boolean canNotify(Context c) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && (Build.VERSION.SDK_INT < 24 || nm.areNotificationsEnabled());
    }

    static void notify(Context c, int id, Notification n) {
        if (!canNotify(c)) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.notify(id, n);
        } catch (SecurityException ignored) {
        }
    }

    static void cancel(Context c, int id) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(id);
    }

    static Notification.Builder builder(Context c, String channel) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat_ironlog);
        b.setColor(0xFFD9480F);
        return b;
    }

    // ---------------------------------------------------------------- intents

    static PendingIntent openApp(Context c, String routineId, int req) {
        Intent i = new Intent(c, MainActivity.class);
        i.setAction(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (routineId != null) i.putExtra(EXTRA_ROUTINE, routineId);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent broadcast(Context c, String action, int req, String id) {
        Intent i = new Intent(c, WorkoutReceiver.class);
        i.setAction(action);
        if (id != null) i.putExtra("id", id);
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Notification.Action action(Context c, String title, String act, int req) {
        return new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat_ironlog), title, broadcast(c, act, req, null)).build();
    }

    // ---------------------------------------------------------------- the workout notification

    /** Posts (or removes) the ongoing workout notification. restOver = the heads-up "rest over" version. */
    static void postWorkout(Context c, JSONObject st, boolean restOver) {
        ensureChannels(c);
        if (st == null) {
            cancel(c, ID_WORKOUT);
            return;
        }
        long now = System.currentTimeMillis();
        long restEnd = st.optLong("restEndMs", 0);
        boolean resting = !restOver && restEnd > now;
        boolean details = st.optBoolean("lockDetails", true);

        String title;
        String text;
        if (restOver) {
            title = st.optString("restOverTitle", "Rest over");
            text = st.optString("restOverText", "");
        } else if (resting) {
            title = st.optString("restTitle", "Resting");
            text = st.optString("text", "");
        } else {
            title = st.optString("title", "Workout");
            text = st.optString("text", "");
        }
        String workoutName = st.optString("workoutName", "Workout");

        Notification.Builder b = builder(c, restOver ? CH_REST : CH_WORKOUT)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSubText(workoutName)
                .setOngoing(true)
                .setOnlyAlertOnce(!restOver)
                .setContentIntent(openApp(c, null, 1))
                .setCategory(restOver ? Notification.CATEGORY_ALARM : "stopwatch")
                .setPriority(restOver ? Notification.PRIORITY_HIGH : Notification.PRIORITY_LOW)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setVisibility(details ? Notification.VISIBILITY_PUBLIC : Notification.VISIBILITY_PRIVATE);

        if (resting) {
            b.setWhen(restEnd);
            b.setChronometerCountDown(true);
            b.addAction(action(c, "−15s", A_MINUS, 11));
            b.addAction(action(c, "+15s", A_PLUS, 12));
            b.addAction(action(c, "Skip rest", A_SKIP, 13));
        } else {
            b.setWhen(st.optLong("startMs", now));
            b.setChronometerCountDown(false);
            if (st.optBoolean("canComplete", false)) b.addAction(action(c, "Complete set", A_COMPLETE, 14));
        }

        if (!details) {
            Notification pub = builder(c, restOver ? CH_REST : CH_WORKOUT)
                    .setContentTitle(restOver ? "Rest over" : "Workout in progress")
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setWhen(resting ? restEnd : st.optLong("startMs", now))
                    .setChronometerCountDown(resting)
                    .build();
            b.setPublicVersion(pub);
        }
        notify(c, ID_WORKOUT, b.build());
    }

    // ---------------------------------------------------------------- alarms

    static AlarmManager alarms(Context c) {
        return (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    }

    static void setExact(Context c, long at, PendingIntent pi) {
        AlarmManager am = alarms(c);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** Rings 3 s early when countdown ticks are on, so the tick-tick-tick lands right before the bell. */
    static void scheduleRest(Context c, JSONObject st) {
        AlarmManager am = alarms(c);
        if (am == null) return;
        PendingIntent pi = broadcast(c, A_REST_ALARM, 21, null);
        am.cancel(pi);
        if (st == null) return;
        long now = System.currentTimeMillis();
        long end = st.optLong("restEndMs", 0);
        if (end <= now) return;
        boolean ticks = st.optBoolean("countdown", true) && st.optBoolean("sound", true) && end - 3000 > now + 500;
        setExact(c, ticks ? end - 3000 : end, pi);
    }

    static void scheduleIdle(Context c, JSONObject st) {
        AlarmManager am = alarms(c);
        if (am == null) return;
        PendingIntent pi = broadcast(c, A_IDLE, 22, null);
        am.cancel(pi);
        if (st == null) return;
        long after = st.optLong("remindAfterMs", 0);
        if (after <= 0) return;
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + after, pi);
    }

    // ---------------------------------------------------------------- sound & vibration

    static void playSound(Context c, JSONObject st, boolean withTicks) {
        if (!st.optBoolean("sound", true)) return;
        String id = st.optString("soundId", "chime").replaceAll("[^a-z]", "");
        int res = raw(c, (withTicks ? "cd_" : "rest_") + id);
        if (res == 0) res = raw(c, withTicks ? "cd_chime" : "rest_chime");
        if (res == 0) return;
        boolean alarm = st.optBoolean("alarmStream", false);
        final AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(alarm ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        final AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(aa);
            AssetFileDescriptor afd = c.getResources().openRawResourceFd(res);
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            float v = (float) Math.max(0, Math.min(1, st.optDouble("volume", 0.8)));
            mp.setVolume(v, v);
            final Object focus = requestFocus(am, aa);
            mp.setOnCompletionListener(p -> {
                p.release();
                abandonFocus(am, focus);
            });
            mp.setOnErrorListener((p, what, extra) -> {
                p.release();
                abandonFocus(am, focus);
                return true;
            });
            mp.prepare();
            mp.start();
        } catch (Exception ignored) {
        }
    }

    static int raw(Context c, String name) {
        return c.getResources().getIdentifier(name, "raw", c.getPackageName());
    }

    /** Ducks music in your earbuds while the bell plays, then hands it back. */
    private static Object requestFocus(AudioManager am, AudioAttributes aa) {
        if (am == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                AudioFocusRequest r = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(aa)
                        .build();
                am.requestAudioFocus(r);
                return r;
            }
            am.requestAudioFocus(null, AudioManager.STREAM_NOTIFICATION, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            return Boolean.TRUE;
        } catch (Exception e) {
            return null;
        }
    }

    private static void abandonFocus(AudioManager am, Object focus) {
        if (am == null || focus == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && focus instanceof AudioFocusRequest) am.abandonAudioFocusRequest((AudioFocusRequest) focus);
            else am.abandonAudioFocus(null);
        } catch (Exception ignored) {
        }
    }

    static long[] pattern(String p) {
        switch (p) {
            case "short": return new long[]{0, 200};
            case "long": return new long[]{0, 800};
            case "heartbeat": return new long[]{0, 120, 100, 120, 500, 120, 100, 120};
            case "ramp": return new long[]{0, 80, 80, 160, 80, 320, 80, 600};
            default: return new long[]{0, 200, 100, 200, 100, 400};
        }
    }

    static void vibrate(Context c, JSONObject st) {
        if (!st.optBoolean("vibrate", true)) return;
        String p = st.optString("vibePattern", "triple");
        if ("off".equals(p)) return;
        Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        long[] pat = pattern(p);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                AudioAttributes aa = new AudioAttributes.Builder()
                        .setUsage(st.optBoolean("alarmStream", false) ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .build();
                v.vibrate(VibrationEffect.createWaveform(pat, -1), aa);
            } else {
                v.vibrate(pat, -1);
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- reminders

    static void postIdle(Context c, JSONObject st) {
        ensureChannels(c);
        Notification n = builder(c, CH_REMIND)
                .setContentTitle("Workout still running")
                .setContentText(st.optString("workoutName", "Your workout") + " has had no new sets for a while. Finish it or keep going.")
                .setStyle(new Notification.BigTextStyle().bigText(st.optString("workoutName", "Your workout") + " has had no new sets for a while. Finish it or keep going."))
                .setContentIntent(openApp(c, null, 2))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
        notify(c, ID_IDLE, n);
    }

    static int reqFor(String id) {
        return 5000 + (id.hashCode() & 0x3fff);
    }

    static String dayKey(Calendar cal) {
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    static long nextTrigger(String hhmm, String lastDone) {
        int h = Integer.parseInt(hhmm.substring(0, 2));
        int m = Integer.parseInt(hhmm.substring(3, 5));
        Calendar cal = Calendar.getInstance();
        String today = dayKey(cal);
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, m);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() || today.equals(lastDone)) cal.add(Calendar.DAY_OF_MONTH, 1);
        return cal.getTimeInMillis();
    }

    static JSONArray dailies(Context c) {
        try {
            return new JSONArray(prefs(c).getString("dailies", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static void scheduleDailies(Context c) {
        AlarmManager am = alarms(c);
        if (am == null) return;
        SharedPreferences p = prefs(c);
        for (String id : p.getString("dailyIds", "").split("\n")) {
            if (!id.isEmpty()) am.cancel(broadcast(c, A_DAILY, reqFor(id), id));
        }
        JSONArray arr = dailies(c);
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null) continue;
            String id = r.optString("id", "");
            String time = r.optString("time", "");
            if (id.isEmpty() || !time.matches("\\d{2}:\\d{2}")) continue;
            setExact(c, nextTrigger(time, r.optString("lastDone", "")), broadcast(c, A_DAILY, reqFor(id), id));
            ids.append(id).append('\n');
        }
        p.edit().putString("dailyIds", ids.toString()).apply();
    }

    static void postDaily(Context c, String id) {
        JSONArray arr = dailies(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null || !id.equals(r.optString("id"))) continue;
            if (dayKey(Calendar.getInstance()).equals(r.optString("lastDone", ""))) return; // already done today
            ensureChannels(c);
            Notification n = builder(c, CH_REMIND)
                    .setContentTitle(r.optString("title", "Daily routine"))
                    .setContentText(r.optString("text", "Tap to start"))
                    .setContentIntent(openApp(c, id, reqFor(id)))
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .build();
            notify(c, reqFor(id), n);
            return;
        }
    }
}
