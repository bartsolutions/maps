package com.rnmapbox.rnmbx.modules

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.api.geocoding.v5.GeocodingCriteria
import com.mapbox.api.geocoding.v5.MapboxGeocoding
import com.mapbox.api.geocoding.v5.models.GeocodingResponse
import com.mapbox.bindgen.Value
import com.mapbox.common.Cancelable
import com.mapbox.common.TileDataDomain
import com.mapbox.common.TileRegionLoadOptions
import com.mapbox.common.TileStore
import com.mapbox.common.TileStoreOptions
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.options.RoutingTilesOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.search.common.AsyncOperationTask
import com.mapbox.search.offline.OfflineIndexChangeEvent
import com.mapbox.search.offline.OfflineIndexChangeEvent.EventType
import com.mapbox.search.offline.OfflineIndexErrorEvent
import com.mapbox.search.offline.OfflineResponseInfo
import com.mapbox.search.offline.OfflineReverseGeoOptions
import com.mapbox.search.offline.OfflineSearchCallback
import com.mapbox.search.offline.OfflineSearchEngine
import com.mapbox.search.offline.OfflineSearchEngineSettings
import com.mapbox.search.offline.OfflineSearchOptions
import com.mapbox.search.offline.OfflineSearchResult
import org.json.JSONException
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response


