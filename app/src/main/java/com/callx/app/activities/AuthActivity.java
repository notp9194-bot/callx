package com.callx.app.activities;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.databinding.ActivityAuthBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.database.FirebaseDatabase;
import java.util.HashMap;
import java.util.Map;
public class AuthActivity extends AppCompatActivity {
    private ActivityAuthBinding binding;
    private FirebaseAuth auth;
    private boolean isLoginMode = true;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAuthBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) { goToMain(); return; }
        binding.btnLogin.setOnClickListener(v -> {
            String email = binding.etEmail.getText().toString().trim();
            String password = binding.etPassword.getText().toString().trim();
            if (email.isEmpty() || password.isEmpty()) {
                showError("Email aur password fill karo");
                return;
            }
            if (isLoginMode) {
                auth.signInWithEmailAndPassword(email, password)
                    .addOnSuccessListener(r -> goToMain())
                    .addOnFailureListener(e -> showError(e.getMessage()));
            } else {
                String name = binding.etName.getText().toString().trim();
                if (name.isEmpty()) { showError("Naam bhi daalo"); return; }
                auth.createUserWithEmailAndPassword(email, password)
                    .addOnSuccessListener(r -> {
                        FirebaseUser user = r.getUser();
                        if (user == null) return;
                        // Display name set karo
                        user.updateProfile(new UserProfileChangeRequest.Builder()
                            .setDisplayName(name).build());
                        // Unique callxId
                        String callxId = "callx_" + user.getUid().substring(0, 8).toLowerCase();
                        Map<String, Object> data = new HashMap<>();
                        data.put("uid", user.getUid());
                        data.put("email", email);
                        data.put("name", name);
                        data.put("emoji", "😊");
                        data.put("callxId", callxId);
                        FirebaseDatabase.getInstance().getReference("users")
                            .child(user.getUid()).setValue(data)
                            .addOnSuccessListener(x -> {
                                Toast.makeText(this,
                                    "Account bana!\nTumhara CallX ID: " + callxId,
                                    Toast.LENGTH_LONG).show();
                                goToMain();
                            });
                    })
                    .addOnFailureListener(e -> showError(e.getMessage()));
            }
        });
        binding.btnSignup.setOnClickListener(v -> {
            isLoginMode = !isLoginMode;
            binding.btnLogin.setText(isLoginMode ? "Login" : "Sign Up");
            binding.btnSignup.setText(isLoginMode ? "Sign Up" : "Back to Login");
            binding.tilName.setVisibility(isLoginMode ? View.GONE : View.VISIBLE);
        });
        // Name field pehle hidden
        binding.tilName.setVisibility(View.GONE);
    }
    private void showError(String msg) {
        binding.tvError.setVisibility(View.VISIBLE);
        binding.tvError.setText(msg);
    }
    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
