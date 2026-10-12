plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "jp.pocket.texteditor"
    compileSdk = 36
    defaultConfig { applicationId = "jp.pocket.texteditor"; minSdk = 26; targetSdk = 36; versionCode = 2; versionName = "1.0.1-dev"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildFeatures { compose = true; buildConfig = true }
    val ownerStore=System.getenv("POCKET_SIGN_STORE")
    if(!ownerStore.isNullOrBlank()) {
        signingConfigs.create("ownerRelease") {
            storeFile=file(ownerStore)
            storePassword=requireNotNull(System.getenv("POCKET_SIGN_STORE_PASSWORD")){"POCKET_SIGN_STORE_PASSWORD is required"}
            keyAlias=requireNotNull(System.getenv("POCKET_SIGN_ALIAS")){"POCKET_SIGN_ALIAS is required"}
            keyPassword=requireNotNull(System.getenv("POCKET_SIGN_KEY_PASSWORD")){"POCKET_SIGN_KEY_PASSWORD is required"}
        }
    }
    buildTypes { release {
        isMinifyEnabled = false
        if(!ownerStore.isNullOrBlank())signingConfig=signingConfigs.getByName("ownerRelease")
    } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

tasks.withType<Test>().configureEach {
    listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort", "javax.net.ssl.trustStore").forEach { key -> System.getProperty(key)?.let { systemProperty(key,it) } }
    val testHome=layout.buildDirectory.dir("test-home").get().asFile
    doFirst { testHome.mkdirs() }
    systemProperty("user.home",testHome.absolutePath)
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}
