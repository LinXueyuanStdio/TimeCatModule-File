package com.timecat.module.files.item

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import com.timecat.component.commonsdk.utils.override.LogUtil
import com.timecat.component.identity.Attr
import com.timecat.layout.ui.entity.BaseAdapter
import com.timecat.layout.ui.entity.BaseItem
import com.timecat.layout.ui.layout.setShakelessClickListener
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.getDrawableCompat
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.filelist.userFriendlyString
import java.util.*

/**
 * @author 林学渊
 * @email linxy59@mail2.sysu.edu.cn
 * @date 2021/1/21
 * @description null
 * @usage null
 */
class DirCard(
    val fileItem: FileItem,
    val context: Context,
    val listener: Listener
) : BaseItem<DirCard.DirCardVH>(fileItem.path.userFriendlyString) {
    class DirCardVH(v: View, adapter: FlexibleAdapter<*>) : AbsCardVH(v, adapter) {
        var title: AppCompatTextView = v.findViewById(R.id.title)
        var mTimerState: TextView = v.findViewById(R.id.state)
        var more: ImageView = v.findViewById(R.id.more)
        var redDot: TextView = v.findViewById(R.id.red_dot_tv)
        var mState: TextView = v.findViewById(R.id.type)
        var mHint: View = v.findViewById(R.id.container_hint)

        init {
            redDot.visibility = View.GONE
            avatar = v.findViewById(R.id.avatar)
        }
    }

    interface Listener {
        fun loadFor(fileItem: FileItem)
    }

    override fun getLayoutRes(): Int = R.layout.card_file_dir

    override fun createViewHolder(view: View, adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>): DirCardVH {
        return DirCardVH(view, adapter)
    }

    override fun bindViewHolder(adapter: FlexibleAdapter<IFlexible<*>>, holder: DirCardVH, position: Int, payloads: MutableList<Any>?) {
        if (adapter is BaseAdapter) {
            adapter.bindViewHolderAnimation(holder)
        }
        val selected = adapter.isSelected(position)
        val icon = context.getDrawableCompat(fileItem.mimeType.iconRes)
        val color = Attr.getBackgroundDarkColor(context)
        holder.bindSelected(context, selected, icon, color)
        val drawable = Attr.tintDrawable(context, R.drawable.shape_rect_accent, color)
        holder.mHint.background = drawable

        holder.title.text = fileItem.name
        holder.mTimerState.text = "文件夹"
        holder.mState.text = "容器符文"

        holder.frontView.setShakelessClickListener {
            listener.loadFor(fileItem)
        }
    }
}