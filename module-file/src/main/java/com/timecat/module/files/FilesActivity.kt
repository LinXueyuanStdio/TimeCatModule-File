package com.timecat.module.files

import androidx.fragment.app.Fragment
import com.xiaojinzi.component.anno.RouterAnno
import com.timecat.page.base.friend.compact.BaseFragmentActivity
import com.timecat.identity.readonly.RouterHub
import com.timecat.component.router.app.NAV

/**
 * @author 林学渊
 * @email linxy59@mail2.sysu.edu.cn
 * @date 2020/7/10
 * @description null
 * @usage null
 */
@RouterAnno(hostAndPath = RouterHub.FILES_FilesActivity)
class FilesActivity : BaseFragmentActivity() {
    override fun createFragment(): Fragment {
        return NAV.fragment(RouterHub.FILES_FilesFragment)
    }
}