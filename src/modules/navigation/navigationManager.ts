import { NativeModules, Platform } from 'react-native';

const {  RNMBXNavigationModule } = NativeModules;

/**
 * NavigationManager implements a asynchronous calculateRoute method to find best route between
 * Origin an Destination Point. Will use tile store when offline.
 */
class NavigationManager {
  private _hasInitialized: boolean;

  constructor() {
    this._hasInitialized = false;
  }

  /**
   * Calcuate geocding
   *
   * @example
   *
   * const point = GeoJSON.Point
   * const language = string
   * await Mapbox.navigationManager.geocoding(point, language)
   *
   * @param  {GeoJSON.Point} point  GPS location.
   * @param  {string} language Language.
   * @return {any}
   */
  async geocoding(
    point: GeoJSON.Point,
    language: string,
  ): Promise<any> {
    if (Platform.OS !== 'android') {
      console.warn('geocoding only support Android');
      return null;
    }
    const { coordinates } = point;
    await this._initialize();
    const payload =
      await RNMBXNavigationModule.geocoding(coordinates, language);
    console.log("payload", payload)
    return payload;
  }

  /**
   * Calcuate a route using the available NavigationRouter implementation. (Online or Offline)
   *
   * @example
   *
   * const waypoints = GeoJSON.Point[]
   * await Mapbox.navigationManager.calculateRoute(waypoints)
   *
   * @param  {GeoJSON.Point[]} waypoints  GPS location.
   * @return {GeoJSON.FeatureCollection}
   */
  async calculateRoute(
    waypoints: GeoJSON.Point[],
  ): Promise<GeoJSON.FeatureCollection | null> {
    if (Platform.OS !== 'android') {
      console.warn('calculateRoute only support Android');
      return null;
    }

    const points = waypoints.map((point) => point.coordinates);

    await this._initialize();
    const { geoJson: jsonPayload }: { geoJson: string } =
      await RNMBXNavigationModule.calculateRoute(points);
    const geoJson: GeoJSON.FeatureCollection = JSON.parse(jsonPayload);
    return geoJson;
  }

  async _initialize(): Promise<boolean> {
    if (this._hasInitialized) {
      return true;
    }

    this._hasInitialized = true;
    return true;
  }
}

const navigationManager = new NavigationManager();
export default navigationManager;

