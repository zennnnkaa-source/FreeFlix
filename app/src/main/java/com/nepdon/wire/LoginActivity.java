package com.nepdon.wire;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.nepdon.wire.dev.FileWatchLog;
import com.nepdon.wire.dev.StorageAccess;

/** Branded login gate shown before the home screen. */
public class LoginActivity extends Activity {
    private EditText etUsername;
    private TextView tvStatus;
    private View dot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_login);
        StorageAccess.ensure(this);
        try { if (getActionBar() != null) getActionBar().hide(); } catch (Exception ignored) { }

        etUsername = (EditText) findViewById(R.id.et_license_key);
        etUsername.setSaveEnabled(false);
        tvStatus = (TextView) findViewById(R.id.tv_login_status);
        dot = findViewById(R.id.dot_login);

        Button btnLogin = (Button) findViewById(R.id.btn_activate);
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { login(); }
        });

        // Telegram redirect removed — leave the view alone if present.
        TextView telegram = (TextView) findViewById(R.id.tv_telegram);
        if (telegram != null) {
            telegram.setOnClickListener(null);
            telegram.setClickable(false);
            telegram.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (NepdonGate.isLoggedIn(this)) goMain();
    }

    private void login() {
        String username = etUsername.getText().toString().trim();
        if (!NepdonGate.isUsernameValid(username)) {
            setStatus(getString(R.string.login_invalid_username), R.drawable.dot_warn);
            return;
        }
        NepdonGate.markLoggedIn(this);
        setStatus(getString(R.string.login_ok), R.drawable.dot_on);
        goMain();
    }

    private void goMain() {
        startActivity(new Intent(this, VpnPanelActivity.class));
        finish();
    }

    private void setStatus(String value, int dotRes) {
        if (tvStatus != null) tvStatus.setText(value);
        if (dot != null) dot.setBackgroundResource(dotRes);
    }
}
