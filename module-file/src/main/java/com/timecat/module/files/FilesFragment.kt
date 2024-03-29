package com.timecat.module.files

import android.Manifest
import android.animation.Animator
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.GravityCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.commit
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.observe
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.AppBarLayout
import com.leinardi.android.speeddial.SpeedDialView
import com.timecat.extend.arms.BaseApplication
import com.timecat.page.base.base.lazyload.BaseLazyLoadSupportFragment
import com.timecat.component.commonsdk.utils.override.LogUtil
import com.timecat.component.commonsdk.utils.statusbar.StatusBarUtil
import com.timecat.identity.readonly.RouterHub
import com.timecat.layout.ui.listener.HidingScrollListener
import com.xiaojinzi.component.anno.FragmentAnno
import java8.nio.file.AccessDeniedException
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.effortlesspermissions.AfterPermissionDenied
import me.zhanghai.android.effortlesspermissions.EffortlessPermissions
import me.zhanghai.android.effortlesspermissions.OpenAppDetailsDialogFragment
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.clipboardManager
import me.zhanghai.android.files.databinding.*
import me.zhanghai.android.files.file.*
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.*
import me.zhanghai.android.files.fileproperties.FilePropertiesDialogFragment
import me.zhanghai.android.files.navigation.BookmarkDirectories
import me.zhanghai.android.files.navigation.BookmarkDirectory
import me.zhanghai.android.files.navigation.NavigationFragment
import me.zhanghai.android.files.navigation.NavigationRootMapLiveData
import me.zhanghai.android.files.provider.archive.createArchiveRootPath
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.document.isDocumentPath
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.terminal.Terminal
import me.zhanghai.android.files.ui.*
import me.zhanghai.android.files.util.*
import me.zhanghai.android.files.viewer.image.ImageViewerActivity
import pub.devrel.easypermissions.AfterPermissionGranted
import java.util.*

/**
 * @author 林学渊
 * @email linxy59@mail2.sysu.edu.cn
 * @date 2020/7/9
 * @description null
 * @usage null
 */
