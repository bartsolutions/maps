package com.rnmapbox.rnmbx.modules

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import android.content.Context
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.api.geocoding.v5.GeocodingCriteria
import com.mapbox.api.geocoding.v5.MapboxGeocoding
import com.mapbox.api.geocoding.v5.models.GeocodingResponse
import com.mapbox.bindgen.Expected
import com.mapbox.bindgen.Value
import com.mapbox.common.NetworkRestriction
import com.mapbox.common.TileRegionLoadOptions
import com.mapbox.common.TileRegionLoadProgress
import com.mapbox.common.TileStore
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.options.RoutingTilesOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationProvider
import com.mapbox.navigation.core.navigator.offline.TilesetUpdateAvailabilityResult
import com.mapbox.navigation.core.navigator.offline.TilesetVersion
import com.mapbox.navigation.core.navigator.offline.TilesetVersionManager
import com.rnmapbox.rnmbx.modules.RNMBXLocationModule
import com.rnmapbox.rnmbx.modules.RNMBXModule
import com.rnmapbox.rnmbx.utils.Logger
import org.json.JSONException
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap


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


    private val tileStore: TileStore by lazy {
        TileStore.create()
    }

    private val prefs by lazy {
        mReactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // The router is pinned to the routing tiles version on the device. Unpinned, it switches to a
    // newer server version on its own, and offline routing then fails until new tiles are downloaded.
    private val pinnedTilesVersion: String?
        get() = prefs.getString(KEY_TILES_VERSION, null)

    private var navigation: MapboxNavigation? = null

    private val mapboxNavigation: MapboxNavigation
        @Synchronized get() = navigation ?: createNavigation().also { navigation = it }

    private fun createNavigation(): MapboxNavigation {
        val routingTilesOptions = RoutingTilesOptions.Builder()
            .tileStore(tileStore)
            .apply { pinnedTilesVersion?.let { tilesVersion(it) } }
            .build()

        val navOptions = NavigationOptions.Builder(mReactContext)
            .routingTilesOptions(routingTilesOptions)
            .build()

        Logger.i(LOG_TAG, "Creating navigation, routing tiles version: ${pinnedTilesVersion ?: "latest"}")
        return MapboxNavigationProvider.create(navOptions)
    }

    @Synchronized
    private fun pinTilesVersion(version: String) {
        if (version == pinnedTilesVersion) {
            return
        }
        Logger.i(LOG_TAG, "Pinning routing tiles version $version (was ${pinnedTilesVersion ?: "latest"})")
        prefs.edit().putString(KEY_TILES_VERSION, version).apply()
        if (navigation != null) {
            MapboxNavigationProvider.destroy()
            navigation = null
        }
    }

    // In-flight downloads by region id: the version being downloaded and its last progress.
    private class RoutingTilesDownload(val version: String) {
        var progress: TileRegionLoadProgress? = null
    }
    private val downloads = ConcurrentHashMap<String, RoutingTilesDownload>()
    private val failures = ConcurrentHashMap<String, String>()

    @OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
    private fun getLatestTilesVersion(callback: (Result<String>) -> Unit) {
        mapboxNavigation.tilesetVersionManager.getAvailableVersions(object : TilesetVersionManager.TilesetVersionsCallback {
            override fun onVersionsResult(result: Expected<Throwable, List<TilesetVersion>>) {
                val versions = result.value
                if (versions == null) {
                    callback(Result.failure(result.error ?: IllegalStateException("No routing tiles versions")))
                    return
                }
                val latest = versions.firstOrNull { it.isLatest && !it.isBlocked }
                    ?: versions.filter { !it.isBlocked }.maxByOrNull { it.releaseDate?.time ?: 0L }
                if (latest == null) {
                    callback(Result.failure(IllegalStateException("No usable routing tiles version")))
                } else {
                    callback(Result.success(latest.versionName))
                }
            }
        })
    }

    private fun tileRegionId(regionId: String, version: String) = "$regionId@$version"

    private fun removeOtherRoutingTiles(regionId: String, keep: String) {
        tileStore.getAllTileRegions { expected ->
            expected.value
                ?.filter { it.id.startsWith("$regionId@") && it.id != keep }
                ?.forEach {
                    Logger.i(LOG_TAG, "Removing routing tiles ${it.id}")
                    tileStore.removeTileRegion(it.id)
                }
        }
    }

    /**
     * Downloads the latest routing tiles for bounds ([[lng, lat], [lng, lat]]) into the tile region
     * "<regionId>@<version>". Once complete, the router is pinned to that version and the routing
     * tiles of other versions are removed, so the old tiles stay usable offline during the download.
     * Resolves with the version.
     */
    @ReactMethod
    fun downloadRoutingTiles(regionId: String, bounds: ReadableArray, promise: Promise) {
        val geometry = try {
            boundsToPolygon(bounds)
        } catch (e: Throwable) {
            promise.reject("ERROR", e)
            return
        }
        if (downloads.containsKey(regionId)) {
            promise.reject("ERROR", "Routing tiles for $regionId are already downloading")
            return
        }
        getLatestTilesVersion { result ->
            val version = result.getOrElse {
                failures[regionId] = it.message ?: it.toString()
                promise.reject("ERROR", it)
                return@getLatestTilesVersion
            }
            val download = RoutingTilesDownload(version)
            if (downloads.putIfAbsent(regionId, download) != null) {
                promise.reject("ERROR", "Routing tiles for $regionId are already downloading")
                return@getLatestTilesVersion
            }
            failures.remove(regionId)
            val id = tileRegionId(regionId, version)
            Logger.i(LOG_TAG, "Downloading routing tiles $id")
            val loadOptions = TileRegionLoadOptions.Builder()
                .geometry(geometry)
                .descriptors(listOf(mapboxNavigation.tilesetDescriptorFactory.getSpecificVersion(version)))
                // RNMBXOfflineModule.getPacks crashes on tile regions without metadata
                .metadata(Value.valueOf(hashMapOf("name" to Value(id), "routingTilesVersion" to Value(version))))
                .acceptExpired(true)
                .networkRestriction(NetworkRestriction.NONE)
                .build()
            tileStore.loadTileRegion(id, loadOptions, { progress ->
                download.progress = progress
            }, { expected ->
                downloads.remove(regionId)
                val region = expected.value
                if (region != null) {
                    Logger.i(LOG_TAG, "Downloaded routing tiles $id: ${region.completedResourceCount} resources, ${region.completedResourceSize} bytes")
                    pinTilesVersion(version)
                    removeOtherRoutingTiles(regionId, keep = id)
                    promise.resolve(version)
                } else {
                    val message = expected.error?.let { "${it.type}: ${it.message}" } ?: "unknown error"
                    Logger.w(LOG_TAG, "Downloading routing tiles $id failed: $message")
                    failures[regionId] = message
                    promise.reject("ERROR", "Downloading routing tiles failed: $message")
                }
            })
        }
    }

    /**
     * Status of the routing tiles of regionId: state is "downloading", "complete", "incomplete",
     * "failed" or "none", version is the pinned version (null when unpinned).
     */
    @ReactMethod
    fun getRoutingTilesStatus(regionId: String, promise: Promise) {
        val version = pinnedTilesVersion
        val status = Arguments.createMap()
        status.putString("version", version)
        val download = downloads[regionId]
        if (download != null) {
            val progress = download.progress
            status.putString("state", "downloading")
            status.putString("downloadingVersion", download.version)
            putProgress(status, progress?.completedResourceCount ?: 0, progress?.requiredResourceCount ?: 0)
            promise.resolve(status)
            return
        }
        val failure = failures[regionId]
        if (failure != null) {
            status.putString("state", "failed")
            status.putString("error", failure)
            putProgress(status, 0, 0)
            promise.resolve(status)
            return
        }
        if (version == null) {
            status.putString("state", "none")
            putProgress(status, 0, 0)
            promise.resolve(status)
            return
        }
        tileStore.getTileRegion(tileRegionId(regionId, version)) { expected ->
            val region = expected.value
            when {
                region == null -> {
                    status.putString("state", "none")
                    putProgress(status, 0, 0)
                }
                region.completedResourceCount >= region.requiredResourceCount -> {
                    status.putString("state", "complete")
                    putProgress(status, region.completedResourceCount, region.requiredResourceCount)
                }
                else -> {
                    status.putString("state", "incomplete")
                    putProgress(status, region.completedResourceCount, region.requiredResourceCount)
                }
            }
            promise.resolve(status)
        }
    }

    /**
     * Pins the router to the routing tiles version of an existing tile region, e.g. a pack created by
     * an older app version. Needs network. Resolves with the version.
     */
    @ReactMethod
    @OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
    fun pinRoutingTilesVersionOfRegion(regionId: String, promise: Promise) {
        mapboxNavigation.tilesetVersionManager.getAvailableUpdate(regionId, object : TilesetVersionManager.TilesetUpdatesCallback {
            override fun onUpdatesResult(result: Expected<Throwable, TilesetUpdateAvailabilityResult>) {
                when (val value = result.value) {
                    is TilesetUpdateAvailabilityResult.Available -> {
                        pinTilesVersion(value.currentVersion)
                        promise.resolve(value.currentVersion)
                    }
                    is TilesetUpdateAvailabilityResult.NoUpdates -> getLatestTilesVersion { latest ->
                        latest.onSuccess {
                            pinTilesVersion(it)
                            promise.resolve(it)
                        }.onFailure {
                            promise.reject("ERROR", it)
                        }
                    }
                    else -> promise.reject("ERROR", result.error ?: IllegalStateException("No routing tiles version for $regionId"))
                }
            }
        })
    }

    private fun putProgress(status: WritableMap, completed: Long, required: Long) {
        status.putDouble("completedResourceCount", completed.toDouble())
        status.putDouble("requiredResourceCount", required.toDouble())
        status.putDouble("percentage", if (required > 0) completed * 100.0 / required else 0.0)
    }

    private fun boundsToPolygon(bounds: ReadableArray): Polygon {
        val corners = (0 until bounds.size()).map { i ->
            val corner = bounds.getArray(i) ?: throw IllegalArgumentException("Invalid bounds")
            corner.getDouble(0) to corner.getDouble(1)
        }
        require(corners.size == 2) { "Bounds must have two corners" }
        val west = corners.minOf { it.first }
        val east = corners.maxOf { it.first }
        val south = corners.minOf { it.second }
        val north = corners.maxOf { it.second }
        return Polygon.fromLngLats(listOf(listOf(
            Point.fromLngLat(west, south),
            Point.fromLngLat(east, south),
            Point.fromLngLat(east, north),
            Point.fromLngLat(west, north),
            Point.fromLngLat(west, south),
        )))
    }

    @ReactMethod
    @Throws(JSONException::class)
    fun geocoding(point: ReadableArray, language: String, promise: Promise)
    {
        val reverseGeocode = try {
            val longitude = point.getDouble(0)
            val latitude = point.getDouble(1)
            MapboxGeocoding.builder()
                .accessToken(RNMBXModule.getAccessToken(mReactContext))
                .query(Point.fromLngLat(longitude, latitude))
                .geocodingTypes(GeocodingCriteria.TYPE_POI_LANDMARK)
                .languages(language)
                .build()
        } catch (e: Throwable) {
            promise.reject("ERROR", e)
            return
        }

        reverseGeocode.enqueueCall(object : Callback<GeocodingResponse> {
            override fun onResponse(call: Call<GeocodingResponse>, response: Response<GeocodingResponse>) {
                if (response.isSuccessful) {
                    val features: MutableList<Feature> = mutableListOf()
                    for (carmenFeature in response.body()?.features() ?: emptyList()) {
                        val feature = Feature.fromGeometry(carmenFeature.geometry())
                        feature.addStringProperty("place_name", carmenFeature.placeName() ?: "")
                        feature.addStringProperty("text", carmenFeature.text() ?: "")
                        feature.addStringProperty("address", carmenFeature.address() ?: "")
                        // Add any other properties you want to include in the Feature
                        features.add(feature)
                    }
                    val featureCollection = FeatureCollection.fromFeatures(features)
                    val result = Arguments.createMap()
                    result.putString("geoJson", featureCollection.toJson())
                    promise.resolve(result)
                } else {
                    promise.reject("ERROR", "Geocoding request failed: HTTP ${response.code()} ${response.message()}")
                }
            }

            override fun onFailure(call: Call<GeocodingResponse>, throwable: Throwable) {
                // Handle the failure
                promise.reject("ERROR", throwable)
            }
        })
    }


    @ReactMethod
    @Throws(JSONException::class)
    fun calculateRoute(readableArray: ReadableArray, promise: Promise)
    {
        val waypoints = ArrayList<Point>()

        for (i in 0 until readableArray.size()) {
            val coordinateArray: ReadableArray? = readableArray.getArray(i)
            coordinateArray?.let {
                val longitude = it.getDouble(0)
                val latitude = it.getDouble(1)
                val waypoint: Point = Point.fromLngLat(longitude, latitude)
                waypoints.add(waypoint)
            }
        }
        try {
            val routeOptions = RouteOptions.builder()
                .applyDefaultNavigationOptions()
                .coordinatesList(waypoints)
                .profile(DirectionsCriteria.PROFILE_WALKING)
                .build()
            mapboxNavigation.requestRoutes(
                routeOptions,
                object : NavigationRouterCallback {
                    override fun onCanceled(routeOptions: RouteOptions, @RouterOrigin routerOrigin: String) {
                        promise.reject("ERROR", "Route request canceled")
                    }

                    override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                        promise.reject("ERROR", "Route request failed: " + reasons.joinToString("; ") { it.message })
                    }

                    override fun onRoutesReady(
                        routes: List<NavigationRoute>,
                        @RouterOrigin routerOrigin: String
                    ) {
                        val geometry = routes.firstOrNull()?.directionsRoute?.geometry() // 获取第一条路径
                        if (geometry != null) {
                            val lineString = LineString.fromPolyline(geometry, 6)

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
        private const val PREFS_NAME = "RNMBXNavigation"
        private const val KEY_TILES_VERSION = "routingTilesVersion"
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
