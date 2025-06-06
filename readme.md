# Smart Fitness and Nutrition Management App

## Overview

This project aims to enhance users' fitness and dietary management through an intuitive mobile application. It integrates advanced sensors, Bluetooth Low Energy (BLE) connectivity, geolocation tracking, and artificial intelligence (AI) to offer personalized fitness tracking and nutrition recommendations.

## Features

### Fitness Module

* **Activity Tracking**: Utilizes device sensors (accelerometer, pedometer) to monitor step count, activity duration, and intensity.
* **Real-Time Heart Rate Monitoring**: Connects via BLE to external heart rate monitors, providing live heart rate data during workouts.
* **Route Tracking**: Employs geolocation services to record and display routes for jogging and walking on integrated maps.
* **Workout History**: Displays historical records including routes, steps, workout duration, and average heart rate for easy tracking and review.

### Nutrition Module

* **Personalized Metrics**: Calculates Basal Metabolic Rate (BMR) based on user inputs of height, weight, and age.
* **AI-Powered Dietary Advice**: Integrates OpenAI API to deliver customized meal recommendations and balanced nutritional guidance.
* **Dietary Logging**: Allows users to input daily meals, with historical tracking and analysis of calorie intake and nutritional balance.

## User Experience

* **Clear Navigation**: Separate entry points for Fitness and Nutrition modules, simplifying user interaction.
* **Intuitive Interface**: Real-time data visualization and AI-driven recommendations presented clearly for maximum usability.
* **Consistent Design**: Unified color schemes, typography, and iconography to ensure an engaging and cohesive user experience.
* **Responsive Layout**: Fully adaptive to various screen sizes and orientations for consistent functionality across devices.

## Technical Stack

* Android (Kotlin)
* BLE (Bluetooth Low Energy)
* Google Maps API
* OpenAI API for dietary suggestions

## Installation

Clone this repository and open the project using Android Studio:

```bash
git clone https://github.com/ajr666/smart-fitness-nutrition-app.git
```

## Usage

* Connect a compatible heart rate monitor via BLE for accurate real-time tracking.
* Enter your personal details to customize fitness tracking and dietary recommendations.
* Regularly log meals to optimize AI-driven nutrition advice.

## Contributing

Feel free to submit pull requests or raise issues to help enhance the app.
