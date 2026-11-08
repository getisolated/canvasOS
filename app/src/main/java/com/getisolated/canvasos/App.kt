package com.getisolated.canvasos

import android.app.Application
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.res = resources
    }
}

/**
 * The UI text, from res/values/strings.xml: French by default, English or German when the TV is set to that
 * language (values-en, values-de). Usable without a Context, from objects such as Weather.
 */
object Strings {
    @Volatile var res: Resources? = null

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
