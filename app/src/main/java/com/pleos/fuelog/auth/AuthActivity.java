package com.pleos.fuelog.auth;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.pleos.fuelog.diagnostics.AaosVehicleDiagnosticService;

import java.io.IOException;
import java.util.function.Consumer;

/** Login screen and deep-link entry point; tokens/URLs never enter view state or logs. */
public final class AuthActivity extends AppCompatActivity {
    private SupabaseAuth auth;
    private AaosVehicleDiagnosticService vehicle;
    private TextView restriction;
    private TextView status;
    private LinearLayout form;
    private EditText email;
    private Button send;
    private Button signOut;
    private boolean allowed;
    private boolean busy;
    private boolean signedIn;
    private int requestVersion;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Fuelog 로그인");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        restriction = text(content, "정차 상태 확인 중");
        status = text(content, "로그인 상태 확인 중");
        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        content.addView(form);
        text(form, "이메일로 받은 링크를 이 기기에서 열면 로그인됩니다. 처음 이용하는 이메일은 계정이 생성됩니다.");
        email = new EditText(this);
        email.setHint("이메일 주소");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setSingleLine(true);
        email.setSaveEnabled(false);
        form.addView(email, new LinearLayout.LayoutParams(-1, -2));
        send = button(form, "로그인 링크 받기", this::sendLink);
        signOut = button(form, "로그아웃", this::signOut);
        setContentView(scroll);
        try { auth = AuthModule.get(this); }
        catch (IllegalStateException error) { status.setText(error.getMessage()); }
        updateControls();
        consumeIntent(getIntent());
    }

    @Override protected void onStart() {
        super.onStart();
        vehicle = new AaosVehicleDiagnosticService(this);
        vehicle.start((canUse, detail) -> {
            allowed = canUse;
            restriction.setText(canUse ? "정차 중 · 로그인 가능" : "정차 후 로그인을 이용하세요.");
            if (!canUse) hideKeyboard();
            updateControls();
        });
        if (auth != null && !busy) {
            busy = true;
            updateControls();
            auth.getSession(callback(session -> showSession(session, "이메일로 로그인하세요.")));
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeIntent(intent);
    }

    private void consumeIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String callbackUrl = intent.getData().toString();
        // Clear before asynchronous work, so rotation doesn't exchange a one-time code twice.
        intent.setData(null);
        if (auth == null) return;
        busy = true;
        status.setText("로그인 확인 중");
        updateControls();
        auth.handleRedirect(callbackUrl, callback(session -> showSession(session, "로그인이 완료되었습니다.")));
    }

    private void sendLink() {
        if (!allowed || busy || auth == null) return;
        String value = email.getText().toString().trim();
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()) {
            email.setError("이메일 주소를 확인하세요.");
            return;
        }
        busy = true;
        hideKeyboard();
        updateControls();
        auth.sendMagicLink(value, callback(ignored -> status.setText(
                "메일을 확인하세요. 가장 최근에 받은 로그인 링크를 이 기기에서 여세요.")));
    }

    private void signOut() {
        if (!allowed || busy || auth == null) return;
        busy = true;
        updateControls();
        auth.signOut(callback(ignored -> showSession(null, "로그아웃했습니다.")));
    }

    private <T> SupabaseAuth.Callback<T> callback(Consumer<T> success) {
        int version = ++requestVersion;
        return new SupabaseAuth.Callback<T>() {
            @Override public void onSuccess(T value) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || version != requestVersion) return;
                    busy = false;
                    success.accept(value);
                    updateControls();
                });
            }
            @Override public void onError(IOException error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || version != requestVersion) return;
                    busy = false;
                    status.setText(error.getMessage());
                    // Refresh failures and failed remote logout may have cleared local state.
                    signedIn = false;
                    updateControls();
                });
            }
        };
    }

    private void showSession(SupabaseAuth.Session session, String emptyMessage) {
        signedIn = session != null;
        if (signedIn) {
            status.setText(session.getEmail().isEmpty() ? "로그인되었습니다." : session.getEmail() + " · 로그인됨");
            email.setText("");
        } else status.setText(emptyMessage);
    }

    private void updateControls() {
        status.setVisibility(allowed ? View.VISIBLE : View.GONE);
        form.setVisibility(allowed ? View.VISIBLE : View.GONE);
        email.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        send.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        signOut.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        email.setEnabled(auth != null && !busy);
        send.setEnabled(auth != null && !busy);
        signOut.setEnabled(auth != null && !busy);
    }

    private void hideKeyboard() {
        email.clearFocus();
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(email.getWindowToken(), 0);
    }

    @Override protected void onStop() {
        allowed = false;
        if (vehicle != null) { vehicle.close(); vehicle = null; }
        updateControls();
        super.onStop();
    }

    private TextView text(LinearLayout parent, String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(18);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }
}
