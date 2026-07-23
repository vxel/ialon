Steampunk UI chrome — provenance and nine-patch metrics
=======================================================

Most of these PNGs started life as baked artefacts, produced by

    local/tools/build-ui-art.sh

from generated source artwork supplied by the project author, kept in the git-ignored
local/resources/steam-punk/. Because the sources are not committed, the script cannot be re-run
from a fresh clone : it is the provenance record and the way to re-bake if the sources are
restored. The baked PNGs here are the real artefacts.

*** DO NOT RE-RUN build-ui-art.sh ***

Several of the PNGs in this folder have been HAND-EDITED since that script last ran, and some were
never produced by it at all — frame-popup.png (twice), button.png, button-pressed.png,
ornament-gear.png and ../cursor.png were reworked by hand, and plate-gold.png (plus its
frame-popup.xcf working file) was authored directly. Re-running build-ui-art.sh would silently
overwrite all of it. The files in this folder are the source of truth ; the script is stale
provenance for them.

New chrome therefore gets its OWN one-file script, so it cannot clobber anything :

    local/tools/build-title-plate.sh    ->  title-plate.png

Consumed by org.delaunois.ialon.ui.IalonTheme. Also baked into ../ (originally by build-ui-art.sh) :
range.png, range-filled.png and cursor.png (the slider rail, its filled part and its thumb — same
names as the textures they replaced, so no code change).

Nine-patch metrics
------------------

Lemur's TbtQuadBackgroundComponent keeps the four corners at a fixed size and STRETCHES the edges
and the centre (there is no tile mode). Two consequences drove how these were authored :

  * the stretched zones carry no ornament, only a gradient perpendicular to the stretch direction ;
    the rivets sit inside the fixed corner zones, and the frame's ornaments are pinned separately.
  * the mesh is built at imageWidth/Height * imageScale, so a patch WIDER OR TALLER than the widget
    it backs makes the folds cross over and the background renders inverted. Hence the small sizes,
    and the non-square button patch.

    file                 size     x1  y1  x2  y2   backs
    frame-popup.png      256x256  48  48  208 208  full-screen popups (min widget side ~ 256 * scale)
    plate-gold.png       128x96   32  32  96  64   dialog boxes, and the SELECTED grid card
    plate.png            128x96   32  32  96  64   the grid card of the world currently loaded
    plate-silver.png     128x96   32  32  96  64   every other grid card
    button.png           64x48    16  16  48  32   text buttons, raised
    button-pressed.png   64x48    16  16  48  32   text buttons, pressed
    title-plate.png      156x68   24  24  132 44   popup titles (the "pancarte" signboard)

The plate and button patches are taller than their content strictly needs, so that their corner zones
stay square enough to hold a rivet : a rivet larger than min(x1, y1), or inset past it, spills into the
stretched edge zone and is smeared into a teardrop. button-pressed.png must keep button.png's exact
geometry — pressing a button swaps only the texture on the mesh button.png built.

The three plates are the same engraving in three metals, and a grid card's state is which one it wears
(IalonTheme.Frame.PLATE_GOLD / PLATE_BRONZE / PLATE_SILVER — no colour multiply on top). Their geometry
must stay identical for the same reason button-pressed.png must match button.png : the cards are laid
out from one plate's chrome, so a differently sliced sibling would resize a card the moment it is
selected and push the grid's columns under the popup's button column.

title-plate.png is the patch with the least headroom : IalonTheme.TITLE_HEIGHT_VH displays it at
10 vh, i.e. 72 px at the 720p reference, and it is baked at 68 — deliberately just UNDER its widget,
since being shorter only stretches the centre band a little further while being taller would render
it inverted. 156 then holds the source artwork's 805x349 aspect ratio. Its 24x24 corner maps back to
source x ~ 124, y ~ 123, which clears both the ~26 px gold border and the rivet at x 50..76,
y 45..68 — so the whole ornamented border sits in the fixed zones and only the plain slate field is
stretched.

ornament-gear.png and rose.png (both 128x128) are not nine-patches : they are the two corner
ornaments (IalonTheme.Ornament.GEAR / COMPASS_ROSE), single icons pinned at the popup frame's
bottom-right corner, at one and a half times the frame's corner zone (~144 px at 720p). They are
baked at 128 rather than 64 because of that size — 128 is about the cog's native size in the source
artwork, so it is the most detail available without inventing pixels. The gear is the default ; the
compass rose is worn by the minimap popup, where a compass belongs to the instrument.

imageScale is not baked in — IalonTheme derives it from the screen height against a 720p reference,
so the chrome keeps the same thickness relative to the screen at any resolution.
