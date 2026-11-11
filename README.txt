Build: gradle build (JDK 21) -> build/libs/*.jar -> .minecraft/mods (needs Fabric API, MC 1.21.4)
Test in-game without a payment: /payfx 5m  /payfx 20m  /payfx 60m  /payfx 150m
Payment regex: edit PayFx.P if DonutSMP's message wording differs.
Tiers: <1M none | 1M 1 effect | 10M 2 | 50M 3 + beams/flash/dim/orbiting faces | 100M all 8 effects + face rain, rainbow, shake, 2nd big-bang flash
Payer's skin face is read from the tab list (falls back to default skin if they are not listed). Payments queue up.
