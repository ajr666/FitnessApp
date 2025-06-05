package com.example.fitnessapp

import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import com.example.fitnessapp.BuildConfig

class NutritionActivity : AppCompatActivity() {

    private lateinit var ageInput: EditText
    private lateinit var heightInput: EditText
    private lateinit var weightInput: EditText
    private lateinit var genderGroup: RadioGroup
    private lateinit var goalSpinner: Spinner
    private lateinit var bmrResult: TextView
    private lateinit var aiAdvice: TextView
    private lateinit var calculateButton: Button
    private lateinit var aiButton: Button

    private val client = OkHttpClient()
    val openAiApiKey = BuildConfig.OPENAI_API_KEY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nutrition)

        ageInput = findViewById(R.id.etAge)
        heightInput = findViewById(R.id.etHeight)
        weightInput = findViewById(R.id.etWeight)
        genderGroup = findViewById(R.id.rgGender)
        goalSpinner = findViewById(R.id.spGoal)
        bmrResult = findViewById(R.id.tvResult)
        aiAdvice = findViewById(R.id.tvAIAdvice)
        calculateButton = findViewById(R.id.btnCalculate)
        aiButton = findViewById(R.id.btnAI)

        ArrayAdapter.createFromResource(
            this,
            R.array.goal_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            goalSpinner.adapter = adapter
        }

        calculateButton.setOnClickListener {
            calculateBMR()
        }

        aiButton.setOnClickListener {
            fetchAIAdvice()
        }
    }

    private fun calculateBMR() {
        val age = ageInput.text.toString().toIntOrNull() ?: return
        val height = heightInput.text.toString().toFloatOrNull() ?: return
        val weight = weightInput.text.toString().toFloatOrNull() ?: return
        val gender = when (genderGroup.checkedRadioButtonId) {
            R.id.rbMale -> "male"
            R.id.rbFemale -> "female"
            else -> return
        }
        val goal = goalSpinner.selectedItem.toString()

        val bmr = if (gender == "male") {
            10 * weight + 6.25 * height - 5 * age + 5
        } else {
            10 * weight + 6.25 * height - 5 * age - 161
        }

        bmrResult.text = "Estimated BMR: ${bmr.toInt()} kcal/day"
    }

    private fun fetchAIAdvice() {
        val age = ageInput.text.toString().toIntOrNull() ?: return
        val height = heightInput.text.toString().toFloatOrNull() ?: return
        val weight = weightInput.text.toString().toFloatOrNull() ?: return
        val gender = when (genderGroup.checkedRadioButtonId) {
            R.id.rbMale -> "male"
            R.id.rbFemale -> "female"
            else -> return
        }
        val goal = goalSpinner.selectedItem.toString()

        val bmr = if (gender == "male") {
            10 * weight + 6.25 * height - 5 * age + 5
        } else {
            10 * weight + 6.25 * height - 5 * age - 161
        }

        val prompt = "I am a $age-year-old $gender, $height cm tall and weigh $weight kg. My goal is to $goal. Please recommend a suitable workout plan and daily meal plan."

        val json = JSONObject()
        json.put("model", "gpt-3.5-turbo")
        json.put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))

        val body = RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            json.toString()
        )

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $openAiApiKey")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    aiAdvice.text = "Network error: ${e.localizedMessage}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                // read the body once
                val raw = response.body?.string()
                runOnUiThread {
                    if (!response.isSuccessful) {
                        aiAdvice.text = "API error ${response.code}: ${response.message}"
                        return@runOnUiThread
                    }
                    if (raw.isNullOrBlank()) {
                        aiAdvice.text = "Empty response from server."
                        return@runOnUiThread
                    }

                    try {
                        val root = JSONObject(raw)
                        val msg = root
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                        aiAdvice.text = msg.trim()
                    } catch (e: Exception) {
                        // log the full exception to Logcat
                        Log.e("NutritionActivity", "Failed to parse AI response", e)
                        aiAdvice.text = "Parsing error: ${e.localizedMessage}"
                    }
                }
            }
        })
    }
}
