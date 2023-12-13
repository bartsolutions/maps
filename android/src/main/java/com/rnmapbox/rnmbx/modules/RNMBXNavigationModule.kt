package com.rnmapbox.rnmbx.modules

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.module.annotations.ReactModule
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.api.geocoding.v5.GeocodingCriteria
import com.mapbox.api.geocoding.v5.MapboxGeocoding
import com.mapbox.api.geocoding.v5.models.GeocodingResponse
import com.mapbox.bindgen.Value
import com.mapbox.common.TileDataDomain
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
import com.rnmapbox.rnmbx.modules.RNMBXLocationModule
import com.rnmapbox.rnmbx.modules.RNMBXModule
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

    @ReactMethod
    @Throws(JSONException::class)
    fun geocoding(point: ReadableArray, language: String, promise: Promise)
    {
        val longitude = point.getDouble(0)
        val latitude = point.getDouble(1)
        var reverseGeocode = MapboxGeocoding.builder()
            .accessToken(RNMBXModule.getAccessToken(mReactContext))
            .query(Point.fromLngLat(longitude, latitude))
            .geocodingTypes(GeocodingCriteria.TYPE_POI_LANDMARK)
            .languages(language)
            .build()

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
