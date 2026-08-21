package io.github.parryqiu.androidimessagerelay;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureQueue extends SQLiteOpenHelper {
    static final int MAX_MESSAGES = 1000;
    static final long MAX_BYTES = 16L * 1024L * 1024L;
    static final long MAX_AGE_SECONDS = 7L * 24L * 60L * 60L;
    static final int MAX_ATTEMPTS = 168;
    private static final String DATABASE = "relay_queue.db";
    private static final int VERSION = 2;
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ENCRYPTION_ALIAS = "android_imessage_relay_queue_key";
    private static final String METRICS = "queue_metrics";
    private final Context context;

    static final class Record {
        final MessagePayload payload;
        final int attempts;

        Record(MessagePayload payload, int attempts) {
            this.payload = payload;
            this.attempts = attempts;
        }
    }

    SecureQueue(Context context) {
        super(context, DATABASE, null, VERSION);
        this.context = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE queue ("
                + "message_id TEXT PRIMARY KEY,"
                + "ciphertext BLOB NOT NULL,"
                + "iv BLOB NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "attempts INTEGER NOT NULL DEFAULT 0,"
                + "next_attempt_at INTEGER NOT NULL DEFAULT 0)");
        database.execSQL("CREATE INDEX queue_ready_idx ON queue(next_attempt_at, created_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        database.execSQL("DROP TABLE IF EXISTS queue");
        onCreate(database);
    }

    void enqueue(MessagePayload payload) throws Exception {
        byte[] plaintext = payload.serialize();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
        long now = System.currentTimeMillis() / 1000L;
        ContentValues values = new ContentValues();
        values.put("message_id", payload.id);
        values.put("ciphertext", cipher.doFinal(plaintext));
        values.put("iv", cipher.getIV());
        values.put("created_at", now);

        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.insertWithOnConflict("queue", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            int dropped = purge(database, now);
            database.setTransactionSuccessful();
            recordDropped(dropped);
        } finally {
            database.endTransaction();
        }
    }

    Record nextReady(long nowSeconds) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        recordDropped(purge(database, nowSeconds));
        try (Cursor cursor = database.query(
                "queue",
                new String[]{"ciphertext", "iv", "attempts"},
                "next_attempt_at <= ?",
                new String[]{Long.toString(nowSeconds)},
                null,
                null,
                "next_attempt_at ASC, created_at ASC",
                "1")) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(),
                    new GCMParameterSpec(128, cursor.getBlob(1)));
            return new Record(MessagePayload.deserialize(cipher.doFinal(cursor.getBlob(0))), cursor.getInt(2));
        }
    }

    void delete(String messageId) {
        getWritableDatabase().delete("queue", "message_id = ?", new String[]{messageId});
    }

    void defer(String messageId, int attempts, long nextAttemptAt) {
        if (attempts >= MAX_ATTEMPTS) {
            delete(messageId);
            recordDropped(1);
            return;
        }
        ContentValues values = new ContentValues();
        values.put("attempts", attempts);
        values.put("next_attempt_at", nextAttemptAt);
        getWritableDatabase().update("queue", values, "message_id = ?", new String[]{messageId});
    }

    int count() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM queue", null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    long droppedCount() {
        return context.getSharedPreferences(METRICS, Context.MODE_PRIVATE).getLong("dropped", 0);
    }

    private int purge(SQLiteDatabase database, long nowSeconds) {
        int dropped = database.delete("queue", "created_at < ?",
                new String[]{Long.toString(nowSeconds - MAX_AGE_SECONDS)});
        while (exceedsQuota(database)) {
            dropped += database.delete("queue", "message_id = (SELECT message_id FROM queue "
                    + "ORDER BY created_at ASC, rowid ASC LIMIT 1)", null);
        }
        return dropped;
    }

    private static boolean exceedsQuota(SQLiteDatabase database) {
        try (Cursor cursor = database.rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(LENGTH(ciphertext) + LENGTH(iv)), 0) FROM queue", null)) {
            cursor.moveToFirst();
            return cursor.getLong(0) > MAX_MESSAGES || cursor.getLong(1) > MAX_BYTES;
        }
    }

    private void recordDropped(int count) {
        if (count <= 0) {
            return;
        }
        long current = context.getSharedPreferences(METRICS, Context.MODE_PRIVATE)
                .getLong("dropped", 0);
        context.getSharedPreferences(METRICS, Context.MODE_PRIVATE)
                .edit().putLong("dropped", current + count).apply();
    }

    private static SecretKey encryptionKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (!keyStore.containsAlias(ENCRYPTION_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(
                    ENCRYPTION_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) keyStore.getKey(ENCRYPTION_ALIAS, null);
    }
}
