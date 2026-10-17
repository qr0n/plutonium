# Plutonium FPS Optimization Guide

Comprehensive reference compiled from Lunar Client, Badlion Client, and open-source performance mods (Patcher, OptiFine, Sodium, Lithium, Phosphor, FoamFix, BetterFps, EntityCulling, TexFix, VanillaFix). All techniques are framed for MCP 1.8.9 direct source patching.

---

## Priority Implementation Order

| # | Technique | Impact | Effort | Vanilla Target |
|---|-----------|--------|--------|----------------|
| 1 | Smart Animations | Very High | Medium | `TextureAtlasSprite.updateAnimation()` |
| 2 | Entity Culling (frustum + distance) | Very High | Medium | `RenderGlobal.renderEntities()` |
| 3 | HUD Caching (FBO) | High | Medium | `GuiIngame.renderGameOverlay()` |
| 4 | Particle Culling + Limits | High | Simple | `EffectRenderer.renderParticles()` |
| 5 | Non-Animated Texture RAM Cleanup | Very High (mem) | Simple | `TextureMap.loadTextureAtlas()` |
| 6 | Tile Entity Distance Culling | Medium-High | Simple | `TileEntityRendererDispatcher.renderTileEntity()` |
| 7 | Frustum Culling Fix | Medium-High | Simple | `Frustum.isBoxInFrustum()` |
| 8 | Chunk Rebuild Throttling | High (stutter) | Simple | `ChunkRenderDispatcher` |
| 9 | Font Renderer Batching | High | Medium | `FontRenderer.renderString()` |
| 10 | Fast Math (sin/cos LUT) | Low-Medium | Simple | `MathHelper.sin()` / `cos()` |
| 11 | Entity Back-Face Culling | Medium | Simple | Entity model renderers |
| 12 | Static Particle Color | High (particles) | Simple | `EntityFX.renderParticle()` |
| 13 | Fast Render (skip anaglyph) | Medium | Simple | `EntityRenderer.renderWorldPass()` |
| 14 | Hopper Tick Optimization | Very High (redstone) | Medium | `TileEntityHopper.update()` |
| 15 | DataWatcher Array Optimization | Medium | Simple | `DataWatcher` |
| 16 | BlockState Memory Compaction | High (mem) | Medium | `BlockState.StateImplementation` |
| 17 | Baked Model Deduplication | High (mem) | Medium | `ModelManager` / `IBakedModel` |
| 18 | Lighting Engine Rewrite | Very High | High | `World.checkLightFor()` |
| 19 | Chunk Render Batching | Very High | High | `RenderGlobal.renderBlockLayer()` |
| 20 | Compact Vertex Format | High | High | `BlockModelRenderer.renderModel()` |

---

## 1. Smart Animations (Highest Impact Single Optimization)

**Source:** OptiFine, Lunar, Badlion

**Problem:** Vanilla `TextureMap.updateAnimations()` calls `updateAnimation()` on EVERY animated sprite (water, lava, fire, portal, prismarine) every tick, even when none of those textures are visible on screen.

**Solution:** Track which `TextureAtlasSprite` instances were actually sampled during the last frame's rendering. Only call `updateAnimation()` on sprites that were drawn.

**Implementation:**
```
1. Add a boolean field to TextureAtlasSprite: `boolean usedThisFrame = false`
2. In BlockModelRenderer.renderModel() and any entity/particle render path that
   references a sprite, set sprite.usedThisFrame = true
3. In TextureMap.updateAnimations(), skip sprites where usedThisFrame == false
4. Reset all usedThisFrame flags at the start of each frame
```

**Files to patch:**
- `net.minecraft.client.renderer.texture.TextureAtlasSprite` -- add field, guard `updateAnimation()`
- `net.minecraft.client.renderer.texture.TextureMap` -- reset flags in `updateAnimations()`
- `net.minecraft.client.renderer.BlockModelRenderer` -- mark sprites as used when rendering quads

---

## 2. Entity Culling

**Source:** Patcher, EntityCulling (tr7zw), Lunar Turbo Entities

### 2a. Frustum Culling for Entities
Vanilla only frustum-culls chunks, not individual entities. Add `Frustum.isBoundingBoxInFrustum(entity.getEntityBoundingBox())` check before each `renderManager.renderEntitySimple()` call in `RenderGlobal.renderEntities()`.

