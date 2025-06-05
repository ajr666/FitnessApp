// MainActivity.kt
package com.example.fitnessapp

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import com.example.fitnessapp.FitnessActivity
import com.example.fitnessapp.NutritionActivity
import com.example.fitnessapp.R

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnFitness = findViewById<Button>(R.id.btnFitness)
        btnFitness.setOnClickListener {
            val intent = Intent(this, FitnessActivity::class.java)
            startActivity(intent)
        }

        val btnNutrition = findViewById<Button>(R.id.btnNutrition)
        btnNutrition.setOnClickListener {
            val intent = Intent(this, NutritionActivity::class.java)
            startActivity(intent)
        }
    }
}
