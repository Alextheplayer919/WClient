# Neon branding and APK icon

## Identity

- Display name: **Neon**.
- Android application ID and namespace: `com.retrivedmods.neon`.
- App sources/tests: `app/src/{main,test}/java/com/retrivedmods/neon/`.
- Internal relay package: `com.retrivedmods.neon.relay`.
- Internal trace package: `com.retrivedmods.neon.trace`.
- Public config/report folders now use `Neon` rather than `WClient`.

Package declarations, imports, fully-qualified references, relay intent actions,
Gradle configuration and ProGuard rules follow the new namespaces. Cloudburst,
Netty and other third-party namespaces remain unchanged. Existing external
website/source links retain their actual URLs; no new Neon domains are assumed.

Changing the application ID makes this a separate Android app. It will not update
an installed WClient in place or automatically inherit its private settings,
accounts, profiles or overlay/storage permissions. Export profiles from WClient
before switching and import them into Neon if needed. The existing signing setup
is unchanged; the name change does not change the signing identity.

## Where to put your image

Replace this single file with your image:

**`app/src/main/res/drawable/neon_launcher.png`**

Use an actual PNG (not a JPG renamed to PNG), preferably a square 1024 × 1024
image. Transparent padding is recommended: Android masks adaptive icons into
circles or other shapes, so keep the important artwork centered. This file is
wired to both adaptive launcher icons and the in-app/overlay logo references.
The current file is the supplied rainbow NEON artwork, preserved as the original PNG.

The adaptive background is configured separately in
`app/src/main/res/drawable/ic_launcher_background.xml`. The normal/round icon
wrappers are `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and
`ic_launcher_round.xml`. Neon requires Android API 28+, so these adaptive icon
resources are used on supported devices. Legacy density-specific mipmap images
remain in the tree but are not the foreground used by those adaptive wrappers.

Rebuild the APK after replacing the image. If a launcher caches the old icon,
remove/re-add its shortcut or restart the launcher.
