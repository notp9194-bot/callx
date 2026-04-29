package com.callx.app.fragments;
import android.os.Bundle;
import android.view.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
public class CallsFragment extends Fragment {
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_calls, container, false);
        RecyclerView rv = view.findViewById(R.id.rv_calls);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        return view;
    }
}
