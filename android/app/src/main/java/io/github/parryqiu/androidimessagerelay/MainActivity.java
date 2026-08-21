package io.github.parryqiu.androidimessagerelay;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int SMS_PERMISSION_REQUEST = 100;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status;
    private EditText endpoint;
    private EditText accessClientId;
    private EditText accessClientSecret;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        status = new TextView(this);
        layout.addView(status);
        endpoint = field(R.id.relay_endpoint, R.string.hint_relay_endpoint, false);
        accessClientId = field(R.id.access_client_id, R.string.hint_access_client_id, false);
        accessClientSecret = field(R.id.access_client_secret, R.string.hint_access_client_secret, true);
        layout.addView(endpoint);
        layout.addView(accessClientId);
        layout.addView(accessClientSecret);
        layout.addView(button(R.id.save_access_credentials, R.string.button_save_access,
                view -> saveConfiguration()));
        layout.addView(button(View.NO_ID, R.string.button_permission, view -> requestSmsPermission()));
        layout.addView(button(R.id.create_pairing_request, R.string.button_pair,
                view -> createPairingRequest()));
        layout.addView(button(R.id.check_pairing_status, R.string.button_check_pairing,
                view -> checkPairingStatus()));
        layout.addView(button(R.id.send_test_message, R.string.button_test, view -> enqueueTest()));
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(layout);
        setContentView(scrollView);
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private EditText field(int id, int hint, boolean secret) {
        EditText value = new EditText(this);
        value.setId(id);
        value.setHint(hint);
        value.setSingleLine(true);
        value.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        value.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        value.setSaveEnabled(false);
        return value;
    }

    private Button button(int id, int text, View.OnClickListener listener) {
        Button button = new Button(this);
        if (id != View.NO_ID) {
            button.setId(id);
        }
        button.setText(text);
        button.setOnClickListener(listener);
        return button;
    }

    private void saveConfiguration() {
        String endpointValue = endpoint.getText().toString();
        String clientId = accessClientId.getText().toString();
        String clientSecret = accessClientSecret.getText().toString();
        executor.execute(() -> {
            try {
                RelayConfiguration.saveCredentials(this, endpointValue, clientId, clientSecret);
                clearFieldsAndSetStatus(R.string.status_access_saved);
            } catch (Exception error) {
                setStatus(R.string.status_access_save_failed);
            }
        });
    }

    private void createPairingRequest() {
        setStatus(R.string.status_pairing);
        executor.execute(() -> {
            try {
                PairingRequest request = new RelayApi(this).createPairingRequest();
                RelayConfiguration.savePendingPairing(this, request.name, request.displayCode);
                runOnUiThread(() -> status.setText(getString(
                        R.string.status_pair_pending_with_code, request.displayCode)));
            } catch (Exception error) {
                setStatus(R.string.status_pair_failed);
            }
        });
    }

    private void checkPairingStatus() {
        executor.execute(() -> {
            try {
                RelayConfiguration configuration = RelayConfiguration.load(this);
                if (configuration == null || !configuration.hasPendingPairing()) {
                    throw new IllegalStateException("No pending pairing request");
                }
                PairingRequest request = new RelayApi(this)
                        .getPairingRequest(configuration.pairingRequestName);
                if (request.isApproved()) {
                    RelayConfiguration.completePairing(this, request.messageEncryptionPublicKey,
                            request.messageEncryptionKeyFingerprint);
                    setStatus(R.string.status_paired);
                } else if ("PENDING".equals(request.state)) {
                    runOnUiThread(() -> status.setText(getString(
                            R.string.status_pair_pending_with_code, request.displayCode)));
                } else {
                    setStatus(R.string.status_pair_failed);
                }
            } catch (Exception error) {
                setStatus(R.string.status_pair_failed);
            }
        });
    }

    private void enqueueTest() {
        executor.execute(() -> {
            try {
                if (!RelayConfiguration.isReady(this)) {
                    throw new IllegalStateException("Pairing is not complete");
                }
                long now = System.currentTimeMillis() / 1000L;
                MessagePayload payload = new MessagePayload(
                        MessagePayload.randomId(), "Relay test", "Test message from Android", now);
                try (SecureQueue queue = new SecureQueue(this)) {
                    queue.enqueue(payload);
                }
                RelayService.start(this);
                setStatus(R.string.status_test_queued);
            } catch (Exception error) {
                setStatus(R.string.status_test_failed);
            }
        });
    }

    private void requestSmsPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.POST_NOTIFICATIONS,
            }, SMS_PERMISSION_REQUEST);
        } else {
            requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS}, SMS_PERMISSION_REQUEST);
        }
    }

    private void updateStatus() {
        boolean runtimeGranted = checkSelfPermission(Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED;
        boolean notificationGranted = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        boolean appOpGranted = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_RECEIVE_SMS, android.os.Process.myUid(), getPackageName())
                == AppOpsManager.MODE_ALLOWED;
        if (!runtimeGranted || !appOpGranted || !notificationGranted) {
            status.setText(R.string.status_permission_needed);
        } else if (!RelayConfiguration.isConfigured(this)) {
            status.setText(R.string.status_access_needed);
        } else if (!RelayConfiguration.isReady(this)) {
            status.setText(R.string.status_not_paired);
        } else {
            try (SecureQueue queue = new SecureQueue(this)) {
                long dropped = queue.droppedCount();
                status.setText(dropped == 0
                        ? getString(R.string.status_ready)
                        : getString(R.string.status_ready_with_drops, dropped));
            }
        }
    }

    private void clearFieldsAndSetStatus(int text) {
        runOnUiThread(() -> {
            endpoint.setText("");
            accessClientId.setText("");
            accessClientSecret.setText("");
            status.setText(text);
        });
    }

    private void setStatus(int text) {
        runOnUiThread(() -> status.setText(text));
    }
}
