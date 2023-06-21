import { NativeModules, Platform } from 'react-native';

const { MGLNavigationModule } = NativeModules;

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
   * Calcuate a route using the available NavigationRouter implementation. (Online or Offline)
   *
   * @example
   *
   * const origin = [22.3528619, 114.1869509]
   * const destination = [22.3522368, 114.1893649]
   * await Mapbox.navigationManager.calculateRoute(origin, destination)
   *
   * @param  {[number, number]} origin origin options GPS location.
   * @param  {[number, number]=} destination destination GPS location.
   * @return {GeoJSON.FeatureCollection}
   */
  async calculateRoute(
    origin: [number, number],
    destination: [number, number],
  ): Promise<GeoJSON.FeatureCollection | null> {
    if (Platform.OS !== 'android') {
      console.warn('calculateRoute only support Android');
      return null;
    }

    await this._initialize();
    const { geoJson: jsonPayload }: { geoJson: string } =
      await MGLNavigationModule.calculateRoute(origin, destination);
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
