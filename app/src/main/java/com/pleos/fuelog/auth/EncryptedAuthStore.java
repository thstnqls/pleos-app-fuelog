package com.pleos.fuelog.auth;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM key stays in Android Keystore; ciphertext is excluded from backup/device transfer. */
final class EncryptedAuthStore implements AuthStore {
    private static final String ALIAS = "fuelog.supabase.auth.v1";
    private final AtomicFile file;

    EncryptedAuthStore(Context context) {
        this(new File(context.getNoBackupFilesDir(), "supabase-auth.bin"));
    }

    EncryptedAuthStore(File location) { file = new AtomicFile(location); }

    @Override public String read() throws IOException {
        byte[] bytes;
        try { bytes = file.readFully(); }
        catch (FileNotFoundException absent) { return "{}"; }
        try {
            if (bytes.length < 29) throw new GeneralSecurityException();
            ByteBuffer data = ByteBuffer.wrap(bytes);
            byte[] iv = new byte[12];
            data.get(iv);
            byte[] encrypted = new byte[data.remaining()];
            data.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException error) {
            // A lost/invalidated key or corrupt file requires a fresh sign-in, never plaintext fallback.
            file.delete();
            throw new AuthException("저장된 인증 정보를 읽을 수 없습니다. 다시 로그인하세요.");
        }
    }

    @Override public void write(String value) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] bytes = ByteBuffer.allocate(12 + encrypted.length)
                    .put(cipher.getIV()).put(encrypted).array();
            FileOutputStream output = null;
            try {
                output = file.startWrite();
                output.write(bytes);
                file.finishWrite(output);
            } catch (IOException error) {
                if (output != null) file.failWrite(output);
                throw error;
            }
        } catch (GeneralSecurityException error) {
            throw new AuthException("인증 정보를 안전하게 저장할 수 없습니다.");
        }
    }

    private SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
