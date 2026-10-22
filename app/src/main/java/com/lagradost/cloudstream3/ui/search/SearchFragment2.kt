package com.lagradost.cloudstream3.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.fragment.findNavController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.plugins.ALLOWED_PLUGIN_API_NAME
import com.lagradost.cloudstream3.ui.lite.*
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import kotlinx.coroutines.delay

class SearchFragment2 : BaseComposeFragment() {
    // A stable view ID lets Fragment restore Compose saveable state on back/recreation.
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        super.onCreateView(inflater, container, savedInstanceState).apply { id = R.id.lite_search_compose }

    override fun onDestroyView() {
        hideKeyboard()
        super.onDestroyView()
    }

    @Composable override fun Content() {
        val model = viewModel { SearchViewModel() }
        var query by rememberSaveable { mutableStateOf(arguments?.getString("search_query")?.takeIf { it.isNotBlank() } ?: model.lastQuery.orEmpty()) }
        val gridState = rememberLazyGridState()
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        var requestFocus by rememberSaveable { mutableStateOf(arguments?.getBoolean("lite_focus_search") == true) }
        LaunchedEffect(Unit) {
            if (requestFocus) {
                focusRequester.requestFocus()
                keyboard?.show()
                requestFocus = false
                arguments?.remove("lite_focus_search")
            }
        }
        val results by model.searchResponse.liteState()
        val history by model.currentHistory.liteState()
        val moreError by model.moreError.liteState(false)
        val moreLoading by model.moreLoading.liteState(false)
        fun search() {
            model.reloadRepos()
            model.searchAndCancel(query.trim(), setOf(ALLOWED_PLUGIN_API_NAME), ignoreSettings = true)
        }
        LaunchedEffect(query) {
            model.updateHistory()
            if (query.trim().length < 2) model.clearSearch()
            else if (query.trim() != model.lastQuery || model.searchResponse.value == null) {
                delay(500)
                gridState.requestScrollToItem(0)
                search()
            }
        }
        Column(Modifier.fillMaxSize().background(LiteColors.background)) {
            Row(Modifier.fillMaxWidth().padding(LiteSpacing.screenPadding), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    keyboard?.hide()
                    val nav = findNavController()
                    if (!nav.popBackStack()) nav.navigate(R.id.navigation_home)
                }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_baseline_arrow_back_24), stringResource(R.string.go_back))
                }
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.title_search)) },
                    modifier = Modifier.weight(1f).focusRequester(focusRequester))
            }
            if (query.trim().length < 2) {
                LazyColumn {
                    items(history.orEmpty(), key = { it.key }) { item ->
                        TextButton(onClick = { query = item.searchText }) { Text(item.searchText) }
                    }
                }
            } else when (val current = results) {
                null, is Resource.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
                is Resource.Failure -> LiteErrorState(stringResource(R.string.error), ::search)
                is Resource.Success -> if (current.value.list.isEmpty()) LiteEmptyState(stringResource(R.string.no_data))
                else key(model.lastQuery) {
                    LiteGrid(current.value.list, { loadResult(it.url, it.apiName, it.name) },
                        hasNext = current.value.hasNext, loading = moreLoading == true, error = moreError == true,
                        pageKey = current.value.currentPage, onLoadMore = { model.loadMore() }, state = gridState)
                }
            }
        }
    }
}