class RNMBXNavigationModule private constructor(private val mReactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(mReactContext) {

    override fun getName(): String {
        return REACT_CLASS
    }

    @ReactMethod
    fun addListener(eventName: String?) {
        // Set up any upstream listeners or background tasks as necessary
    }

    @ReactMethod
    fun removeListeners(count: Int?) {
        // Remove upstream listeners, stop unnecessary background tasks
    }

    private var searchRequestTask: AsyncOperationTask? = null
    private lateinit var tilesLoadingTask: Cancelable

    private val tileStore: TileStore by lazy {
        TileStore.create().also {
            // Set default access token for the created tile store instance
            it.setOption(
                TileStoreOptions.MAPBOX_ACCESS_TOKEN,
                TileDataDomain.MAPS,
                Value(RNMBXModule.getAccessToken(mReactContext))
            )
            it.setOption(
                TileStoreOptions.MAPBOX_ACCESS_TOKEN,
                TileDataDomain.NAVIGATION,
                Value(RNMBXModule.getAccessToken(mReactContext))
            )
            it.setOption(
                TileStoreOptions.MAPBOX_ACCESS_TOKEN,
                TileDataDomain.SEARCH,
                Value(RNMBXModule.getAccessToken(mReactContext))
            )
        }
    }

    val mapboxNavigation: MapboxNavigation by lazy {
        val routingTilesOptions = RoutingTilesOptions.Builder()
            .tileStore(tileStore)
            .build()

        var navOptions = NavigationOptions.Builder(mReactContext)
            .accessToken(RNMBXModule.getAccessToken(mReactContext))
            .routingTilesOptions(routingTilesOptions)
            .build()

        MapboxNavigation(navOptions)
    }

    val searchEngine: OfflineSearchEngine by lazy {
        OfflineSearchEngine.create(
            OfflineSearchEngineSettings(
                tileStore = tileStore,
                accessToken = RNMBXModule.getAccessToken(mReactContext)
            )
        )
    }

    private val engineReadyCallback = object : OfflineSearchEngine.EngineReadyCallback {
        override fun onEngineReady() {
            Log.i("SearchApiExample", "Engine is ready")
        }
    }

    private val searchCallback = object : OfflineSearchCallback {

        override fun onResults(results: List<OfflineSearchResult>, responseInfo: OfflineResponseInfo) {
            Log.i("SearchApiExample", "Results: $results")
        }

        override fun onError(e: Exception) {
            Log.i("SearchApiExample", "Search error", e)
        }
    }

    @ReactMethod
    @Throws(JSONException::class)
    fun geocoding(point: ReadableArray, language: String, promise: Promise)
    {
        val longitude = point.getDouble(0)
        val latitude = point.getDouble(1)
        val tileStoreOffline = TileStore.create()
        val dcLocation = Point.fromLngLat(136.0339911055176, 37.899920004207516)
        val tileRegionId = "hk-offline-search-map"
        val descriptors = listOf(OfflineSearchEngine.createTilesetDescriptor())
        val tileRegionLoadOptions = TileRegionLoadOptions.Builder()
            .descriptors(descriptors)
            .geometry(dcLocation)
            .acceptExpired(true)
            .build()

        var offlineSearchEngine = OfflineSearchEngine.create(
            OfflineSearchEngineSettings(
                tileStore = tileStoreOffline,
                accessToken = RNMBXModule.getAccessToken(mReactContext)
            )
        )
        offlineSearchEngine.addEngineReadyCallback(engineReadyCallback)
        val token = RNMBXModule.getAccessToken(mReactContext)
        Log.i("SearchApiExample", "token $token")
        offlineSearchEngine.addOnIndexChangeListener(object : OfflineSearchEngine.OnIndexChangeListener {
            override fun onIndexChange(event: OfflineIndexChangeEvent) {
                if (event.regionId == tileRegionId && (event.type == EventType.ADD || event.type == EventType.UPDATE)) {
                    Log.i("SearchApiExample", "$tileRegionId was successfully added or updated")

                    searchRequestTask = offlineSearchEngine.reverseGeocoding(
                        OfflineReverseGeoOptions(center = dcLocation),
                        searchCallback
                    )
                }
            }

            override fun onError(event: OfflineIndexErrorEvent) {
                Log.i("SearchApiExample", "Offline index error: $event")
            }
        })

        Log.i("SearchApiExample", "Loading tiles...")

        tilesLoadingTask = tileStoreOffline.loadTileRegion(
            tileRegionId,
            tileRegionLoadOptions,
            { progress -> Log.i("SearchApiExample", "Loading progress: $progress") },
            { result ->
                if (result.isValue) {
                    Log.i("SearchApiExample", "Tiles successfully loaded: ${result.value}")
                } else {
                    Log.i("SearchApiExample", "Tiles loading error: ${result.error}")
                }
            }
        )
    }


    @ReactMethod
    @Throws(JSONException::class)
    fun calculateRoute(readableArray: ReadableArray, promise: Promise)
    {
        val waypoints = ArrayList<Point>()

        for (i in 0 until readableArray.size()) {
            val coordinateArray: ReadableArray = readableArray.getArray(i)
            val longitude = coordinateArray.getDouble(0)
            val latitude = coordinateArray.getDouble(1)
            val waypoint: Point = Point.fromLngLat(longitude, latitude)
            waypoints.add(waypoint)
        }
        val routeOptions = RouteOptions.builder()
            .applyDefaultNavigationOptions()
            .coordinatesList(waypoints)
            .profile(DirectionsCriteria.PROFILE_WALKING)
            .build()

        try {
            mapboxNavigation.requestRoutes(
                routeOptions,
                object : NavigationRouterCallback {
                    override fun onCanceled(routeOptions: RouteOptions, routerOrigin: RouterOrigin) {
                        // no impl
                    }

                    override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                        // no impl
                    }

                    override fun onRoutesReady(
                        routes: List<NavigationRoute>,
                        routerOrigin: RouterOrigin
                    ) {
                        if (routes.isNotEmpty()) {
                            val route = routes[0] // 获取第一条路径
                            val lineString = LineString.fromPolyline(route.directionsRoute.geometry()!!, 6)

                            // 将路径的 GeoJSON 数据返回给你的逻辑
                            val featureCollection = FeatureCollection.fromFeatures(
                                arrayOf(Feature.fromGeometry(lineString))
                            )
                            val result = Arguments.createMap()
                            result.putString("geoJson", featureCollection.toJson())
                            promise.resolve(result)
                        } else {
                            promise.reject("ERROR", "No routes found")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            promise.reject("ERROR", e)
        }
    }

    companion object {
        const val REACT_CLASS = "RNMBXNavigationModule"
        const val LOG_TAG = REACT_CLASS
        private var instance: RNMBXNavigationModule? = null

        @JvmStatic
        fun getInstance(reactContext: ReactApplicationContext): RNMBXNavigationModule {
            if (instance == null) {
                instance = RNMBXNavigationModule(reactContext)
            }
            return instance as RNMBXNavigationModule
        }
    }
}
