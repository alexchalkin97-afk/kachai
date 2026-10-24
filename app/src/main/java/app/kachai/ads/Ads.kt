package app.kachai.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.kachai.BuildConfig
import com.yandex.mobile.ads.banner.BannerAdEventListener
import com.yandex.mobile.ads.banner.BannerAdSize
import com.yandex.mobile.ads.banner.BannerAdView
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.YandexAds
import com.yandex.mobile.ads.interstitial.InterstitialAd
import com.yandex.mobile.ads.interstitial.InterstitialAdEventListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoader
import com.yandex.mobile.ads.rewarded.Reward
import com.yandex.mobile.ads.rewarded.RewardedAd
import com.yandex.mobile.ads.rewarded.RewardedAdEventListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoadListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoader

/**
 * Реклама Яндекса. Все вызовы SDK — с главного потока.
 * Где что показывается, решает UI: баннер внизу экрана, полноэкранная — после каждой
 * второй загрузки, за вознаграждение — по кнопке «+2 загрузки».
 */
object Ads {
    private const val TAG = "Ads"

    private var interstitial: InterstitialAd? = null
    private var interstitialLoader: InterstitialAdLoader? = null
    private var rewarded: RewardedAd? = null
    private var rewardedLoader: RewardedAdLoader? = null

    var rewardedReady by mutableStateOf(false)
        private set

    fun init(context: Context) {
        YandexAds.initialize(context) { Log.i(TAG, "initialized") }
    }

    fun preloadInterstitial(context: Context) {
        if (interstitial != null) return
        val loader = interstitialLoader ?: InterstitialAdLoader(context.applicationContext).also { interstitialLoader = it }
        loader.loadAd(AdRequest.Builder(BuildConfig.AD_INTERSTITIAL_ID).build(), object : InterstitialAdLoadListener {
            override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad }
            override fun onAdFailedToLoad(error: AdRequestError) { Log.w(TAG, "interstitial: ${error.description}") }
        })
    }

    /** Показать полноэкранную рекламу, если она загружена. Возвращает true, если показали. */
    fun showInterstitial(activity: Activity): Boolean {
        val ad = interstitial ?: run { preloadInterstitial(activity); return false }
        interstitial = null
        ad.setAdEventListener(object : InterstitialAdEventListener {
            override fun onAdShown() = Unit
            override fun onAdFailedToShow(adError: AdError) { ad.setAdEventListener(null); preloadInterstitial(activity) }
            override fun onAdDismissed() { ad.setAdEventListener(null); preloadInterstitial(activity) }
            override fun onAdClicked() = Unit
            override fun onAdImpression(impressionData: ImpressionData?) = Unit
        })
        ad.show(activity)
        return true
    }

    fun preloadRewarded(context: Context) {
        if (rewarded != null) return
        val loader = rewardedLoader ?: RewardedAdLoader(context.applicationContext).also { rewardedLoader = it }
        loader.loadAd(AdRequest.Builder(BuildConfig.AD_REWARDED_ID).build(), object : RewardedAdLoadListener {
            override fun onAdLoaded(ad: RewardedAd) { rewarded = ad; rewardedReady = true }
            override fun onAdFailedToLoad(error: AdRequestError) { Log.w(TAG, "rewarded: ${error.description}") }
        })
    }

    fun showRewarded(activity: Activity, onReward: () -> Unit) {
        val ad = rewarded ?: run { preloadRewarded(activity); return }
        rewarded = null
        rewardedReady = false
        ad.setAdEventListener(object : RewardedAdEventListener {
            override fun onAdShown() = Unit
            override fun onAdFailedToShow(adError: AdError) { ad.setAdEventListener(null); preloadRewarded(activity) }
            override fun onAdDismissed() { ad.setAdEventListener(null); preloadRewarded(activity) }
            override fun onAdClicked() = Unit
            override fun onAdImpression(impressionData: ImpressionData?) = Unit
            override fun onRewarded(reward: Reward) = onReward()
        })
        ad.show(activity)
    }
}

/** Адаптивный баннер на всю ширину экрана. */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val view = remember {
        BannerAdView(context).apply {
            setAdSize(BannerAdSize.sticky(context, widthDp))
            setBannerAdEventListener(object : BannerAdEventListener {
                override fun onAdLoaded() = Unit
                override fun onAdFailedToLoad(error: AdRequestError) { Log.w("Ads", "banner: ${error.description}") }
                override fun onAdClicked() = Unit
                override fun onImpression(impressionData: ImpressionData?) = Unit
            })
            loadAd(AdRequest.Builder(BuildConfig.AD_BANNER_ID).build())
        }
    }
    DisposableEffect(view) { onDispose { view.destroy() } }
    AndroidView(factory = { view }, modifier = modifier.fillMaxWidth())
}