### 2b. Distance-Based Culling
Skip rendering entities beyond a configurable distance (e.g., 64 blocks for mobs, 128 for players). Tile entities like chests/signs/banners beyond 48 blocks.

### 2c. Occlusion Culling (Advanced)
On a background thread, trace rays from camera to each entity's AABB. If all rays hit opaque blocks, mark entity as occluded and skip rendering. Use one-frame delay for results.

**Implementation:**
```
In RenderGlobal.renderEntities():
  for each entity:
    // Frustum check
    if (!camera.isBoundingBoxInFrustum(entity.getEntityBoundingBox())) continue;
    // Distance check
    double distSq = entity.getDistanceSqToEntity(renderViewEntity);
    if (distSq > maxEntityRenderDistSq) continue;
    // Occlusion check (async results from previous frame)
    if (occlusionCache.isOccluded(entity)) continue;
    // Render
    renderManager.renderEntitySimple(entity, partialTicks);
```

**Files to patch:**
- `net.minecraft.client.renderer.RenderGlobal` -- `renderEntities()`
- `net.minecraft.client.renderer.culling.Frustum` -- fix boundary comparisons in `isBoxInFrustum()`
- New: `plutonium.optimization.EntityCullingManager` -- async occlusion thread

---

## 3. HUD Caching

**Source:** Patcher, Lunar Client (caches at 20 FPS)

**Problem:** The HUD (health, hunger, hotbar, scoreboard, bossbar, XP bar) is redrawn every frame even though most elements change rarely.

**Solution:** Render HUD to an FBO. Only re-render when data changes.

**Implementation:**
```
1. Create FBO at screen resolution
2. Track HUD state: health, food, armor, xp, hotbarSlot, scoreboardLines
3. In GuiIngame.renderGameOverlay():
   if (hudStateChanged()):
     bindFBO()
     renderAllHudElements()  // original code
     unbindFBO()
   blitFBOToScreen()
```

**Files to patch:**
- `net.minecraft.client.gui.GuiIngame` -- `renderGameOverlay()`
- New: `plutonium.optimization.HUDCache` -- FBO management + dirty tracking

---

## 4. Particle Culling + Limits

**Source:** Patcher, Badlion Betterframes

**Problem:** Particles are rendered without visibility checks. In PvP, crit/enchant hit particles tank FPS.

**Solution:**
```
In EffectRenderer.renderParticles():
  1. Frustum-cull each particle before calling renderParticle()
  2. Distance-cull: skip particles beyond 32 blocks
  3. Cap total rendered particles (configurable, e.g., 500 vs vanilla's 4000)
```

**Bonus -- Static Particle Color:**
Each particle calls `getBrightnessForRender()` every frame (world lighting lookup). Cache brightness at spawn time, update infrequently (every 4 ticks) instead of every frame.

**Files to patch:**
- `net.minecraft.client.particle.EffectRenderer` -- `renderParticles()`
- `net.minecraft.client.particle.EntityFX` -- cache `getBrightnessForRender()` result

---

## 5. Non-Animated Texture RAM Cleanup (TexFix)

**Problem:** After stitching textures into the atlas and uploading to GPU, vanilla keeps ALL sprite pixel data in RAM. For non-animated textures, this RAM copy is never needed again. With resource packs, this wastes 300MB-1.5GB+.

**Solution:** After atlas upload, null out `framesTextureData` for sprites where `animationMetadata == null`.

**Implementation:**
```
In TextureMap, after loadTextureAtlas() completes:
  for (TextureAtlasSprite sprite : mapRegisteredSprites.values()):
    if (sprite.animationMetadata == null):
      sprite.framesTextureData.clear()  // free RAM, GPU has the data
```

**Files to patch:**
- `net.minecraft.client.renderer.texture.TextureMap` -- post-load cleanup

---

## 6. Tile Entity Distance Culling

**Source:** Patcher, Badlion

**Solution:** In `TileEntityRendererDispatcher.renderTileEntity()`:
```
double distSq = tileEntity.getDistanceSq(playerX, playerY, playerZ);
if (distSq > 4096.0) return;  // 64 blocks
// For signs specifically, maybe 32 blocks (1024.0)
```

**Files to patch:**
- `net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher` -- `renderTileEntity()`

---

## 7. Frustum Culling Fix

**Source:** Patcher

