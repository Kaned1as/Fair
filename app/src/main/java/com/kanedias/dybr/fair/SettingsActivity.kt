package com.kanedias.dybr.fair

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.kanedias.dybr.fair.databinding.ActivityPreferencesBinding

/**
 * Activity for holding and showing preference fragments
 *
 * @author Kanedias
 *
 * Created on 26.04.18
 */
class SettingsActivity: AppCompatActivity() {

    private lateinit var binding: ActivityPreferencesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPreferencesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.prefToolbar)
        supportFragmentManager.beginTransaction().replace(R.id.pref_content_frame, SettingsFragment()).commit()
    }

}