package io.github.parryqiu.androidimessagerelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

public final class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())
                || !RelayConfiguration.isReady(context)) {
            return;
        }
        try {
            SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (parts.length == 0) {
                return;
            }
            String sender = parts[0].getOriginatingAddress();
            StringBuilder body = new StringBuilder();
            long sentAtMillis = Long.MAX_VALUE;
            for (SmsMessage part : parts) {
                String partBody = part.getMessageBody();
                if (partBody == null) {
                    return;
                }
                body.append(partBody);
                sentAtMillis = Math.min(sentAtMillis, part.getTimestampMillis());
            }
            MessagePayload payload = new MessagePayload(
                    MessagePayload.randomId(), sender, body.toString(), sentAtMillis / 1000L);
            try (SecureQueue queue = new SecureQueue(context)) {
                queue.enqueue(payload);
            }
            RelayService.start(context);
        } catch (IllegalArgumentException error) {
            return;
        } catch (Exception error) {
            RelayService.scheduleRetry(context, 60);
        }
    }
}
