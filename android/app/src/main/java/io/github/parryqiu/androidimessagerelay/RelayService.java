package io.github.parryqiu.androidimessagerelay;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RelayService extends Service {
    static final String RETRY_ACTION = "io.github.parryqiu.androidimessagerelay.RETRY";
    private static final String CHANNEL_ID = "sms_relay_status";
    private static final int NOTIFICATION_ID = 1001;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    static void start(Context context) {
        if (RelayConfiguration.isReady(context)) {
            context.startForegroundService(new Intent(context, RelayService.class));
        }
    }

    static void scheduleRetry(Context context, long delaySeconds) {
        if (!RelayConfiguration.isReady(context)) {
            return;
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, StartReceiver.class).setAction(RETRY_ACTION);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delaySeconds * 1000L,
                pendingIntent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "SMS relay status", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Android iMessage Relay")
                .setContentText("Sending queued SMS messages")
                .setOngoing(true)
                .build());
        executor.execute(() -> drainQueue(startId));
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void drainQueue(int startId) {
        try (SecureQueue queue = new SecureQueue(this)) {
            RelayApi api = new RelayApi(this);
            SecureQueue.Record record;
            while ((record = queue.nextReady(System.currentTimeMillis() / 1000L)) != null) {
                try {
                    api.send(record.payload);
                    queue.delete(record.payload.id);
                } catch (Exception error) {
                    int attempts = record.attempts + 1;
                    long delay = Math.min(3600L, 30L * (1L << Math.min(attempts, 7)));
                    queue.defer(record.payload.id, attempts,
                            System.currentTimeMillis() / 1000L + delay);
                    scheduleRetry(this, delay);
                    break;
                }
            }
        } catch (Exception error) {
            scheduleRetry(this, 300);
        } finally {
            if (stopSelfResult(startId)) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            }
        }
    }
}