**Problem:** Vanilla `Frustum.isBoxInFrustum()` has boundary comparison bugs causing visible chunks to be culled (invisible chunk artifacts).

**Solution:** Fix the plane tests -- use `>=` instead of `>` for boundary cases. Ensure the frustum plane extraction in `ClippingHelperImpl` normalizes planes correctly.

**Files to patch:**
- `net.minecraft.client.renderer.culling.Frustum` -- `isBoxInFrustum()`
- `net.minecraft.client.renderer.culling.ClippingHelperImpl` -- plane extraction

---

## 8. Chunk Rebuild Throttling

**Source:** Patcher, Badlion

**Problem:** Vanilla tries to rebuild as many dirty chunks as possible per frame, causing frame-time spikes.

**Solution:** Cap chunk rebuilds per frame (e.g., max 5). Use a priority queue sorted by distance to camera.

**Implementation:**
```
In RenderGlobal.setupTerrain() or ChunkRenderDispatcher:
  int rebuildsThisFrame = 0;
  for chunk in dirtyChunks (sorted by distance):
    if (rebuildsThisFrame >= maxRebuildsPerFrame) break;
    scheduleRebuild(chunk);
    rebuildsThisFrame++;
```

**Files to patch:**
- `net.minecraft.client.renderer.RenderGlobal` -- `setupTerrain()`
- `net.minecraft.client.renderer.chunk.ChunkRenderDispatcher`

---

## 9. Font Renderer Batching

**Source:** Patcher, ImmediatelyFast

**Problem:** Vanilla font renderer issues individual GL calls per character, switches textures per char. Each `drawString()` call does GL state changes. Text is rendered hundreds of times per frame (chat, scoreboard, tab list, nametags, tooltips).

**Solution:** Batch characters into a single VBO draw per string. Cache glyph metrics.

**Files to patch:**
- `net.minecraft.client.gui.FontRenderer` -- `renderString()`, `renderStringAtPos()`

---

## 10. Fast Math

**Source:** BetterFps, OptiFine

**Solution:** Replace `MathHelper.sin()` with a lookup table:
```java
private static final float[] SIN_TABLE = new float[65536];
static {
    for (int i = 0; i < 65536; i++) {
        SIN_TABLE[i] = (float) Math.sin(i * Math.PI * 2.0 / 65536.0);
    }
}
public static float sin(float value) {
    return SIN_TABLE[(int)(value * 10430.378f) & 0xFFFF];
}
public static float cos(float value) {
    return SIN_TABLE[(int)(value * 10430.378f + 16384.0f) & 0xFFFF];
}
```

**Files to patch:**
- `net.minecraft.util.MathHelper` -- `sin()`, `cos()`

---

## 11. Entity Back-Face Culling

**Source:** Patcher

**Solution:** Enable `GL11.glEnable(GL11.GL_CULL_FACE)` during entity model rendering to skip back-facing polygons. Roughly halves fragment work for entities.

**Files to patch:**
- `net.minecraft.client.renderer.entity.RenderLivingBase` -- `doRender()` -- wrap with GL cull state

---

## 12. Fast Render (Skip Anaglyph)

**Source:** OptiFine

**Problem:** Vanilla has a code path for anaglyph (3D glasses) rendering that does redundant state changes even when disabled.

**Solution:** In `EntityRenderer.renderWorldPass()`, skip the anaglyph render pass entirely when not using anaglyph mode.

**Files to patch:**
- `net.minecraft.client.renderer.EntityRenderer` -- `renderWorldPass()`

---

## 13. Hopper Optimization

**Source:** Lithium, BetterFps

**Problem:** Hoppers check for items and adjacent inventories EVERY tick. Each check does entity lookup + block state lookup + inventory access. Hopper grids cause massive lag.

**Solution:**
- Cache adjacent inventory references, invalidate on block change
- Only scan for item entities when items are actually nearby (event-based)
- Skip unchanged inventories using modification counters

**Files to patch:**
- `net.minecraft.tileentity.TileEntityHopper` -- `update()`, `captureDroppedItems()`

---

## 14. DataWatcher Array Optimization

**Source:** FoamFix

**Problem:** `DataWatcher` uses `Map<Integer, WatchableObject>` with boxing overhead.

**Solution:** Replace with a flat `WatchableObject[]` array indexed by watcher ID. O(1) lookup, no boxing.

