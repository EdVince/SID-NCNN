package com.example.camera2raw;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if(null==savedInstanceState){
            getFragmentManager().beginTransaction()
                    .replace(R.id.container, Camera2RawFragment.newInstance())
                    .commit();
        }
    }
}