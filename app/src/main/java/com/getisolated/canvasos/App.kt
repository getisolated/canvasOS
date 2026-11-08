package com.getisolated.canvasos

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.apply(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Strings.apply(this)
    }
}

/**
 * The UI text, from res/values/strings.xml: French by default, English or German when the TV is set to that
 * language (values-en, values-de). Usable without a Context, from objects such as Weather.
 */
object Strings {
    @Volatile var res: Resources? = null

    /** Reads the text in the language chosen in Réglages → Système → Langue, or the TV's when it is auto. Called again on a change of the TV's language (App.onConfigurationChanged). */
    fun apply(ctx: Context) {
        val app = ctx.applicationContext
        val lang = Prefs(app).language
        // In auto, the language of the text Android picked (French on a TV in another language), so plurals follow its rules.
        val tag = if (lang == Prefs.AUTO) app.resources.getString(R.string.ui_language) else lang
        res = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }).resources
    }

    /**
     * After a language change: some lists (key names, wallpaper sources) are read once per process, so
     * the app starts again on the home screen rather than showing two languages at once.
     */
    fun restart(ctx: Context) {
        ctx.startActivity(Intent(ctx, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }

    /** Local unit tests have no Resources: TestStrings (test sources only) reads values/strings.xml instead. */
    @Volatile var stub: ((id: Int, count: Int?, args: Array<out Any>) -> String)? = null

    internal fun stub() = stub ?: run {
        Class.forName("com.getisolated.canvasos.TestStrings").getField("INSTANCE").get(null)
        stub!!
    }
}

fun str(@StringRes id: Int, vararg args: Any): String {
    val res = Strings.res ?: return Strings.stub()(id, null, args)
    return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
}

fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String {
    val res = Strings.res ?: return Strings.stub()(id, count, args)
    return if (args.isEmpty()) res.getQuantityString(id, count) else res.getQuantityString(id, count, *args)
}

/** The UI language (fr, en or de), for dates and the online services' answers. */
val uiLocale: Locale get() = Locale.forLanguageTag(str(R.string.ui_language))
