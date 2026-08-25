package com.blackhope01

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PasswordLock(
    private val correctPassword: String,
    private val prefsName: String
) {
    private var unlocked = false
    private var unlockDeferred: CompletableDeferred<Boolean>? = null
    private val unlockMutex = Mutex()

    companion object {
        private const val KEY_REMEMBER = "remember_unlocked"
    }

    suspend fun ensureUnlocked(): Boolean {
        if (unlocked) return true

        if (isRemembered()) {
            unlocked = true
            return true
        }

        val deferred = unlockMutex.withLock {
            unlockDeferred ?: CompletableDeferred<Boolean>().also {
                unlockDeferred = it
                showDialog(it)
            }
        }
        val result = deferred.await()
        if (result) unlocked = true
        return result
    }

    private fun isRemembered(): Boolean {
        val activity = CommonActivity.activity ?: return false
        val prefs = activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_REMEMBER, false)
    }

    private fun saveRemember(remember: Boolean) {
        val activity = CommonActivity.activity ?: return
        val prefs = activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_REMEMBER, remember).apply()
    }

    private fun showDialog(deferred: CompletableDeferred<Boolean>) {
        val activity = CommonActivity.activity ?: run {
            deferred.complete(false)
            return
        }

        activity.runOnUiThread {
            val density = activity.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(28), dp(28), dp(24))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(Color.parseColor("#111827"))
                }
            }

            val title = TextView(activity).apply {
                text = "Erişim Kilidi"
                setTextColor(Color.WHITE)
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            }

            val subtitle = TextView(activity).apply {
                text = "Devam etmek için şifrenizi girin"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(20))
            }

            val input = EditText(activity).apply {
                hint = "Şifre"
                setHintTextColor(Color.parseColor("#6B7280"))
                setTextColor(Color.WHITE)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor("#1F2937"))
                    setStroke(dp(1), Color.parseColor("#374151"))
                }
            }

            val errorText = TextView(activity).apply {
                text = "Şifre hatalı, tekrar deneyin"
                setTextColor(Color.parseColor("#EF4444"))
                textSize = 12f
                setPadding(dp(4), dp(6), 0, 0)
                visibility = View.GONE
            }

            val rememberCheckbox = CheckBox(activity).apply {
                text = "Beni hatırla"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 13f
                setPadding(0, dp(8), 0, 0)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#8B5CF6"))
                visibility = View.GONE // Beni hatırla görünmez yap
            }

            val buttonRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(20), 0, 0)
            }

            val cancelBtn = TextView(activity).apply {
                text = "İptal"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(13), 0, dp(13))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.parseColor("#374151"))
                    setColor(Color.TRANSPARENT)
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
                isClickable = true
            }

            val confirmBtn = TextView(activity).apply {
                text = "Onayla"
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(13), 0, dp(13))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor("#8B5CF6"))
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                }
                isClickable = true
            }

            buttonRow.addView(cancelBtn)
            buttonRow.addView(confirmBtn)

            container.addView(title)
            container.addView(subtitle)
            container.addView(input)
            container.addView(errorText)
            container.addView(rememberCheckbox)
            container.addView(buttonRow)

            val dialog = AlertDialog.Builder(activity)
                .setView(container)
                .setCancelable(false)
                .create()

            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            confirmBtn.setOnClickListener {
                val entered = input.text.toString().trim()
                if (entered == correctPassword) {

                    saveRemember(rememberCheckbox.isChecked)
                    deferred.complete(true)
                    dialog.dismiss()
                } else {
                    errorText.visibility = View.VISIBLE
                    input.text?.clear()
                    val shake = TranslateAnimation(0f, dp(10).toFloat(), 0f, 0f).apply {
                        duration = 80
                        repeatCount = 3
                        repeatMode = Animation.REVERSE
                    }
                    input.startAnimation(shake)
                }
            }

            cancelBtn.setOnClickListener {
                deferred.complete(false)
                dialog.dismiss()
            }

            dialog.show()
        }
    }
}