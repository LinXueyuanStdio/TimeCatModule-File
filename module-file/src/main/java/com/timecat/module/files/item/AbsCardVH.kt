package com.timecat.module.files.item

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.updateLayoutParams
import com.timecat.component.identity.Attr
import com.timecat.layout.ui.utils.IconLoader
import com.timecat.layout.ui.utils.ViewUtil
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flipview.FlipView
import eu.davidea.viewholders.FlexibleViewHolder
import me.zhanghai.android.files.R

/**
 * @author 林学渊
 * @email linxy59@mail2.sysu.edu.cn
 * @date 2020/10/3
 * @description null
 * @usage null
 */
abstract class AbsCardVH(v: View, adapter: FlexibleAdapter<*>) : FlexibleViewHolder(v, adapter) {
    lateinit var avatar: FlipView

    override fun toggleActivation() {
        super.toggleActivation()
        val selected = mAdapter.isSelected(adapterPosition)
        avatar.flip(selected)
    }

    fun bindSelected(activity: Context, isSelected: Boolean, icon: String, color: Int) {
        avatar.initialLayoutAnimationDuration = 0
        avatar.rearImageAnimationDuration = 80
        avatar.mainAnimationDuration = 80

        bindFrontIcon(activity, icon)
        val padding = ViewUtil.dp2px(activity, 3f)
        bindFrontIconBackground(activity, color, padding)
        bindRearIcon(activity, color, padding)

        avatar.flipSilently(isSelected)
    }

    fun bindSelected(activity: Context, isSelected: Boolean, icon: Drawable, color: Int) {
        avatar.initialLayoutAnimationDuration = 0
        avatar.rearImageAnimationDuration = 80
        avatar.mainAnimationDuration = 80

        bindFrontIcon(icon)
        val padding = ViewUtil.dp2px(activity, 3f)
        bindFrontIconBackground(activity, color, padding)
        bindRearIcon(activity, color, padding)

        avatar.flipSilently(isSelected)
    }

    fun bindRearIcon(activity: Context, color: Int, padding: Int) {
        avatar.rearImageView.setImageResource(R.drawable.ic_check_24dp)
        avatar.rearImageView.setPadding(padding, padding, padding, padding)
        avatar.rearImageView.imageTintList = ColorStateList.valueOf(color)
        avatar.rearImageView.updateLayoutParams<FrameLayout.LayoutParams> {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = FrameLayout.LayoutParams.MATCH_PARENT
        }
    }

    fun bindFrontIconBackground(activity: Context, color: Int, padding: Int) {
        val drawable = Attr.tintDrawable(activity, R.drawable.shape_rectangle_with_radius, color)
        avatar.frontImageView.background = drawable
        avatar.frontImageView.setPadding(padding, padding, padding, padding)
        avatar.frontImageView.updateLayoutParams<FrameLayout.LayoutParams> {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = FrameLayout.LayoutParams.MATCH_PARENT
        }
    }

    fun bindFrontIcon(activity: Context, icon: String) {
        IconLoader.loadDefaultRoundIcon(activity, avatar.frontImageView, icon)
        if (icon.startsWith("R.drawable.")) {
            avatar.frontImageView.imageTintList = ColorStateList.valueOf(Attr.getIconColor(activity))
        } else {
            avatar.frontImageView.imageTintList = null
        }
    }

    fun bindFrontIcon(icon: Drawable) {
        avatar.frontImageView.setImageDrawable(icon)
        avatar.frontImageView.imageTintList = null
    }

}