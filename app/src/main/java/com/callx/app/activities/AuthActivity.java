package com.callx.app.activities;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.databinding.ActivityAuthBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;
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
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser != null) {
            goToMain();
            return;
        }
        binding.btnLogin.setOnClickListener(v -> {
            String email = binding.etEmail.getText().toString().trim();
            String password = binding.etPassword.getText().toString().trim();
            if (email.isEmpty() || password.isEmpty()) {
                showError("Please fill all fields");
                return;
            }
            if (isLoginMode) {
                auth.signInWithEmailAndPassword(email, password)
                    .addOnSuccessListener(r -> goToMain())
                    .addOnFailureListener(e -> showError(e.getMessage()));
            } else {
                auth.createUserWithEmailAndPassword(email, password)
                    .addOnSuccessListener(r -> {
                        saveUserProfile(r.getUser().getUid(), email);
                        goToMain();
                    })
                    .addOnFailureListener(e -> showError(e.getMessage()));
            }
        });
        binding.btnSignup.setOnClickListener(v -> {
            isLoginMode = !isLoginMode;
            binding.btnLogin.setText(isLoginMode ? "Login" : "Sign Up");
            binding.btnSignup.setText(isLoginMode ? "Sign Up" : "Back to Login");
        });
    }
    private void saveUserProfile(String uid, String email) {
        java.util.Map<String, Object> user = new java.util.HashMap<>();
        user.put("uid", uid);
        user.put("email", email);
        user.put("name", email.split("@")[0]);
        user.put("emoji", "😊");
        user.put("callxId", uid.substring(0, 8).toUpperCase());
        FirebaseDatabase.getInstance().getReference("users").child(uid).setValue(user);
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
