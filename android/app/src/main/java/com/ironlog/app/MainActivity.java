package com.ironlog.app;

import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(WorkoutNotificationPlugin.class);
        WebUpdater.onLaunch(this); // switch to (or roll back) a page update from GitHub before the page loads
        super.onCreate(savedInstanceState);
        takeRoutine(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        takeRoutine(intent);
    }

    /** A tapped daily-routine reminder carries the routine id; the page picks it up via takeLaunchRoutine(). */
    private void takeRoutine(Intent i) {
        if (i == null) return;
        String id = i.getStringExtra(WorkoutCore.EXTRA_ROUTINE);
        if (id != null) {
            WorkoutCore.pendingRoutine = id;
            i.removeExtra(WorkoutCore.EXTRA_ROUTINE);
        }
    }
}
