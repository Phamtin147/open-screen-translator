package com.openscreentranslator.app.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.openscreentranslator.app.R
import com.openscreentranslator.app.data.AppLanguages

/**
 * Full-width Star Atlas modal bottom sheet selector so long Vietnamese language labels
 * and short codes render clearly with active selection highlights in Day/Night and 1.3x font scale.
 */
object LanguagePickerSheet {

    fun show(
        context: Context,
        title: String,
        subtitle: String,
        options: List<AppLanguages.LanguageOption>,
        selectedCode: String,
        onSelected: (AppLanguages.LanguageOption) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.sheet_language_picker, null, false)

        val tvTitle = root.findViewById<TextView>(R.id.tvLanguageSheetTitle)
        val tvSubtitle = root.findViewById<TextView>(R.id.tvLanguageSheetSubtitle)
        val scroll = root.findViewById<NestedScrollView>(R.id.scrollLanguageOptions)
        val container = root.findViewById<LinearLayout>(R.id.containerLanguageOptions)

        tvTitle.text = title
        tvSubtitle.text = subtitle

        val baseBottomPadding = container.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, baseBottomPadding + navBottom)
            insets
        }

        var selectedRowView: View? = null
        val normalizedSelected = selectedCode.lowercase()

        for (option in options) {
            val row = inflater.inflate(R.layout.item_language_option, container, false)
            val tvCode = row.findViewById<TextView>(R.id.tvOptionCode)
            val tvOptionTitle = row.findViewById<TextView>(R.id.tvOptionTitle)
            val tvOptionSubtitle = row.findViewById<TextView>(R.id.tvOptionSubtitle)
            val ivCheck = row.findViewById<ImageView>(R.id.ivOptionCheck)

            val isSelected = option.code.equals(normalizedSelected, ignoreCase = true)
            tvCode.text = option.shortCode
            tvOptionTitle.text = option.labelVi
            tvOptionSubtitle.text = if (option.code == AppLanguages.AUTO) {
                "Quét nhiều thứ tiếng cùng lúc trên 1 trang"
            } else {
                option.englishName
            }

            row.setBackgroundResource(
                if (isSelected) R.drawable.bg_option_selected else R.drawable.bg_option_unselected
            )
            tvCode.setBackgroundResource(
                if (isSelected) R.drawable.bg_lang_pill_selected else R.drawable.bg_lang_pill_neutral
            )
            tvCode.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isSelected) R.color.on_accent else R.color.ink_primary
                )
            )
            ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            row.isSelected = isSelected

            if (isSelected) {
                selectedRowView = row
            }

            row.setOnClickListener {
                onSelected(option)
                dialog.dismiss()
            }
            container.addView(row)
        }

        dialog.setContentView(root)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                val maxHeight = (context.resources.displayMetrics.heightPixels * 0.78f).toInt()
                it.layoutParams = it.layoutParams.apply {
                    height = maxHeight
                }
                BottomSheetBehavior.from(it).apply {
                    skipCollapsed = true
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
            selectedRowView?.let { target ->
                scroll.post {
                    val targetTop = (target.top - (scroll.height / 3)).coerceAtLeast(0)
                    scroll.scrollTo(0, targetTop)
                }
            }
        }
        dialog.show()
    }
}
