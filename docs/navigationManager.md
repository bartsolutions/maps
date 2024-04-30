<!-- This file was autogenerated from navigationManager.ts do not modify -->

```tsx
import { navigationManager } from '@rnmapbox/maps';

navigationManager
```
NavigationManager implements a asynchronous calculateRoute method to find best route between
Origin an Destination Point. Will use tile store when offline.



## methods
### geocoding(point, language)

Calcuate geocding

#### arguments
| Name | Type | Required | Description  |
| ---- | :--: | :------: | :----------: |
| `point` | `GeoJSON.Point` | `Yes` | GPS location. |
| `language` | `string` | `Yes` | Language. |



```javascript
const point = GeoJSON.Point
const language = string
await Mapbox.navigationManager.geocoding(point, language)
```


### calculateRoute(waypoints)

Calcuate a route using the available NavigationRouter implementation. (Online or Offline)

#### arguments
| Name | Type | Required | Description  |
| ---- | :--: | :------: | :----------: |
| `waypoints` | `Array` | `Yes` | GPS location. |



```javascript
const waypoints = GeoJSON.Point[]
await Mapbox.navigationManager.calculateRoute(waypoints)
```