@FragmentAnno(RouterHub.FILES_FilesFragment)
class FilesFragment : BaseLazyLoadSupportFragment(),
    BreadcrumbLayout.Listener,
    FileListAdapter.Listener,
    OpenApkDialogFragment.Listener,
    ConfirmDeleteFilesDialogFragment.Listener,
    RenameFileDialogFragment.Listener,
    CreateArchiveDialogFragment.Listener,
    CreateFileDialogFragment.Listener,
    CreateDirectoryDialogFragment.Listener,
    NavigationFragment.Listener {

    private val args by args(Args(Intent()))
    private val argsPath by lazy { args.intent.extraPath }
    private val viewModel by viewModels { { FileListViewModel() } }

    private lateinit var binding: Binding

    private lateinit var navigationFragment: NavigationFragment

    private lateinit var menuBinding: MenuBinding

    private lateinit var overlayActionMode: ToolbarActionMode

    private lateinit var bottomActionMode: ToolbarActionMode

    private lateinit var adapter: FileListAdapter

    override fun onBackPressedSupport(): Boolean {
        return onBackPressed()
    }

    private val debouncedSearchRunnable = DebouncedRunnable(Handler(Looper.getMainLooper()), 1000) {
        if (!isResumed || !viewModel.isSearchViewExpanded) {
            return@DebouncedRunnable
        }
        val query = viewModel.searchViewQuery
        if (query.isEmpty()) {
            return@DebouncedRunnable
        }
        viewModel.search(query)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)
    }

    override fun lazyInit() {
        LogUtil.se("${binding.appBarLayout.height}")
        binding.recyclerView.post {
            LogUtil.se("${binding.appBarLayout.height}")
            binding.recyclerView.apply {
                LogUtil.se("$paddingLeft, $paddingRight, ($paddingTop, $paddingBottom)")
                setPadding(
                    paddingLeft,
                    binding.appBarLayout.height,
                    paddingRight,
                    paddingBottom
                )
                LogUtil.se("$paddingLeft, $paddingRight, ($paddingTop, $paddingBottom)")
                scrollToPosition(0)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val origin = requireContext()
        val contextThemeWrapper: Context = ContextThemeWrapper(origin, origin.theme)
        val themeAwareInflater = inflater.cloneInContext(contextThemeWrapper)
        return Binding.inflate(themeAwareInflater, container, false)
            .also { binding = it }
            .root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        if (savedInstanceState == null) {
            navigationFragment = NavigationFragment()
            childFragmentManager.commit { add(R.id.navigationFragment, navigationFragment) }
        } else {
            navigationFragment = childFragmentManager.findFragmentById(R.id.navigationFragment)
                    as NavigationFragment
        }
        navigationFragment.listener = this
        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout?.openDrawer(GravityCompat.START)
            if (binding.persistentDrawerLayout != null) {
                Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.putValue(
                    !Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.valueCompat
                )
            }
        }
        overlayActionMode = OverlayToolbarActionMode(binding.overlayToolbar)
        bottomActionMode = PersistentBarLayoutToolbarActionMode(
            binding.persistentBarLayout, binding.bottomBarLayout, binding.bottomToolbar
        )
        val contentLayoutInitialPaddingBottom = binding.contentLayout.paddingBottom
        if (binding.appBarLayout.parent is CoordinatorLayout) {
            binding.appBarLayout.updateLiftOnScrollOnPreDraw()
        }
        binding.appBarLayout.addOnOffsetChangedListener(
            AppBarLayout.OnOffsetChangedListener { _, verticalOffset ->
                binding.contentLayout.updatePaddingRelative(
                    bottom = contentLayoutInitialPaddingBottom +
                            binding.appBarLayout.totalScrollRange + verticalOffset
                )
            }
        )
        binding.breadcrumbLayout.setListener(this)
        binding.swipeRefreshLayout.setOnRefreshListener { this.refresh() }
        binding.recyclerView.layoutManager = GridLayoutManager(activity, /* TODO */ 1)
        adapter = FileListAdapter(this)
        binding.recyclerView.adapter = adapter
        val fastScroller = ThemedFastScroller.create(binding.recyclerView)
        binding.recyclerView.setOnApplyWindowInsetsListener(
            ScrollingViewOnApplyWindowInsetsListener(binding.recyclerView, fastScroller)
        )
        binding.recyclerView.addOnScrollListener(object : HidingScrollListener() {
            override fun goingDown() {
                //出现动画
                binding.appBarLayout.apply {
                    animate().cancel()
                    if (visibility != View.VISIBLE) {
                        translationY = -height.toFloat()
                    }
                    visibility = View.VISIBLE
                    animate().translationY(0f).alpha(1f).setDuration(200)
                        .setInterpolator(DecelerateInterpolator())
                        .setListener(object : Animator.AnimatorListener {
                            override fun onAnimationRepeat(animation: Animator) {
                            }

                            override fun onAnimationEnd(animation: Animator) {
                                binding.recyclerView.clipToPadding = true
                            }

                            override fun onAnimationCancel(animation: Animator) {
                            }

                            override fun onAnimationStart(animation: Animator) {
                            }
                        })
                        .start()
                }

            }

            override fun goingUp() {
                //消失动画
                binding.appBarLayout.apply {
                    animate().cancel()
                    visibility = View.VISIBLE
                    animate().translationY(-height.toFloat()).alpha(0f).setDuration(200)
                        .setInterpolator(DecelerateInterpolator())
                        .setListener(object : Animator.AnimatorListener {
                            override fun onAnimationRepeat(animation: Animator) {
                            }

                            override fun onAnimationEnd(animation: Animator) {
                            }

                            override fun onAnimationCancel(animation: Animator) {
                            }

                            override fun onAnimationStart(animation: Animator) {
                                binding.recyclerView.clipToPadding = false
                            }
                        })
                        .start()
                }
            }

        })
        binding.speedDialView.inflate(R.menu.file_list_speed_dial)
        binding.speedDialView.setOnActionSelectedListener {
            when (it.id) {
                R.id.action_create_file -> showCreateFileDialog()
                R.id.action_create_directory -> showCreateDirectoryDialog()
            }
            // Returning false causes the speed dial to close without animation.
            //return false;
            binding.speedDialView.close()
            true
        }

        if (!viewModel.hasTrail) {
            var path = argsPath
            val intent = args.intent
            var pickOptions: PickOptions? = null
            when (val action = intent.action ?: Intent.ACTION_VIEW) {
                Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT,
                Intent.ACTION_CREATE_DOCUMENT -> {
                    val readOnly = action == Intent.ACTION_GET_CONTENT
                    val mimeType = intent.type?.asMimeTypeOrNull() ?: MimeType.ANY
                    val extraMimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                        ?.mapNotNull { it.asMimeTypeOrNull() }?.takeIfNotEmpty()
                    val mimeTypes = extraMimeTypes ?: listOf(mimeType)
                    val localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)
                    val allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                    // TODO: Actually support ACTION_CREATE_DOCUMENT.
                    pickOptions = PickOptions(readOnly, false, mimeTypes, localOnly, allowMultiple)
                }
                Intent.ACTION_OPEN_DOCUMENT_TREE -> {
                    val localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)
                    pickOptions = PickOptions(false, true, emptyList(), localOnly, false)
                }
                ACTION_VIEW_DOWNLOADS ->
                    path = Paths.get(
                        @Suppress("DEPRECATION")
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        ).path
                    )
                Intent.ACTION_VIEW ->
                    if (path != null) {
                        val mimeType = intent.type?.asMimeTypeOrNull()
                        if (mimeType != null && path.isArchiveFile(mimeType)) {
                            path = path.createArchiveRootPath()
                        }
                    }
                else ->
                    if (path != null) {
                        val mimeType = intent.type?.asMimeTypeOrNull()
                        if (mimeType != null && path.isArchiveFile(mimeType)) {
                            path = path.createArchiveRootPath()
                        }
                    }
            }
            if (path == null) {
                path = Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat
            }
            viewModel.resetTo(path)
            if (pickOptions != null) {
                viewModel.pickOptions = pickOptions
            }
        }
        val viewLifecycleOwner = viewLifecycleOwner
        if (binding.persistentDrawerLayout != null) {
            Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.observe(viewLifecycleOwner) {
                onPersistentDrawerOpenChanged(it)
            }
        }
        viewModel.currentPathLiveData.observe(viewLifecycleOwner) { onCurrentPathChanged(it) }
        viewModel.searchViewExpandedLiveData.observe(viewLifecycleOwner) {
            onSearchViewExpandedChanged(it)
        }
        viewModel.breadcrumbLiveData.observe(viewLifecycleOwner) {
            binding.breadcrumbLayout.setData(it)
        }
        viewModel.sortOptionsLiveData.observe(viewLifecycleOwner) { onSortOptionsChanged(it) }
        viewModel.sortPathSpecificLiveData.observe(viewLifecycleOwner) {
            onSortPathSpecificChanged(it)
        }
        viewModel.pickOptionsLiveData.observe(viewLifecycleOwner) { onPickOptionsChanged(it) }
        viewModel.selectedFilesLiveData.observe(viewLifecycleOwner) { onSelectedFilesChanged(it) }
        viewModel.pasteStateLiveData.observe(viewLifecycleOwner) { onPasteStateChanged(it) }
        viewModel.fileListLiveData.observe(viewLifecycleOwner) { onFileListChanged(it) }
        Settings.FILE_LIST_SHOW_HIDDEN_FILES.observe(viewLifecycleOwner) {
            onShowHiddenFilesChanged(it)
        }
    }

    //region menu
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        menuBinding = MenuBinding.inflate(menu, inflater)
        setUpSearchView()
    }

    private fun setUpSearchView() {
        val searchView = menuBinding.searchItem.actionView as FixQueryChangeSearchView
        // MenuItem.OnActionExpandListener.onMenuItemActionExpand() is called before SearchView
        // resets the query.
        searchView.setOnSearchClickListener {
            viewModel.isSearchViewExpanded = true
            searchView.setQuery(viewModel.searchViewQuery, false)
            debouncedSearchRunnable()
        }
        // SearchView.OnCloseListener.onClose() is not always called.
        menuBinding.searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                viewModel.isSearchViewExpanded = false
                viewModel.stopSearching()
                return true
            }
        })
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                debouncedSearchRunnable.cancel()
                viewModel.search(query)
                return true
            }

            override fun onQueryTextChange(query: String): Boolean {
                if (searchView.shouldIgnoreQueryChange) {
                    return false
                }
                viewModel.searchViewQuery = query
                debouncedSearchRunnable()
                return false
            }
        })
        if (viewModel.isSearchViewExpanded) {
            menuBinding.searchItem.expandActionView()
        }
    }

    private fun collapseSearchView() {
        if (this::menuBinding.isInitialized && menuBinding.searchItem.isActionViewExpanded) {
            menuBinding.searchItem.collapseActionView()
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        updateSortMenuItems()
        updateSelectAllMenuItem()
        updateShowHiddenFilesMenuItem()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        LogUtil.se("onOptionsItemSelected")
        return when (item.itemId) {
            android.R.id.home -> {
                LogUtil.se("android.R.id.home")
                binding.drawerLayout?.openDrawer(GravityCompat.START)
                if (binding.persistentDrawerLayout != null) {
                    Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.putValue(
                        !Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.valueCompat
                    )
                }
                true
            }
            R.id.action_sort_by_name -> {
                viewModel.setSortBy(FileSortOptions.By.NAME)
                true
            }
            R.id.action_sort_by_type -> {
                viewModel.setSortBy(FileSortOptions.By.TYPE)
                true
            }
            R.id.action_sort_by_size -> {
                viewModel.setSortBy(FileSortOptions.By.SIZE)
                true
            }
            R.id.action_sort_by_last_modified -> {
                viewModel.setSortBy(FileSortOptions.By.LAST_MODIFIED)
                true
            }
            R.id.action_sort_order_ascending -> {
                viewModel.setSortOrder(
                    if (!menuBinding.sortOrderAscendingItem.isChecked) {
                        FileSortOptions.Order.ASCENDING
                    } else {
                        FileSortOptions.Order.DESCENDING
                    }
                )
                true
            }
            R.id.action_sort_directories_first -> {
                viewModel.setSortDirectoriesFirst(!menuBinding.sortDirectoriesFirstItem.isChecked)
                true
            }
            R.id.action_sort_path_specific -> {
                viewModel.isSortPathSpecific = !menuBinding.sortPathSpecificItem.isChecked
                true
            }
            R.id.action_new_task -> {
                newTask()
                true
            }
            R.id.action_navigate_up -> {
                navigateUp()
                true
            }
            R.id.action_refresh -> {
                refresh()
                true
            }
            R.id.action_select_all -> {
                selectAllFiles()
                true
            }
            R.id.action_show_hidden_files -> {
                setShowHiddenFiles(!menuBinding.showHiddenFilesItem.isChecked)
                true
            }
            R.id.action_share -> {
                share()
                true
            }
            R.id.action_copy_path -> {
                copyPath()
                true
            }
            R.id.action_open_in_terminal -> {
                openInTerminal()
                true
            }
            R.id.action_add_bookmark -> {
                addBookmark()
                true
            }
            R.id.action_create_shortcut -> {
                createShortcut()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun onBackPressed(): Boolean {
        val drawerLayout = binding.drawerLayout
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
            return true
        }
        if (binding.speedDialView.isOpen) {
            binding.speedDialView.close()
            return true
        }
        if (overlayActionMode.isActive) {
            overlayActionMode.finish()
            return true
        }
        return viewModel.navigateUp(false)
    }

    private fun onPersistentDrawerOpenChanged(open: Boolean) {
        binding.persistentDrawerLayout?.let {
            if (open) {
                it.openDrawer(GravityCompat.START)
            } else {
                it.closeDrawer(GravityCompat.START)
            }
        }
    }

    private fun onCurrentPathChanged(path: Path) {
        updateOverlayToolbar()
        updateBottomToolbar()
    }

    private fun onSearchViewExpandedChanged(expanded: Boolean) {
        updateSortMenuItems()
    }

    private fun onFileListChanged(stateful: Stateful<List<FileItem>>) {
        val files = stateful.value
        val isSearching = viewModel.searchState.isSearching
        when {
            stateful is Failure -> binding.toolbar.setSubtitle(R.string.error)
            stateful is Loading && !isSearching -> binding.toolbar.setSubtitle(R.string.loading)
            else -> binding.toolbar.subtitle = getSubtitle(files!!)
        }
        val hasFiles = !files.isNullOrEmpty()
        binding.swipeRefreshLayout.isRefreshing = stateful is Loading && (hasFiles || isSearching)
        binding.progress.fadeToVisibilityUnsafe(stateful is Loading && !(hasFiles || isSearching))
        binding.errorText.fadeToVisibilityUnsafe(stateful is Failure && !hasFiles)
        val throwable = (stateful as? Failure)?.throwable
        if (throwable != null) {
            throwable.printStackTrace()
            val error = throwable.toString()
            if (hasFiles) {
                showToast(error)
            } else {
                binding.errorText.text = error
            }
        }
        binding.emptyView.fadeToVisibilityUnsafe(stateful is Success && !hasFiles)
        if (files != null) {
            updateAdapterFileList()
        } else {
            // This resets animation as well.
            adapter.clear()
        }
        if (stateful is Success) {
            viewModel.pendingState
                ?.let { binding.recyclerView.layoutManager!!.onRestoreInstanceState(it) }
        }
        throwable?.let { onFileListFailure(it) }
    }

    private fun getSubtitle(files: List<FileItem>): String {
        val directoryCount = files.count { it.attributes.isDirectory }
        val fileCount = files.size - directoryCount
        val directoryCountText = if (directoryCount > 0) {
            getQuantityString(
                R.plurals.file_list_subtitle_directory_count_format, directoryCount, directoryCount
            )
        } else {
            null
        }
        val fileCountText = if (fileCount > 0) {
            getQuantityString(
                R.plurals.file_list_subtitle_file_count_format, fileCount, fileCount
            )
        } else {
            null
        }
        return when {
            !directoryCountText.isNullOrEmpty() && !fileCountText.isNullOrEmpty() ->
                (directoryCountText + getString(R.string.file_list_subtitle_separator)
                        + fileCountText)
            !directoryCountText.isNullOrEmpty() -> directoryCountText
            !fileCountText.isNullOrEmpty() -> fileCountText
            else -> getString(R.string.empty)
        }
    }

    private fun onFileListFailure(throwable: Throwable) {
        if (throwable is AccessDeniedException) {
            val path = viewModel.currentPath
            if (path.isLinuxPath
                && !EffortlessPermissions.hasPermissions(this, *STORAGE_PERMISSIONS)
            ) {
                EffortlessPermissions.requestPermissions(
                    this, R.string.storage_permission_request_message,
                    REQUEST_CODE_STORAGE_PERMISSIONS, *STORAGE_PERMISSIONS
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        EffortlessPermissions.onRequestPermissionsResult(
            requestCode, permissions, grantResults, this
        )
    }

    @AfterPermissionGranted(REQUEST_CODE_STORAGE_PERMISSIONS)
    private fun onStoragePermissionGranted() {
        refresh()
    }

    @AfterPermissionDenied(REQUEST_CODE_STORAGE_PERMISSIONS)
    private fun onStoragePermissionDenied() {
        if (EffortlessPermissions.somePermissionPermanentlyDenied(this, *STORAGE_PERMISSIONS)) {
            OpenAppDetailsDialogFragment.show(
                R.string.storage_permission_permanently_denied_message, R.string.open_settings, this
            )
        }
    }

    private fun onSortOptionsChanged(sortOptions: FileSortOptions) {
        adapter.comparator = sortOptions.createComparator()
        updateSortMenuItems()
    }

    private fun onSortPathSpecificChanged(pathSpecific: Boolean) {
        updateSortMenuItems()
    }

    private fun updateSortMenuItems() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val searchViewExpanded = viewModel.isSearchViewExpanded
        menuBinding.sortItem.isVisible = !searchViewExpanded
        if (searchViewExpanded) {
            return
        }
        val sortOptions = viewModel.sortOptions
        val checkedSortByMenuItem = when (sortOptions.by) {
            FileSortOptions.By.NAME -> menuBinding.sortByNameItem
            FileSortOptions.By.TYPE -> menuBinding.sortByTypeItem
            FileSortOptions.By.SIZE -> menuBinding.sortBySizeItem
            FileSortOptions.By.LAST_MODIFIED -> menuBinding.sortByLastModifiedItem
        }
        checkedSortByMenuItem.isChecked = true
        menuBinding.sortOrderAscendingItem.isChecked =
            sortOptions.order == FileSortOptions.Order.ASCENDING
        menuBinding.sortDirectoriesFirstItem.isChecked = sortOptions.isDirectoriesFirst
        menuBinding.sortPathSpecificItem.isChecked = viewModel.isSortPathSpecific
    }

    private fun navigateUp() {
        collapseSearchView()
        viewModel.navigateUp(true)
    }

    private fun newTask() {
        openInNewTask(currentPath)
    }

    private fun refresh() {
        viewModel.reload()
    }

    private fun setShowHiddenFiles(showHiddenFiles: Boolean) {
        Settings.FILE_LIST_SHOW_HIDDEN_FILES.putValue(showHiddenFiles)
    }

    private fun onShowHiddenFilesChanged(showHiddenFiles: Boolean) {
        updateAdapterFileList()
        updateShowHiddenFilesMenuItem()
    }

    private fun updateAdapterFileList() {
        var files = viewModel.fileListStateful.value ?: return
        if (!Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat) {
            files = files.filterNot { it.isHidden }
        }
        adapter.replaceListAndIsSearching(files, viewModel.searchState.isSearching)
    }

    private fun updateShowHiddenFilesMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val showHiddenFiles = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        menuBinding.showHiddenFilesItem.isChecked = showHiddenFiles
    }

    private fun share() {
        shareFile(currentPath, MimeType.DIRECTORY)
    }

    private fun copyPath() {
        copyPath(currentPath)
    }

    private fun openInTerminal() {
        val path = currentPath
        if (path.isLinuxPath) {
            Terminal.open(path.toFile().path, requireContext())
        } else {
            // TODO
        }
    }
    //endregion

    //region BreadcrumbLayout.Listener
    override fun navigateTo(path: Path) {
        collapseSearchView()
        val state = binding.recyclerView.layoutManager!!.onSaveInstanceState()
        viewModel.navigateTo(state!!, path)
    }

    override fun copyPath(path: Path) {
        clipboardManager.copyText(path.userFriendlyString, requireContext())
    }

    override fun openInNewTask(path: Path) {
        val intent = FileListActivity.createViewIntent(path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        startActivitySafe(intent)
    }
    //endregion

    //region pick file
    private fun onPickOptionsChanged(pickOptions: PickOptions?) {
        val title = if (pickOptions == null) {
            getString(R.string.file_list_title)
        } else {
            val titleRes = if (pickOptions.pickDirectory) {
                R.plurals.file_list_title_pick_directory
            } else {
                R.plurals.file_list_title_pick_file
            }
            val count = if (pickOptions.allowMultiple) Int.MAX_VALUE else 1
            getQuantityString(titleRes, count)
        }
        requireActivity().title = title
        updateSelectAllMenuItem()
        updateOverlayToolbar()
        updateBottomToolbar()
        adapter.pickOptions = pickOptions
    }

    private fun updateSelectAllMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val pickOptions = viewModel.pickOptions
        menuBinding.selectAllItem.isVisible = pickOptions == null || pickOptions.allowMultiple
    }

    private fun pickFiles(files: FileItemSet) {
        pickPaths(files.mapTo(linkedSetOf()) { it.path })
    }

    private fun pickPaths(paths: LinkedHashSet<Path>) {
        val intent = Intent().apply {
            val pickOptions = viewModel.pickOptions!!
            if (paths.size == 1) {
                val path = paths.single()
                data = path.fileProviderUri
                extraPath = path
            } else {
                val mimeTypes = pickOptions.mimeTypes.map { it.value }
                val items = paths.map { ClipData.Item(it.fileProviderUri) }
                clipData = ClipData::class.create(null, mimeTypes, items)
                extraPathList = paths.toList()
            }
            var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (!pickOptions.readOnly) {
                flags = flags or (Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            if (pickOptions.pickDirectory) {
                flags = flags or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            }
            addFlags(flags)
        }
        requireActivity().run {
            setResult(Activity.RESULT_OK, intent)
            finish()
        }
    }

    private fun onSelectedFilesChanged(files: FileItemSet) {
        updateOverlayToolbar()
        adapter.replaceSelectedFiles(files)
    }
    //endregion

    //region OverlayToolbar
    private fun updateOverlayToolbar() {
        val files = viewModel.selectedFiles
        if (files.isEmpty()) {
            if (overlayActionMode.isActive) {
                overlayActionMode.finish()
            }
            return
        }
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            overlayActionMode.title = getString(R.string.file_list_select_title_format, files.size)
            overlayActionMode.setMenuResource(R.menu.file_list_pick)
            val menu = overlayActionMode.menu
            menu.findItem(R.id.action_select_all).isVisible = pickOptions.allowMultiple
        } else {
            overlayActionMode.title = getString(R.string.file_list_select_title_format, files.size)
            overlayActionMode.setMenuResource(R.menu.file_list_select)
            val menu = overlayActionMode.menu
            val hasReadOnly = files.any { it.path.fileSystem.isReadOnly }
            menu.findItem(R.id.action_cut).isVisible = !hasReadOnly
            val isExtract = files.all { it.path.isArchivePath }
            menu.findItem(R.id.action_copy)
                .setIcon(
                    if (isExtract) {
                        R.drawable.extract_icon_white_24dp
                    } else {
                        R.drawable.copy_icon_control_normal_24dp
                    }
                )
                .setTitle(
                    if (isExtract) R.string.file_list_select_action_extract else R.string.copy
                )
            menu.findItem(R.id.action_delete).isVisible = !hasReadOnly
        }
        if (!overlayActionMode.isActive) {
            binding.appBarLayout.setExpanded(true)
            overlayActionMode.start(object : ToolbarActionMode.Callback {
                override fun onToolbarActionModeStarted(toolbarActionMode: ToolbarActionMode) {}

                override fun onToolbarActionModeItemClicked(
                    toolbarActionMode: ToolbarActionMode,
                    item: MenuItem
                ): Boolean = onOverlayActionModeItemClicked(toolbarActionMode, item)

                override fun onToolbarActionModeFinished(toolbarActionMode: ToolbarActionMode) {
                    onOverlayActionModeFinished(toolbarActionMode)
                }
            })
        }
    }

    private fun onOverlayActionModeItemClicked(
        toolbarActionMode: ToolbarActionMode,
        item: MenuItem
    ): Boolean =
        when (item.itemId) {
            R.id.action_pick -> {
                pickFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_cut -> {
                cutFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_copy -> {
                copyFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_delete -> {
                confirmDeleteFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_archive -> {
                showCreateArchiveDialog(viewModel.selectedFiles)
                true
            }
            R.id.action_share -> {
                shareFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_select_all -> {
                selectAllFiles()
                true
            }
            else -> false
        }

    private fun onOverlayActionModeFinished(toolbarActionMode: ToolbarActionMode) {
        viewModel.clearSelectedFiles()
    }

    private fun cutFiles(files: FileItemSet) {
        viewModel.addToPasteState(false, files)
        viewModel.selectFiles(files, false)
    }

    private fun copyFiles(files: FileItemSet) {
        viewModel.addToPasteState(true, files)
        viewModel.selectFiles(files, false)
    }

    //region ConfirmDeleteFilesDialogFragment.Listener
    private fun confirmDeleteFiles(files: FileItemSet) {
        ConfirmDeleteFilesDialogFragment.show(files, this)
    }

    override fun deleteFiles(files: FileItemSet) {
        FileJobService.delete(makePathListForJob(files), requireContext())
        viewModel.selectFiles(files, false)
    }
    //endregion

    private fun showCreateArchiveDialog(files: FileItemSet) {
        CreateArchiveDialogFragment.show(files, this)
    }

    //region CreateArchiveDialogFragment.Listener
    override fun archive(
        files: FileItemSet,
        name: String,
        archiveType: String,
        compressorType: String?
    ) {
        val archiveFile = viewModel.currentPath.resolve(name)
        FileJobService.archive(
            makePathListForJob(files), archiveFile, archiveType, compressorType, requireContext()
        )
        viewModel.selectFiles(files, false)
    }
    //endregion

    private fun shareFiles(files: FileItemSet) {
        shareFiles(files.map { it.path }, files.map { it.mimeType })
        viewModel.selectFiles(files, false)
    }

    private fun selectAllFiles() {
        adapter.selectAllFiles()
    }
    //endregion

    //region BottomToolbar
    private fun onPasteStateChanged(pasteState: PasteState) {
        updateBottomToolbar()
    }

    private fun updateBottomToolbar() {
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            if (!pickOptions.pickDirectory) {
                if (bottomActionMode.isActive) {
                    bottomActionMode.finish()
                }
                return
            }
            bottomActionMode.setNavigationIcon(R.drawable.check_icon_control_normal_24dp)
            val path = viewModel.currentPath
            val navigationRoot = NavigationRootMapLiveData.valueCompat[path]
            val name = navigationRoot?.getName(requireContext()) ?: path.name
            bottomActionMode.title =
                getString(R.string.file_list_select_current_directory_format, name)
        } else {
            val pasteState = viewModel.pasteState
            val files = pasteState.files
            if (files.isEmpty()) {
                if (bottomActionMode.isActive) {
                    bottomActionMode.finish()
                }
                return
            }
            bottomActionMode.setNavigationIcon(R.drawable.close_icon_control_normal_24dp)
            val isExtract = files.all { it.path.isArchivePath }
            bottomActionMode.title = getString(
                if (pasteState.copy) {
                    if (isExtract) {
                        R.string.file_list_paste_extract_title_format
                    } else {
                        R.string.file_list_paste_copy_title_format
                    }
                } else {
                    R.string.file_list_paste_move_title_format
                }, files.size
            )
            bottomActionMode.setMenuResource(R.menu.file_list_paste)
            val isReadOnly = viewModel.currentPath.fileSystem.isReadOnly
            bottomActionMode.menu.findItem(R.id.action_paste)
                .setTitle(
                    if (isExtract) R.string.file_list_paste_action_extract_here else R.string.paste
                )
                .isEnabled = !isReadOnly
        }
        if (!bottomActionMode.isActive) {
            bottomActionMode.start(object : ToolbarActionMode.Callback {
                override fun onToolbarActionModeStarted(toolbarActionMode: ToolbarActionMode) {}

                override fun onToolbarActionModeItemClicked(
                    toolbarActionMode: ToolbarActionMode,
                    item: MenuItem
                ): Boolean = onBottomActionModeItemClicked(toolbarActionMode, item)

                override fun onToolbarActionModeFinished(toolbarActionMode: ToolbarActionMode) {
                    onBottomActionModeFinished(toolbarActionMode)
                }
            })
        }
    }

    private fun onBottomActionModeItemClicked(
        toolbarActionMode: ToolbarActionMode,
        item: MenuItem
    ): Boolean =
        when (item.itemId) {
            R.id.action_paste -> {
                pasteFiles(currentPath)
                true
            }
            else -> false
        }

    private fun onBottomActionModeFinished(toolbarActionMode: ToolbarActionMode) {
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            if (pickOptions.pickDirectory) {
                pickPaths(linkedSetOf(viewModel.currentPath))
            }
        } else {
            viewModel.clearPasteState()
        }
    }

    private fun pasteFiles(targetDirectory: Path) {
        val pasteState = viewModel.pasteState
        if (viewModel.pasteState.copy) {
            FileJobService.copy(
                makePathListForJob(pasteState.files), targetDirectory, requireContext()
            )
        } else {
            FileJobService.move(
                makePathListForJob(pasteState.files), targetDirectory, requireContext()
            )
        }
        viewModel.clearPasteState()
    }

    private fun makePathListForJob(files: FileItemSet): List<Path> =
        files.map { it.path }.sorted()
    //endregion

    //region FileListAdapter
    override fun clearSelectedFiles() {
        viewModel.clearSelectedFiles()
    }

    override fun selectFile(file: FileItem, selected: Boolean) {
        viewModel.selectFile(file, selected)
    }

    override fun selectFiles(files: FileItemSet, selected: Boolean) {
        viewModel.selectFiles(files, selected)
    }

    override fun openFile(file: FileItem) {
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            if (file.attributes.isDirectory) {
                navigateTo(file.path)
            } else if (!pickOptions.pickDirectory) {
                pickFiles(fileItemSetOf(file))
            }
            return
        }
        if (file.mimeType.isApk) {
            openApk(file)
            return
        }
        if (file.isListable) {
            navigateTo(file.listablePath)
            return
        }
        openFileWithIntent(file, false)
    }

    private fun openApk(file: FileItem) {
        if (!file.isListable) {
            installApk(file)
            return
        }
        when (Settings.OPEN_APK_DEFAULT_ACTION.valueCompat) {
            OpenApkDefaultAction.INSTALL -> installApk(file)
            OpenApkDefaultAction.VIEW -> viewApk(file)
            OpenApkDefaultAction.ASK -> OpenApkDialogFragment.show(file, this)
        }
    }

    override fun installApk(file: FileItem) {
        val path = file.path
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (path.isLinuxPath || path.isDocumentPath) {
                path.fileProviderUri
            } else {
                null
            }
        } else {
            // PackageInstaller only supports file URI before N.
            if (path.isLinuxPath) {
                Uri.fromFile(path.toFile())
            } else {
                null
            }
        }
        if (uri != null) {
            startActivitySafe(uri.createInstallPackageIntent())
        } else {
            FileJobService.installApk(path, requireContext())
        }
    }

    override fun viewApk(file: FileItem) {
        navigateTo(file.listablePath)
    }

    override fun openFileWith(file: FileItem) {
        openFileWithIntent(file, true)
    }

    private fun openFileWithIntent(file: FileItem, withChooser: Boolean) {
        val path = file.path
        val mimeType = file.mimeType
        if (path.isLinuxPath || path.isDocumentPath) {
            val intent = path.fileProviderUri.createViewIntent(mimeType)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply {
                    extraPath = path
                    maybeAddImageViewerActivityExtras(this, path, mimeType)
                }
                .let {
                    if (withChooser) {
                        it.withChooser(
                            OpenFileAsDialogActivity::class.createIntent()
                                .putArgs(OpenFileAsDialogFragment.Args(path))
                        )
                    } else {
                        it
                    }
                }
            startActivitySafe(intent)
        } else {
            FileJobService.open(path, mimeType, withChooser, requireContext())
        }
    }

    private fun maybeAddImageViewerActivityExtras(intent: Intent, path: Path, mimeType: MimeType) {
        if (!mimeType.isImage) {
            return
        }
        val paths = mutableListOf<Path>()
        // We need the ordered list from our adapter instead of the list from FileListLiveData.
        for (index in 0 until adapter.itemCount) {
            val file = adapter.getItem(index)
            val filePath = file.path
            if (file.mimeType.isImage || filePath == path) {
                paths.add(filePath)
            }
        }
        val position = paths.indexOf(path)
        if (position == -1) {
            return
        }
        ImageViewerActivity.putExtras(intent, paths, position)
    }

    override fun cutFile(file: FileItem) {
        cutFiles(fileItemSetOf(file))
    }

    override fun copyFile(file: FileItem) {
        copyFiles(fileItemSetOf(file))
    }

    override fun confirmDeleteFile(file: FileItem) {
        confirmDeleteFiles(fileItemSetOf(file))
    }

    override fun showRenameFileDialog(file: FileItem) {
        RenameFileDialogFragment.show(file, this)
    }

    override fun hasFileWithName(name: String): Boolean {
        val fileListData = viewModel.fileListStateful
        return fileListData is Success && fileListData.value.any { it.name == name }
    }

    override fun renameFile(file: FileItem, newName: String) {
        FileJobService.rename(file.path, newName, requireContext())
        viewModel.selectFile(file, false)
    }

    override fun extractFile(file: FileItem) {
        copyFile(file.createDummyArchiveRoot())
    }

    override fun showCreateArchiveDialog(file: FileItem) {
        showCreateArchiveDialog(fileItemSetOf(file))
    }

    override fun shareFile(file: FileItem) {
        shareFile(file.path, file.mimeType)
    }

    private fun shareFile(path: Path, mimeType: MimeType) {
        shareFiles(listOf(path), listOf(mimeType))
    }

    private fun shareFiles(paths: List<Path>, mimeTypes: List<MimeType>) {
        val uris = paths.map { it.fileProviderUri }
        val intent = uris.createSendStreamIntent(mimeTypes)
            .withChooser()
        startActivitySafe(intent)
    }

    override fun copyPath(file: FileItem) {
        copyPath(file.path)
    }

    override fun addBookmark(file: FileItem) {
        addBookmark(file.path)
    }

    private fun addBookmark() {
        addBookmark(currentPath)
    }

    private fun addBookmark(path: Path) {
        BookmarkDirectories.add(BookmarkDirectory(null, path))
        showToast(R.string.file_add_bookmark_success)
    }

    override fun createShortcut(file: FileItem) {
        createShortcut(file.path, file.mimeType)
    }

    private fun createShortcut() {
        createShortcut(currentPath, MimeType.DIRECTORY)
    }

    private fun createShortcut(path: Path, mimeType: MimeType) {
        val context = requireContext()
        val isDirectory = mimeType == MimeType.DIRECTORY
        val shortcutInfo = ShortcutInfoCompat.Builder(context, path.toString())
            .setShortLabel(path.name)
            .setIntent(
                if (isDirectory) {
                    FileListActivity.createViewIntent(path)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                } else {
                    OpenFileActivity.createIntent(path, mimeType)
                }
            )
            .setIcon(
                IconCompat.createWithResource(
                    context, if (isDirectory) {
                        R.mipmap.directory_shortcut_icon
                    } else {
                        R.mipmap.file_shortcut_icon
                    }
                )
            )
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showToast(R.string.shortcut_created)
        }
    }

    override fun showPropertiesDialog(file: FileItem) {
        FilePropertiesDialogFragment.show(file, this)
    }

    private fun showCreateFileDialog() {
        CreateFileDialogFragment.show(this)
    }

    //region CreateFileDialogFragment.Listener
    override fun createFile(name: String) {
        val path = currentPath.resolve(name)
        FileJobService.create(path, false, requireContext())
    }
    //endregion
    //endregion

    //region CreateDirectoryDialogFragment.Listener
    private fun showCreateDirectoryDialog() {
        CreateDirectoryDialogFragment.show(this)
    }

    override fun createDirectory(name: String) {
        val path = currentPath.resolve(name)
        FileJobService.create(path, true, requireContext())
    }
    //endregion

    //region NavigationFragment.Listener
    override val currentPath: Path
        get() = viewModel.currentPath

    override fun navigateToRoot(path: Path) {
        collapseSearchView()
        viewModel.resetTo(path)
    }

    override fun navigateToDefaultRoot() {
        navigateToRoot(Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat)
    }

    override fun observeCurrentPath(owner: LifecycleOwner, observer: (Path) -> Unit) {
        viewModel.currentPathLiveData.observe(owner, observer)
    }

    override fun closeNavigationDrawer() {
        binding.drawerLayout?.closeDrawer(GravityCompat.START)
    }
    //endregion

    companion object {
        private const val ACTION_VIEW_DOWNLOADS =
            "me.zhanghai.android.files.intent.action.VIEW_DOWNLOADS"

        private const val REQUEST_CODE_STORAGE_PERMISSIONS = 1

        private val STORAGE_PERMISSIONS = arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs

    private class Binding private constructor(
        val root: View,
        val drawerLayout: DrawerLayout? = null,
        val persistentDrawerLayout: PersistentDrawerLayout? = null,
        val persistentBarLayout: PersistentBarLayout,
        val appBarLayout: AppBarLayout,
        val toolbar: Toolbar,
        val overlayToolbar: Toolbar,
        val breadcrumbLayout: BreadcrumbLayout,
        val contentLayout: ViewGroup,
        val progress: ProgressBar,
        val errorText: TextView,
        val emptyView: View,
        val swipeRefreshLayout: SwipeRefreshLayout,
        val recyclerView: RecyclerView,
        val bottomBarLayout: ViewGroup,
        val bottomToolbar: Toolbar,
        val speedDialView: SpeedDialView
    ) {
        companion object {
            fun inflate(
                inflater: LayoutInflater,
                root: ViewGroup?,
                attachToRoot: Boolean
            ): Binding {
                val binding = FilesFragmentBinding.inflate(inflater, root, attachToRoot)
                val bindingRoot = binding.root
                val includeBinding = FilesFragmentIncludeBinding.bind(bindingRoot)
                val appBarBinding = FilesFragmentAppBarIncludeBinding.bind(bindingRoot)
                val contentBinding = FilesFragmentContentIncludeBinding.bind(bindingRoot)
                val bottomBarBinding = FilesFragmentBottomBarIncludeBinding.bind(bindingRoot)
                val speedDialBinding = FilesFragmentSpeedDialIncludeBinding.bind(bindingRoot)
                includeBinding.coordinator?.apply {
                    val h = StatusBarUtil.getStatusBarHeight(BaseApplication.getContext())
                    updateLayoutParams<PersistentBarLayout.LayoutParams> {
                        topMargin = h
                    }
                }
                return Binding(
                    bindingRoot, includeBinding.drawerLayout, includeBinding.persistentDrawerLayout,
                    includeBinding.persistentBarLayout, appBarBinding.appBarLayout,
                    appBarBinding.toolbar, appBarBinding.overlayToolbar,
                    appBarBinding.breadcrumbLayout, contentBinding.contentLayout,
                    contentBinding.progress, contentBinding.errorText, contentBinding.emptyView,
                    contentBinding.swipeRefreshLayout, contentBinding.recyclerView,
                    bottomBarBinding.bottomBarLayout, bottomBarBinding.bottomToolbar,
                    speedDialBinding.speedDialView
                )
            }
        }
    }

    private class MenuBinding private constructor(
        val menu: Menu,
        val searchItem: MenuItem,
        val sortItem: MenuItem,
        val sortByNameItem: MenuItem,
        val sortByTypeItem: MenuItem,
        val sortBySizeItem: MenuItem,
        val sortByLastModifiedItem: MenuItem,
        val sortOrderAscendingItem: MenuItem,
        val sortDirectoriesFirstItem: MenuItem,
        val sortPathSpecificItem: MenuItem,
        val selectAllItem: MenuItem,
        val showHiddenFilesItem: MenuItem
    ) {
        companion object {
            fun inflate(menu: Menu, inflater: MenuInflater): MenuBinding {
                inflater.inflate(R.menu.file_list, menu)
                return MenuBinding(
                    menu, menu.findItem(R.id.action_search),
                    menu.findItem(R.id.action_sort),
                    menu.findItem(R.id.action_sort_by_name),
                    menu.findItem(R.id.action_sort_by_type),
                    menu.findItem(R.id.action_sort_by_size),
                    menu.findItem(R.id.action_sort_by_last_modified),
                    menu.findItem(R.id.action_sort_order_ascending),
                    menu.findItem(R.id.action_sort_directories_first),
                    menu.findItem(R.id.action_sort_path_specific),
                    menu.findItem(R.id.action_select_all),
                    menu.findItem(R.id.action_show_hidden_files)
                )
            }
        }
    }
}
