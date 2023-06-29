<!-- This file was autogenerated from navigationManager.ts do not modify -->

```tsx
import { navigationManager } from '@rnmapbox/maps';

navigationManager
```
NavigationManager implements a asynchronous calculateRoute method to find best route between
Origin an Destination Point. Will use tile store when offline.



## methods
### calculateRoute(origin[, destination])

Calcuate a route using the available NavigationRouter implementation. (Online or Offline)

#### arguments
| Name | Type | Required | Description  |
| ---- | :--: | :------: | :----------: |
| `origin` | `n/a` | `Yes` | origin options GPS location. |
| `destination` | `n/a` | `No` | destination GPS location. |



```javascript
const origin = [22.3528619, 114.1869509]
const destination = [22.3522368, 114.1893649]
await Mapbox.navigationManager.calculateRoute(origin, destination)
```


