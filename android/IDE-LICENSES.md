> **Licenciamento de terceiros (vigente).** Este documento preserva a origem e reconstrução da IDE a partir da fase R4. O licenciamento do código original My-CT está em [LICENSE](../LICENSE), [NOTICE](../NOTICE) e [guia de licenças](../docs/LICENSING.md). A adoção de Apache-2.0 no projeto não substitui a LGPL do Sora ou as licenças de seus componentes.

# IDE — dependencies, licenses and rebuilding

My-CT R4 uses unmodified `io.github.Rosemoe.sora-editor:editor:0.23.6` and
`language-textmate:0.23.6` from Maven Central. Sora is LGPL-2.1-or-later;
embedded TM4E files retain their EPL-2.0/MIT headers. No native regex/NDK module
is included. Sora's source, including the embedded components and their original
headers, is attached as `sora-editor-0.23.6-source.tar.gz` to the R4 release:
https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r4-v0.6.5-rc1
Upstream source commit: `b1777d0d465b42515739ffe529b4ec1634ac2175`.

Original license texts/copyright notices and grammar provenance are included in
`app/src/main/assets/ide/`; the app exposes them in IDE → Detalhes → Licenças e
fontes. VSCode grammar versions are pinned in `SOURCES.txt`; their original
projects/licenses appear in the bundled cgmanifest and license files. The
Industrial TextMate theme is authored for My-CT.

New transitive libraries: Gson (Apache-2.0), JCodings and Joni (MIT), SnakeYAML
Engine (Apache-2.0), Eclipse JDT annotations (EPL-2.0). Original transitive license texts are included alongside the editor notices. Existing AndroidX/Kotlin dependencies
are reused. Full dependency graph: `:app:dependencies --configuration debugRuntimeClasspath`.

To replace/modify the LGPL components, obtain this repository at the release tag
and the attached Sora source. With JDK 21, Android SDK 36 and the existing Gradle
wrapper, build Sora's `editor` and `language-textmate` modules, then substitute
those project modules or AARs in `app/build.gradle.kts` (including their declared
transitive dependencies). Build My-CT with `android/gradlew -p android
:app:assembleDebug`; dependency download may be needed once, then `--offline`.
The complete My-CT source and build scripts are public and unminified. You may
reverse engineer the combined work for debugging modifications to LGPL parts.
There is no vendor-only loading/signature check inside My-CT. A modified APK may
be signed with your own certificate; Android requires the original certificate
for an in-place update, or a separate applicationId/uninstall to install a differently
signed build. The owner's private signing material is not part of the source.
