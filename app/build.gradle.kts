import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Параметры рекламы, оплаты и статистики лежат в secrets.properties (не в git).
// Шаблон: secrets.properties.example
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}
fun secret(key: String, default: String = "") = secrets.getProperty(key, default)
fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
fun sha256(v: String) = MessageDigest.getInstance("SHA-256")
    .digest(v.toByteArray()).joinToString("") { "%02x".format(it) }

android {
    namespace = "app.kachai"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.kachai"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.3.0"

        buildConfigField("String", "PREMIUM_URL", quoted(secret("premiumUrl")))
        buildConfigField("String", "API_BASE", quoted(secret("apiBase")))
        buildConfigField("String", "SOURCE_URL", quoted(secret("sourceUrl")))
        // Адрес политики конфиденциальности (docs/privacy.html, выложенная в интернет) — ссылка в «О приложении»
        buildConfigField("String", "PRIVACY_URL", quoted(secret("privacyUrl")))
        // Куда «Отправить отчёт» шлёт письмо с журналом ошибок
        buildConfigField("String", "SUPPORT_EMAIL", quoted(secret("supportEmail", "alexchalkin97@bk.ru")))
        buildConfigField("int", "FREE_DAILY_LIMIT", secret("freeDailyLimit", "3"))
        // Бесплатная версия качает видео не выше этого разрешения (по короткой стороне кадра)
        buildConfigField("int", "FREE_MAX_HEIGHT", secret("freeMaxHeight", "720"))
        buildConfigField("String", "PREMIUM_PRICE", quoted(secret("premiumPrice", "99 ₽ в месяц")))
        // Код тестировщика включает Плюс без оплаты. В APK попадает только хэш, не сам код
        val testerCode = secret("testerCode").trim().uppercase()
        buildConfigField("String", "TESTER_CODE_HASH", quoted(if (testerCode.isEmpty()) "" else sha256(testerCode)))
        // По умолчанию — демо-блоки Яндекса, они всегда показывают тестовую рекламу
        buildConfigField("String", "AD_BANNER_ID", quoted(secret("ads.banner", "demo-banner-yandex")))
        buildConfigField("String", "AD_INTERSTITIAL_ID", quoted(secret("ads.interstitial", "demo-interstitial-yandex")))
        buildConfigField("String", "AD_REWARDED_ID", quoted(secret("ads.rewarded", "demo-rewarded-yandex")))
        // RuStore: ID приложения из адреса Консоли (console.rustore.ru/apps/<ID>) и технический
        // идентификатор подписки. Пустой ID — оплата через RuStore выключена
        val rustoreAppId = secret("rustore.appId")
        manifestPlaceholders["rustoreAppId"] = rustoreAppId
        buildConfigField("String", "RUSTORE_APP_ID", quoted(rustoreAppId))
        buildConfigField("String", "APPMETRICA_KEY", quoted(secret("appmetrica.apiKey")))
        buildConfigField("String", "RUSTORE_SUBSCRIPTION_ID", quoted(secret("rustore.subscriptionId", "subsribe")))
        // Другие тарифы Плюса: годовая подписка и разовый Плюс на месяц / год (потребляемые товары).
        // Пустой ID — тарифа нет, экран покажет только то, что заведено в Консоли
        buildConfigField("String", "RUSTORE_YEARLY_ID", quoted(secret("rustore.yearlyId")))
        buildConfigField("String", "RUSTORE_PASS_MONTH_ID", quoted(secret("rustore.passMonthId")))
        buildConfigField("String", "RUSTORE_PASS_YEAR_ID", quoted(secret("rustore.passYearId")))
        // Открытый ключ для проверки промокодов (tools/promo). Пустой — промокоды выключены
        buildConfigField("String", "PROMO_PUBLIC_KEY", quoted(secret("promo.publicKey")))
        // Канал или группа приложения в соцсетях — ссылка в настройках; пустая — пункта нет
        buildConfigField("String", "COMMUNITY_URL", quoted(secret("communityUrl")))
    }

    splits {
        abi {
            isEnable = true
            reset()
            // x86_64 — только для эмулятора: в релиз (и в универсальный APK для RuStore) его не кладём,
            // иначе файл почти вдвое тяжелее ради процессоров, которых у телефонов почти не бывает
            // -Pemulator: та же релизная сборка, но под процессор эмулятора — посмотреть приложение глазами
            // пользователя. Такой APK только для эмулятора, в магазин его не отдавать
            val forPhones = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) } &&
                !project.hasProperty("emulator")
            if (forPhones) include("arm64-v8a", "armeabi-v7a") else include("arm64-v8a", "armeabi-v7a", "x86_64")
            // Универсальный APK — для RuStore без передачи ключа подписи (AAB он требует вместе с ключом)
            isUniversalApk = true
        }
    }

    signingConfigs {
        val f = rootProject.file("signing.properties")
        if (f.exists()) {
            val p = Properties().apply { f.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            // Отладочные запуски не должны крутить боевые блоки: Рекламная сеть сочтёт такие показы
            // недействительными. Демо-блоки Яндекса всегда отдают тестовую рекламу
            buildConfigField("String", "AD_BANNER_ID", quoted("demo-banner-yandex"))
            buildConfigField("String", "AD_INTERSTITIAL_ID", quoted("demo-interstitial-yandex"))
            buildConfigField("String", "AD_REWARDED_ID", quoted("demo-rewarded-yandex"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // yt-dlp запускает python и ffmpeg прямо из nativeLibraryDir —
        // библиотеки должны лежать распакованными
        jniLibs.useLegacyPackaging = true
    }
}

// Релиз — только для телефонов: библиотеки x86/x86_64 (эмуляторы) не кладём, особенно в универсальный APK,
// который собирает все архитектуры из зависимостей, не глядя на список в splits
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        if (!project.hasProperty("emulator")) {
            variant.packaging.jniLibs.excludes.addAll(listOf("lib/x86/**", "lib/x86_64/**"))
        }
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    // aria2c: качает в несколько потоков и сам рвёт «замёрзшие» соединения
    implementation("io.github.junkfood02.youtubedl-android:aria2c:0.18.1")
    // Та же версия, что внутри youtubedl-android: разбираем JSON yt-dlp сами (ради таймаута)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.11.1")

    implementation("com.yandex.android:mobileads:8.5.0")
    implementation("io.appmetrica.analytics:analytics:8.6.0")
    implementation("ru.rustore.sdk:pay:11.1.0")
    // Окно «Оцените приложение» прямо в приложении: оценка уходит в RuStore
    implementation("ru.rustore.sdk:review:10.5.2")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
}
