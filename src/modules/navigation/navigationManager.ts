import { NativeModules, Platform } from 'react-native';

const {  RNMBXNavigationModule } = NativeModules;

export type RoutingTilesStatus = {
  /** downloading, complete, incomplete, failed, or none when there are no routing tiles of the pinned version */
  state: 'downloading' | 'complete' | 'incomplete' | 'failed' | 'none';
  /** Routing tiles version the router is pinned to, null when unpinned */
  version: string | null;
  /** Version being downloaded, when state is downloading */
  downloadingVersion?: string;
  completedResourceCount: number;
  requiredResourceCount: number;
  percentage: number;
  error?: string;
};

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
   * @return {GeoJSON.FeatureCollection}
   */
  async geocoding(
    point: GeoJSON.Point,
    language: string,
  ): Promise<GeoJSON.FeatureCollection | null> {
    if (Platform.OS !== 'android') {
      console.warn('geocoding only support Android');
      return null;
    }
    const { coordinates } = point;
    await this._initialize();
    const { geoJson: jsonPayload }: { geoJson: string } =
      await RNMBXNavigationModule.geocoding(coordinates, language);

    const geoJson: GeoJSON.FeatureCollection = JSON.parse(jsonPayload);
    return geoJson;
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

  /**
   * Downloads routing tiles for offline routing within bounds: the given version, or the latest one.
   * Tiles of that version already on the device are reused. Once complete, the router is pinned to
   * the downloaded version and routing tiles of other versions are removed, so the old tiles stay
   * usable offline until then.
   *
   * @example
   *
   * const version = await Mapbox.navigationManager.downloadRoutingTiles('routing-tiles', [[114.5, 22.6], [113.8, 22.1]])
   *
   * @param  {string} regionId  Tile region id prefix; regions are named `${regionId}@${version}`.
   * @param  {GeoJSON.Position[]} bounds  Two opposite corners as [longitude, latitude].
   * @param  {string} version  Routing tiles version to download, the latest one when omitted.
   * @return {string} Downloaded routing tiles version
   */
  async downloadRoutingTiles(
    regionId: string,
    bounds: [GeoJSON.Position, GeoJSON.Position],
    version?: string,
  ): Promise<string | null> {
    if (Platform.OS !== 'android') {
      console.warn('downloadRoutingTiles only support Android');
      return null;
    }
    return RNMBXNavigationModule.downloadRoutingTiles(
      regionId,
      bounds,
      version ?? null,
    );
  }

  /**
   * Status of the routing tiles downloaded by downloadRoutingTiles for regionId.
   *
   * @param  {string} regionId  Tile region id prefix passed to downloadRoutingTiles.
   * @return {RoutingTilesStatus}
   */
  async getRoutingTilesStatus(
    regionId: string,
  ): Promise<RoutingTilesStatus | null> {
    if (Platform.OS !== 'android') {
      return null;
    }
    return RNMBXNavigationModule.getRoutingTilesStatus(regionId);
  }

  /**
   * The routing tiles version of an existing tile region, e.g. an offline pack created by an older
   * app version. Passing it to downloadRoutingTiles moves those routing tiles to the routing tiles
   * region without downloading them again. Needs network.
   *
   * @param  {string} regionId  Tile region id.
   * @return {string} Routing tiles version
   */
  async getRoutingTilesVersionOfRegion(
    regionId: string,
  ): Promise<string | null> {
    if (Platform.OS !== 'android') {
      return null;
    }
    return RNMBXNavigationModule.getRoutingTilesVersionOfRegion(regionId);
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

