package com.callx.app.activities;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;
import com.callx.app.R;
import com.callx.app.adapters.ViewPagerAdapter;
import com.callx.app.databinding.ActivityMainBinding;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private final String[] tabs = {"Chats", "Updates", "Calls"};
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, AuthActivity.class));
            finish(); return;
        }
        setSupportActionBar(binding.toolbar);
        binding.viewPager.setAdapter(new ViewPagerAdapter(this));
        new TabLayoutMediator(binding.tabLayout, binding.viewPager,
            (tab, pos) -> tab.setText(tabs[pos])).attach();
        // FAB — Search screen kholo
        binding.fabNewChat.setOnClickListener(v ->
            startActivity(new Intent(this, SearchActivity.class)));
        // Apna CallX ID dikhao
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseDatabase.getInstance("https://sathix-97a76-default-rtdb.firebaseio.com").getReference("users").child(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                public void onDataChange(DataSnapshot snap) {
                    String id = snap.child("callxId").getValue(String.class);
                    if (id != null) binding.tvMyId.setText("Mera CallX ID: " + id);
                }
                public void onCancelled(DatabaseError e) {}
            });
    }
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_logout) {
            FirebaseAuth.getInstance().signOut();
            startActivity(new Intent(this, AuthActivity.class));
            finish();
        }
        return super.onOptionsItemSelected(item);
    }
}
