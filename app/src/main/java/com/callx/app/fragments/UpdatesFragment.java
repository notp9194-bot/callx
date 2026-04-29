package com.callx.app.fragments;
import android.os.Bundle;
import android.view.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
public class UpdatesFragment extends Fragment {
    private RecyclerView rv;
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_updates, container, false);
        rv = view.findViewById(R.id.rv_requests);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        return view;
    }
}
