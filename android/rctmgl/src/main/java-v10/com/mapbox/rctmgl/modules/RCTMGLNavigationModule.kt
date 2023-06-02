package com.mapbox.rctmgl.modules

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
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
import org.json.JSONException

class RCTMGLNavigationModule private constructor(private val mReactContext: ReactApplicationContext) :
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
                Value(RCTMGLModule.getAccessToken(mReactContext))
            )
            it.setOption(
                TileStoreOptions.MAPBOX_ACCESS_TOKEN,
                TileDataDomain.NAVIGATION,
                Value(RCTMGLModule.getAccessToken(mReactContext))
            )
        }
    }

     val mapboxNavigation: MapboxNavigation by lazy {
        val routingTilesOptions = RoutingTilesOptions.Builder()
            .tileStore(tileStore)
            .build()

        var navOptions = NavigationOptions.Builder(mReactContext)
            .accessToken(RCTMGLModule.getAccessToken(mReactContext))
            .routingTilesOptions(routingTilesOptions)
            .build()

        MapboxNavigation(navOptions)
    }

    @ReactMethod
    @Throws(JSONException::class)
    fun calculateRoute(origin: ReadableArray, destination: ReadableArray, promise: Promise)
    {
        val originLatitude = origin.getDouble(0)
        val originLongitude = origin.getDouble(1)
        val destinationLatitude = destination.getDouble(0)
        val destinationLongitude = destination.getDouble(1)

        val originPoint = Point.fromLngLat(originLongitude, originLatitude)
        val destinationPoint = Point.fromLngLat(destinationLongitude, destinationLatitude)

        val routeOptions = RouteOptions.builder()
            .applyDefaultNavigationOptions()
            .coordinatesList(listOf(originPoint, destinationPoint))
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
        const val REACT_CLASS = "RCTMGLNavigationModule"
        const val LOG_TAG = REACT_CLASS
        private var instance: RCTMGLNavigationModule? = null

        @JvmStatic
        fun getInstance(reactContext: ReactApplicationContext): RCTMGLNavigationModule {
            if (instance == null) {
                instance = RCTMGLNavigationModule(reactContext)
            }
            return instance as RCTMGLNavigationModule
        }
    }
}
