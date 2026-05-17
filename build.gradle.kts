// Top-level build file where you can add configuration options common to all sub-projects/modules.
git checkout -b plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}