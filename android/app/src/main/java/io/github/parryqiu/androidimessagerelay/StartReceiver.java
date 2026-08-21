package io.github.parryqiu.androidimessagerelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class StartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if ((Intent.ACTION_BOOT_COMPLETED.equals(action) || RelayService.RETRY_ACTION.equals(action))
                && RelayConfiguration.isReady(context)) {
            RelayService.start(context);
        }
    }
}