**Files to patch:**
- `net.minecraft.entity.DataWatcher`

---

## 15. BlockState Memory Compaction

**Source:** FoamFix, VanillaFix

**Problem:** `BlockState.StateImplementation` uses `ImmutableMap<IProperty, Comparable>` for property storage. Each state holds a full map even for blocks with few properties.

**Solution:** Replace with a compact array-backed implementation. Properties stored as indices into a flat array.

**Files to patch:**
- `net.minecraft.block.state.BlockState` -- `StateImplementation`

---

## 16. Baked Model Deduplication

**Source:** FoamFix

**Problem:** Many baked models share identical quads but each holds its own copy. In vanilla, wastes ~100MB; in modded, much more.

**Solution:** After `ModelManager.onResourceManagerReload()`, walk all `IBakedModel` instances. Build `HashMap<BakedQuad, BakedQuad>` and replace duplicates with canonical instances.

**Files to patch:**
- `net.minecraft.client.resources.model.ModelManager` -- post-reload dedup pass

---

## 17. Lighting Engine Rewrite (Phosphor/Starlight)

**Source:** Phosphor, Starlight

**Problem:** Vanilla propagates light by calculating each position's light FROM all 6 neighbors (reads 7 blocks per update). This is 12-28x slower than necessary.

**Solution:** Propagate light TO neighbors. Take a light value at a position and push it outward. Use BFS queue instead of recursive flood-fill.

**Additional fixes:**
- Skip light recalc when replacing one opaque block with another opaque block (same opacity)
- Optimize skylight initialization: walk downward from top of column, set 15 until opaque
- Replace hash-based data structures in light queues with simple FIFO ring buffers

**Files to patch:**
- `net.minecraft.world.World` -- `checkLightFor()`
- `net.minecraft.world.chunk.Chunk` -- `relightBlock()`, `generateSkylightMap()`

---

## 18. Chunk Render Batching (Sodium Approach)

**Source:** Sodium, Lunar Turbo Engine

**Problem:** Vanilla renders each 16x16x16 chunk section with its own draw call. Thousands of draw calls per frame.

**Solution:** Group chunks into regions (8x4x8). Upload all geometry per region into one large VBO. Use `glMultiDrawArrays` to render entire region in one call. 90%+ draw call reduction.

**Files to patch:**
- `net.minecraft.client.renderer.RenderGlobal` -- `renderBlockLayer()`
- `net.minecraft.client.renderer.chunk.RenderChunk` -- buffer management
- New: `plutonium.optimization.RegionBuffer` -- manages shared VBOs per region

---

## 19. Compact Vertex Format

**Source:** Sodium

**Problem:** Vanilla `DefaultVertexFormats.BLOCK` is 28 bytes/vertex. Positions as floats, texture coords as floats.

**Solution:** Pack vertex data tighter:
- Position: 3x short (6 bytes, relative to chunk origin)
- Color: 1x int (4 bytes, already packed)
- TexCoord: 2x half-float (4 bytes instead of 8)
- Lightmap: 1x short (2 bytes)
- Total: ~16 bytes/vertex (43% reduction)

Requires a vertex shader to unpack.

**Files to patch:**
- `net.minecraft.client.renderer.vertex.DefaultVertexFormats`
- `net.minecraft.client.renderer.BlockModelRenderer` -- `renderModel()`
- `net.minecraft.client.renderer.WorldRenderer` (buffer builder)

---

## 20. Additional Game Logic Optimizations (Lithium)

