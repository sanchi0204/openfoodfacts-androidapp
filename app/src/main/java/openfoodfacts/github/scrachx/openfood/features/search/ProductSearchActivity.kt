package openfoodfacts.github.scrachx.openfood.features.search

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.SearchView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.squareup.picasso.Picasso
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.rx2.awaitSingleOrNull
import kotlinx.coroutines.withContext
import openfoodfacts.github.scrachx.openfood.R
import openfoodfacts.github.scrachx.openfood.customtabs.CustomTabActivityHelper
import openfoodfacts.github.scrachx.openfood.databinding.ActivityProductBrowsingListBinding
import openfoodfacts.github.scrachx.openfood.features.adapters.ProductSearchAdapter
import openfoodfacts.github.scrachx.openfood.features.shared.BaseActivity
import openfoodfacts.github.scrachx.openfood.listeners.CommonBottomListenerInstaller.installBottomNavigation
import openfoodfacts.github.scrachx.openfood.listeners.CommonBottomListenerInstaller.selectNavigationItem
import openfoodfacts.github.scrachx.openfood.listeners.EndlessRecyclerViewScrollListener
import openfoodfacts.github.scrachx.openfood.listeners.RecyclerItemClickListener
import openfoodfacts.github.scrachx.openfood.models.Search
import openfoodfacts.github.scrachx.openfood.models.SearchInfo
import openfoodfacts.github.scrachx.openfood.models.SearchProduct
import openfoodfacts.github.scrachx.openfood.network.OpenFoodAPIClient
import openfoodfacts.github.scrachx.openfood.repositories.ProductRepository
import openfoodfacts.github.scrachx.openfood.utils.*
import openfoodfacts.github.scrachx.openfood.utils.SearchType.*
import java.text.NumberFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class ProductSearchActivity : BaseActivity() {
    private var _binding: ActivityProductBrowsingListBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var client: OpenFoodAPIClient

    @Inject
    lateinit var productRepository: ProductRepository

    @Inject
    lateinit var picasso: Picasso

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    @Inject
    lateinit var localeManager: LocaleManager

    private lateinit var mSearchInfo: SearchInfo
    private lateinit var adapter: ProductSearchAdapter

    private var contributionType = 0
    private var disp = CompositeDisposable()
    private val lowBatteryMode by lazy { isDisableImageLoad() && isBatteryLevelLow() }

    /**
     * boolean to determine if image should be loaded or not
     */
    private var setupDone = false
    private var mCountProducts = 0
    private var pageAddress = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityProductBrowsingListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarInclude.toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        // OnClick
        binding.buttonTryAgain.setOnClickListener { reloadSearch() }
        binding.addProduct.setOnClickListener { addProduct() }

        binding.textCountProduct.visibility = View.INVISIBLE

        // Get the search information (query, title, type) that we will use in this activity
        val extras = intent.extras
        if (extras != null) {
            mSearchInfo = extras.getParcelable(SEARCH_INFO) ?: SearchInfo.emptySearchInfo()
        } else if (Intent.ACTION_VIEW == intent.action) {
            // the user has entered the activity via a url
            val data = intent.data
            if (data != null) {
                // TODO: If we open advanced search from app it redirects here
                val paths = data.toString().split("/")
                mSearchInfo = SearchInfo.emptySearchInfo()

                if (paths[3] == "cgi" && paths[4].contains("search.pl")) {
                    mSearchInfo.searchTitle = data.getQueryParameter("search_terms") ?: ""
                    mSearchInfo.searchQuery = data.getQueryParameter("search_terms") ?: ""
                    mSearchInfo.searchType = SEARCH
                } else {
                    mSearchInfo.searchTitle = paths[4]
                    mSearchInfo.searchQuery = paths[4]
                    mSearchInfo.searchType = SearchType.fromUrl(paths[3]) ?: SEARCH
                }

            } else {
                Log.i(LOG_TAG, "No data was passed in with URL. Exiting.")
                finish()
            }
        } else {
            Log.e(LOG_TAG, "No data passed to the activity. Exiting.")
            finish()
        }
        newSearch()

        binding.navigationBottom.bottomNavigation.selectNavigationItem(0)
        binding.navigationBottom.bottomNavigation.installBottomNavigation(this)
    }

    override fun onDestroy() {
        disp.dispose()
        _binding = null
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchMenuItem = menu.findItem(R.id.action_search)
        val searchView = searchMenuItem.actionView as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                mSearchInfo.searchQuery = query
                mSearchInfo.searchType = SEARCH
                newSearch()
                return true
            }

            override fun onQueryTextChange(newText: String) = true
        })
        searchMenuItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                supportActionBar?.title = ""
                finish()
                return true
            }
        })
        if (CONTRIBUTOR == mSearchInfo.searchType) {
            menu.findItem(R.id.action_set_type).isVisible = true
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_set_type -> {
                val contributionTypes = arrayOf(
                    getString(R.string.products_added),
                    getString(R.string.products_incomplete),
                    getString(R.string.product_pictures_contributed),
                    getString(R.string.picture_contributed_incomplete),
                    getString(R.string.product_info_added),
                    getString(R.string.product_info_tocomplete)
                )
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.show_by)
                    .setItems(contributionTypes) { _, position ->
                        contributionType = when (position) {
                            in 1..5 -> position
                            else -> 0
                        }
                        newSearch()
                    }.show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupHungerGames() {
        val actualCountryTag = sharedPreferences.getString(getString(R.string.pref_country_key), "")
        if (actualCountryTag.isNullOrBlank()) {
            lifecycleScope.launch {
                val url = productRepository
                    .getCountryByCC2OrWorld(localeManager.getLocale().country)
                    .awaitSingleOrNull()
                    ?.tag ?: "en:world"
                withContext(Main) { setupUrlHungerGames(url) }
            }
        } else {
            setupUrlHungerGames(actualCountryTag)
        }
    }

    private fun setupUrlHungerGames(countryTag: String?) {
        val url = ("https://hunger.openfoodfacts.org/questions?" +
                "type=${mSearchInfo.searchType.url}" +
                "&value_tag=${mSearchInfo.searchQuery}" +
                "&country=$countryTag").toUri()
        val builder = CustomTabsIntent.Builder()
        val customTabsIntent = builder.build()
        binding.btnHungerGames.visibility = View.VISIBLE
        binding.btnHungerGames.text = resources.getString(R.string.hunger_game_call_to_action, mSearchInfo.searchTitle)
        binding.btnHungerGames.setOnClickListener {
            CustomTabActivityHelper.openCustomTab(this, customTabsIntent, url, null)
        }
    }

    private fun newSearch() {
        val bar = supportActionBar ?: error("Support action bar not set.")

        bar.title = mSearchInfo.searchTitle
        when (mSearchInfo.searchType) {
            BRAND -> {
                bar.setSubtitle(R.string.brand_string)
                setupHungerGames()
            }
            LABEL -> {
                bar.subtitle = getString(R.string.label_string)
                setupHungerGames()
            }
            CATEGORY -> {
                bar.subtitle = getString(R.string.category_string)
                setupHungerGames()
            }
            COUNTRY -> bar.setSubtitle(R.string.country_string)
            ORIGIN -> bar.setSubtitle(R.string.origin_of_ingredients)
            MANUFACTURING_PLACE -> bar.setSubtitle(R.string.manufacturing_place)
            ADDITIVE -> bar.setSubtitle(R.string.additive_string)
            SEARCH -> bar.setSubtitle(R.string.search_string)
            STORE -> bar.setSubtitle(R.string.store_subtitle)
            PACKAGING -> bar.setSubtitle(R.string.packaging_subtitle)
            CONTRIBUTOR -> bar.subtitle = getString(R.string.contributor_string)
            ALLERGEN -> bar.subtitle = getString(R.string.allergen_string)
            INCOMPLETE_PRODUCT -> bar.title = getString(R.string.products_to_be_completed)
            STATE -> bar.setSubtitle(R.string.state_subtitle)
            TRACE -> bar.setSubtitle(R.string.traces)
            EMB -> bar.setSubtitle(R.string.emb_code)
        }

        binding.progressBar.visibility = View.VISIBLE
        reloadSearch()
    }

    private fun reloadSearch() {
        binding.offlineCloudLinearLayout.visibility = View.INVISIBLE
        binding.textCountProduct.visibility = View.INVISIBLE
        binding.noResultsLayout.visibility = View.INVISIBLE

        pageAddress = 1
        loadDataFromAPI()
    }

    /**
     * When no matching products are found in the database then noResultsLayout is displayed.
     * This method is called when the user clicks on the add photo button in the noResultsLayout.
     */
    private fun addProduct() {
        when {
            checkSelfPermission(this, Manifest.permission.CAMERA) == PERMISSION_GRANTED -> startScanActivity()
            shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.action_about)
                    .setMessage(R.string.permission_camera)
                    .setNeutralButton(android.R.string.ok) { _, _ ->
                        requestCameraThenOpenScan.launch(Manifest.permission.CAMERA)
                    }.show()
            }
            else -> requestCameraThenOpenScan.launch(Manifest.permission.CAMERA)
        }
    }


    fun loadDataFromAPI() {
        val searchQuery = mSearchInfo.searchQuery
        when (mSearchInfo.searchType) {
            BRAND -> client.getProductsByBrand(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_brand_products)

            COUNTRY -> client.getProductsByCountry(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_country_products)

            ORIGIN -> client.getProductsByOrigin(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_country_products)

            MANUFACTURING_PLACE -> client.getProductsByManufacturingPlace(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_country_products)

            ADDITIVE -> client.getProductsByAdditive(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_additive_products)

            STORE -> client.getProductsByStore(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_store_products)

            PACKAGING -> client.getProductsByPackaging(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_packaging_products)

            SEARCH -> {
                if (isBarcodeValid(searchQuery)) {
                    client.openProduct(searchQuery, this)
                } else {
                    client.searchProductsByName(searchQuery, pageAddress)
                        .startSearch(R.string.txt_no_matching_products, R.string.txt_broaden_search)
                }
            }
            LABEL -> client.getProductsByLabel(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_label_products)

            CATEGORY -> client.getProductsByCategory(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching__category_products)

            ALLERGEN -> client.getProductsByAllergen(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_allergen_products)

            CONTRIBUTOR -> loadDataForContributor(searchQuery)

            STATE -> client.getProductsByStates(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_allergen_products)

            // Get Products to be completed data and input it to loadData function
            INCOMPLETE_PRODUCT -> client.getIncompleteProducts(pageAddress)
                .startSearch(R.string.txt_no_matching_incomplete_products)

            else -> Log.e("Products Browsing", "No match case found for ${mSearchInfo.searchType}")
        }
    }


    private fun Single<Search>.startSearch(@StringRes noMatchMsg: Int, @StringRes extendedMsg: Int = -1) {
        lifecycleScope.launch(Main) {
            var throwable: Throwable? = null
            val search = try {
                withContext(IO) { this@startSearch.await() }
            } catch (err: Exception) {
                throwable = err
                null
            }

            displaySearch(throwable == null, search, noMatchMsg, extendedMsg)
        }
    }

    private fun loadDataForContributor(searchQuery: String) {
        when (contributionType) {
            1 -> client.getToBeCompletedProductsByContributor(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_contributor_products)

            2 -> client.getPicturesContributedProducts(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_contributor_products)

            3 -> client.getPicturesContributedIncompleteProducts(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_contributor_products)

            4 -> client.getInfoAddedProducts(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_contributor_products)

            5 -> client.getInfoAddedIncompleteProductsSingle(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_contributor_products)

            else -> client.getProductsByContributor(searchQuery, pageAddress)
                .startSearch(R.string.txt_no_matching_contributor_products)
        }
    }

    private fun showSuccessfulSearch(search: Search) {
        mCountProducts = search.count.toInt()
        if (pageAddress == 1) {
            val number = NumberFormat.getInstance(Locale.getDefault()).format(search.count.toLong())
            binding.textCountProduct.text = "${resources.getString(R.string.number_of_results)} $number"

            // Hacky thing to make sure the count is right
            val products: MutableList<SearchProduct?> = search.products.toMutableList()
            if (products.size < mCountProducts) {
                products += null
            }

            if (setupDone) {
                adapter = ProductSearchAdapter(products, lowBatteryMode, this, picasso, client, localeManager)
                binding.productsRecyclerView.adapter = adapter
            }

            setUpRecyclerView(products)

        } else if (adapter.products.size - 1 < mCountProducts + 1) {
            val posStart = adapter.itemCount

            adapter.products.removeAt(posStart - 1)
            adapter.products += search.products

            // Hacky thing to make sure the count is right
            if (adapter.products.size < mCountProducts) {
                adapter.products += null
            }

            adapter.notifyItemRangeChanged(posStart - 1, adapter.products.size - 1)
        }
    }

    private fun showOfflineCloud() {
        binding.swipeRefresh.isRefreshing = false
        binding.textCountProduct.visibility = View.GONE
        binding.productsRecyclerView.visibility = View.INVISIBLE
        binding.progressBar.visibility = View.INVISIBLE
        binding.offlineCloudLinearLayout.visibility = View.VISIBLE
    }

    /**
     * Shows UI indicating that no matching products were found.
     *
     * Called by [displaySearch].
     *
     * @param message message to display when there are no results for given search
     * @param extendedMessage additional message to display, -1 if no message is displayed
     */
    private fun showEmptyResponse(@StringRes message: Int, @StringRes extendedMessage: Int) {
        binding.swipeRefresh.isRefreshing = false

        binding.productsRecyclerView.visibility = View.INVISIBLE
        binding.progressBar.visibility = View.INVISIBLE
        binding.offlineCloudLinearLayout.visibility = View.INVISIBLE
        binding.textCountProduct.visibility = View.GONE

        binding.textNoResults.setText(message)
        if (extendedMessage != -1) binding.textExtendSearch.setText(extendedMessage)
        binding.noResultsLayout.bringToFront()
        binding.noResultsLayout.visibility = View.VISIBLE

    }

    /**
     * Loads the search results into the UI, otherwise shows UI indicating that no matching
     * products were found.
     *
     * @param isResponseSuccessful true if the search response was successful
     * @param response the search results
     * @param emptyMessage message to display if there are no results
     * @param extendedMessage extended message to display if there are no results
     */
    private fun displaySearch(
        isResponseSuccessful: Boolean,
        response: Search?,
        @StringRes emptyMessage: Int,
        @StringRes extendedMessage: Int = -1
    ) {
        if (response == null || !isResponseSuccessful) {
            showOfflineCloud()
            return
        }

        val count = try {
            response.count.toInt()
        } catch (e: NumberFormatException) {
            throw NumberFormatException("Cannot parse ${response.count}.")
        }

        if (count == 0) {
            showEmptyResponse(emptyMessage, extendedMessage)
        } else {
            showSuccessfulSearch(response)
        }
    }

    private fun setUpRecyclerView(mProducts: MutableList<SearchProduct?>) {
        binding.swipeRefresh.isRefreshing = false

        binding.progressBar.visibility = View.INVISIBLE
        binding.offlineCloudLinearLayout.visibility = View.INVISIBLE

        binding.textCountProduct.visibility = View.VISIBLE
        binding.productsRecyclerView.visibility = View.VISIBLE

        if (!setupDone) {
            binding.productsRecyclerView.setHasFixedSize(true)
            val mLayoutManager = LinearLayoutManager(this@ProductSearchActivity, LinearLayoutManager.VERTICAL, false)
            binding.productsRecyclerView.layoutManager = mLayoutManager
            adapter = ProductSearchAdapter(mProducts, lowBatteryMode, this, picasso, client, localeManager)
            binding.productsRecyclerView.adapter = adapter
            val dividerItemDecoration = DividerItemDecoration(binding.productsRecyclerView.context, DividerItemDecoration.VERTICAL)
            binding.productsRecyclerView.addItemDecoration(dividerItemDecoration)

            // Retain an instance so that you can call `resetState()` for fresh searches
            // Adds the scroll listener to RecyclerView
            binding.productsRecyclerView.addOnScrollListener(object : EndlessRecyclerViewScrollListener(mLayoutManager) {
                override fun onLoadMore(page: Int, totalItemsCount: Int, view: RecyclerView) {
                    if (mProducts.size < mCountProducts) {
                        pageAddress = page
                        loadDataFromAPI()
                    }
                }
            })
            binding.productsRecyclerView.addOnItemTouchListener(RecyclerItemClickListener(this) { _, position ->
                val product = adapter.getProduct(position) ?: return@RecyclerItemClickListener
                val barcode = product.code

                if (Utils.isNetworkConnected(this)) {
                    client.openProduct(barcode, this)
                    try {
                        val viewWithFocus = currentFocus
                        if (viewWithFocus != null) {
                            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.hideSoftInputFromWindow(viewWithFocus.windowToken, 0)
                        }
                    } catch (e: NullPointerException) {
                        Log.e(LOG_TAG, "addOnItemTouchListener", e)
                    }
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.device_offline_dialog_title)
                        .setMessage(R.string.connectivity_check)
                        .setPositiveButton(R.string.txt_try_again) { _, _ ->
                            if (Utils.isNetworkConnected(this)) {
                                client.openProduct(barcode, this)
                            } else {
                                Toast.makeText(this@ProductSearchActivity, R.string.device_offline_dialog_title, Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton(R.string.dismiss) { d, _ -> d.dismiss() }
                        .show()
                }
                return@RecyclerItemClickListener
            })

            binding.swipeRefresh.setOnRefreshListener {
                mProducts.clear()
                adapter.notifyDataSetChanged()
                binding.textCountProduct.text = resources.getString(R.string.number_of_results)
                pageAddress = 1
                reloadSearch()
                if (binding.swipeRefresh.isRefreshing) {
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }

        setupDone = true
        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.isRefreshing = true
            pageAddress = 1
            reloadSearch()
        }
    }

    companion object {
        /**
         * Must be public to be visible by TakeScreenshotIncompleteProductsTest class.
         */
        const val SEARCH_INFO = "search_info"

        /**
         * Start a new [ProductSearchActivity] given a search information
         *
         * @param context the context to use to start this activity
         * @param searchQuery the search query
         * @param searchTitle the title used in the activity for this search query
         * @param type the type of search
         */
        @JvmStatic
        fun start(context: Context, type: SearchType, searchQuery: String, searchTitle: String = searchQuery) =
            start(context, SearchInfo(type, searchQuery, searchTitle))

        /**
         * @see [start]
         */
        @JvmStatic
        private fun start(context: Context, searchInfo: SearchInfo) {
            context.startActivity(Intent(context, ProductSearchActivity::class.java).apply {
                putExtra(SEARCH_INFO, searchInfo)
            })
        }

        private val LOG_TAG: String = ProductSearchActivity::class.simpleName!!
    }
}
