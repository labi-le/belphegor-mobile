package belphegor.app

import android.content.Context
import android.view.ContextThemeWrapper
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Programmatic-UI construction helpers shared by [MainActivity]'s sections.
 * Kept as Context / LinearLayout extensions so the Activity stays focused on
 * wiring and state instead of view boilerplate.
 */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun fullWidth(top: Int = 0) =
    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = top }

fun Context.fabParams() = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
    gravity = Gravity.BOTTOM or Gravity.END
    val m = dp(20); setMargins(m, m, m, m)
}

fun Context.scroll(child: View): ScrollView = ScrollView(this).apply {
    isFillViewport = true
    addView(child)
}

fun Context.resolveColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

fun Context.card(build: LinearLayout.() -> Unit): MaterialCardView {
    val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; build() }
    return MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = dp(1).toFloat()
        // Container role stays distinct from the (pure-black) window in the
        // AMOLED night theme, so the card edge is visible.
        setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurfaceContainer))
        setContentPadding(dp(18), dp(14), dp(18), dp(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) }
        addView(inner)
    }
}

fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun Context.dimText(): TextView = TextView(this).apply {
    textSize = 13f
    setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
}

fun Context.sectionLabel(t: String) = TextView(this).apply {
    text = t
    textSize = 12f
    setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
    letterSpacing = 0.09f
    setTypeface(typeface, Typeface.BOLD)
    setPadding(dp(4), dp(8), 0, dp(8))
}

fun Context.filledButton(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
    text = label
    minimumHeight = dp(52)
    setOnClickListener { onClick() }
}

fun Context.outlinedButton(label: String, onClick: () -> Unit) =
    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label
        setOnClickListener { onClick() }
    }

fun LinearLayout.field(
    label: String,
    example: String,
    value: String,
    numeric: Boolean = false,
    multiline: Boolean = false,
): EditText {
    val til = TextInputLayout(
        ContextThemeWrapper(context, com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox),
        null,
    ).apply {
        hint = label
        placeholderText = example
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(10) }
    }
    val et = TextInputEditText(til.context).apply {
        setText(value)
        when {
            numeric -> inputType = InputType.TYPE_CLASS_NUMBER
            multiline -> {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                isSingleLine = false
                minLines = 2
            }
        }
    }
    til.addView(et)
    addView(til)
    return et
}

fun LinearLayout.switchRow(title: String, subtitle: String?, checked: Boolean): SwitchMaterial {
    val sw = SwitchMaterial(context).apply {
        isChecked = checked
        // The switch is the single a11y node; label it so TalkBack reads the
        // title/subtitle plus the on/off state.
        contentDescription = if (subtitle != null) "$title, $subtitle" else title
    }
    val texts = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(TextView(context).apply { text = title; textSize = 15f })
        if (subtitle != null) addView(context.dimText().apply { text = subtitle; textSize = 12f })
    }
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(12), 0, context.dp(12))
        addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(sw)
        setOnClickListener { sw.toggle() }
    }
    addView(row)
    return sw
}

fun Context.toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
