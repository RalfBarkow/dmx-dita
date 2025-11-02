# WORKLOG
## 2025-10-31 — Wire `dmx-dita` into dmx-platform (dev webclient)

### Context
We’re adding the **dmx-dita** plugin to `modules-external/` and enabling it in the webclient during development.

### Changes
- Added `modules-external/dmx-dita` (from fork `github.com/RalfBarkow/dmx-dita`, upstream `git.dmx.systems/dmx-plugins/dmx-dita`).
- Enabled the plugin in the webclient’s development list.

### Files touched
- `~/workspace/dmx-platform/modules/dmx-webclient/src/main/js/plugin-manager.js`

### Code (development section)
```js
// while development add your plugins here
initPlugin(require('modules-external/dmx-zettelkasten/src/main/js/plugin.js').default)
initPlugin(require('modules-external/dmx-fedwiki/src/main/js/plugin.js').default)
initPlugin(require('modules-external/dmx-dita/src/main/js/plugin.js').default)