### Entity Collision
- `World.getEntitiesWithinAABBExcludingEntity()` iterates ALL entities. Use spatial partitioning -- only check entities in chunks the AABB touches.
- Cache collision boxes per block state (they're immutable).

### ClassInheritanceMultiMap
- `Chunk` stores entities in `ClassInheritanceMultiMap`. `getByClass()` does O(n) instanceof scan.
- Replace with pre-sorted lists by entity class using array-backed maps. O(1) lookup.

### Explosion Optimization
- Explosions ray-cast in all directions. Each ray does multiple block lookups.
- Cache chunk access during ray casting. Use `long` packed positions instead of allocating `BlockPos`.

### AI Goal Selector
- Every tick, every mob iterates ALL AI goals and evaluates `shouldExecute()`.
- Cache goal execution states, only re-evaluate when relevant world state changes.

---

## 21. JVM Flags

For `launch.sh`, recommended G1GC tuning for 1.8.9 client (Java 8 Temurin):

```bash
-Xms2G -Xmx2G \
-XX:+UseG1GC \
-XX:MaxGCPauseMillis=50 \
-XX:G1NewSizePercent=40 \
-XX:G1MaxNewSizePercent=50 \
-XX:G1HeapRegionSize=16M \
-XX:G1ReservePercent=15 \
-XX:G1MixedGCCountTarget=4 \
-XX:InitiatingHeapOccupancyPercent=20 \
-XX:SurvivorRatio=32 \
-XX:MaxTenuringThreshold=1 \
-XX:+UnlockExperimentalVMOptions \
-XX:+AlwaysPreTouch \
-XX:+ParallelRefProcEnabled \
-XX:+DisableExplicitGC
```

**Rationale:**
- `G1NewSizePercent=40`: Minecraft's extreme short-lived allocation rate needs a large young gen
- `MaxTenuringThreshold=1`: Objects surviving one GC are likely permanent -- promote immediately
- `AlwaysPreTouch`: Pre-fault pages at startup to avoid mid-game page fault stutters
- `Xms=Xmx`: Prevents heap resizing pauses
- `MaxGCPauseMillis=50`: Target ~3 frames at 60 FPS max pause
- `DisableExplicitGC`: Prevent vanilla's `System.gc()` calls during world load

---

## 22. Lunar vs Badlion: Key Differences

| Aspect | Lunar | Badlion |
|--------|-------|---------|
| **Architecture** | JNI native code (C/C++) for hot paths | Pure Java optimizations |
| **Memory** | ~5 GB | ~2 GB |
| **Chunk rendering** | "Turbo Engine" -- native chunk meshing | OptiFine-based + Betterframes |
| **Entity rendering** | "Turbo Entities" -- instanced rendering, native | Betterframes distance settings |
| **GPU sync** | Likely native-level fencing | GPU Fencing toggle in Betterframes |
| **Relevance to Plutonium** | Cannot replicate JNI approach in pure MCP | **More applicable** -- Java-level patches |

**Conclusion:** Badlion's approach (Java-level mixin patches, OptiFine-like features) is the realistic model for Plutonium. Lunar's JNI/native approach requires a fundamentally different architecture.

---

## Sources

- Lunar Client: [Performance Boosts](https://www.lunarclient.com/news/overhauling-minecraft-performance-boosts-and-custom-features), [Turbo Entities Devlog](https://www.lunarclient.com/news/lunar-client-devlog-turbo-entities)
- Badlion: [FPS Guide](https://www.badlion.net/minecraft-blog/improve-your-minecraft-fps), [OptiFine & Betterframes](https://www.badlion.net/optifine-and-betterframes-boost-your-minecraft-fps)
- [Patcher (Sk1er)](https://github.com/Sk1erLLC/Patcher), [PolyPatcher (Polyfrost)](https://github.com/Polyfrost/PolyPatcher)
- [Sodium (CaffeineMC)](https://github.com/CaffeineMC/sodium) -- [DeepWiki](https://deepwiki.com/CaffeineMC/sodium)
- [Lithium (CaffeineMC)](https://github.com/CaffeineMC/lithium) -- [DeepWiki](https://deepwiki.com/CaffeineMC/lithium)
- [Starlight Technical Details](https://github.com/PaperMC/Starlight/blob/fabric/TECHNICAL_DETAILS.md)
- [FoamFix](https://github.com/asiekierka/FoamFix)
- [EntityCulling (tr7zw)](https://github.com/tr7zw/EntityCulling)
- [BetterFps](https://www.curseforge.com/minecraft/mc-mods/betterfps)
- [TexFix](https://www.curseforge.com/minecraft/mc-mods/texfix)
- [VanillaFix](https://github.com/DimensionalDevelopment/VanillaFix)
- [Aikar's G1GC Flags](https://aikar.co/2018/07/02/tuning-the-jvm-g1gc-garbage-collector-flags-for-minecraft/)
- [1.8 Rendering Primer](https://gist.github.com/williewillus/57d7093efa80163e96e0)
- [1.8.9 Performance Mods List](https://gist.github.com/NordicGamerFE/3394b115e34639376aee9c5e2d11a2ba)
