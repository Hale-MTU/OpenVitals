# Translations

OpenVitals uses Android XML resources for app UI translations. Codeberg Translate
is the preferred place for community translation work.

Translate the Android app here:

[translate.codeberg.org/projects/openvitals/android-app](https://translate.codeberg.org/projects/openvitals/android-app/)

## Codeberg Translate Setup

Create one Weblate component for the Android app UI:

- Project: `OpenVitals`
- Component: `Android app`
- Repository: the GitHub `Hale-MTU/OpenVitals` repository
- VCS mode: `GitHub pull request`
- File format: `Android String Resource`
- File mask: `app/src/main/res/values-*/strings.xml`
- Monolingual base language file: `app/src/main/res/values/strings.xml`
- Source language: English
- Edit base file: disabled
- License: `AGPL-3.0-or-later`

Add the GitHub repository webhook target:

```text
https://translate.codeberg.org/hooks/github
```

The first component intentionally excludes `debug`, `nightly`, Fastlane
metadata, release notes, and docs. Add separate components later if those need
community translation.

## Shipping Policy

Shipping app languages are English, Czech, Spanish, French, Galician, German,
Italian, Japanese, Estonian, Finnish, Polish, Portuguese, Russian, Ukrainian,
and Simplified Chinese. Every locale file is fully translated, so new keys go to
all of them.

New languages can be collected in Codeberg Translate before they are ready to
ship. A new `values-<lang>/strings.xml` file can be merged once it is more than
70% translated, reviewed, and passes CI. Languages above that threshold
are generated into the in-app language picker automatically.

A merged language that is still below the threshold fails `verifyTranslations`
until it catches up — see the translation gate in
[development.md](development.md), which records the current state.

Android's `MissingTranslation` lint check is disabled because partial Weblate
languages are allowed. The repository validator replaces that check by enforcing
the greater-than-70% coverage threshold, placeholder safety, plural shape, and
`translatable="false"` handling.

## Translator Notes

- Preserve placeholders exactly, including numbered placeholders like `%1$s`
  and percent values like `%1$d%%`.
- Preserve escaped newlines such as `\n` when they are part of the source text.
- Keep product names and platform names unchanged unless the language normally
  localizes them: `OpenVitals`, `Health Connect`, `Google Play`, `Codeberg`,
  `Zulip`, package names, URLs, and measurement unit symbols.
- Prefer short labels. Many strings appear in compact cards, buttons, tabs, and
  dashboard widgets.
- Do not translate resources marked `translatable="false"` in the base file.

## Merging Weblate Pull Requests

Weblate keeps its own clone of `main` and pushes its commits to the
`weblate-translations` branch; the `weblate-pr.yml` workflow opens the pull
request. Merge that pull request with a **merge commit**. A squash or a
rebase-merge rewrites Weblate's commits, so on its next update Weblate cannot
rebase its branch onto `main`, the component locks, and the alerts page shows
"could not merge the repository".

When that has happened, the translations Weblate holds are only in its clone,
so do not press Reset first. Recover them like this:

```bash
git remote add weblate https://translate.codeberg.org/git/openvitals/mobile-app/
git fetch weblate main
git checkout -b weblate-recovery origin/main
git merge --no-ff weblate/main
```

Resolve `values-*/strings.xml` by keeping both sides (both usually append at
the end of the file), run `python3 scripts/verify-translations.py`, commit,
open a pull request and merge it with a merge commit. Weblate's commits are then
ancestors of `main`, and Manage → Repository maintenance → Update on
translate.codeberg.org fast-forwards and unlocks the component. If it still
reports a conflict after that, Reset is now safe: everything it held is on
`main`.

## Local Verification

Run the translation validator before merging Weblate pull requests:

```bash
./gradlew verifyTranslations
```

The validator reports locale files at or below 70% coverage, stale extra keys,
translated `translatable="false"` resources, plural shape mismatches, and
placeholder mismatches. It is also part of `./gradlew verifyCi`.

Plural shape follows each language's CLDR categories, listed in
`CLDR_LANGUAGE_QUANTITIES` in `scripts/verify-translations.py`. Japanese and
Chinese have only `other`, so they may drop the `one` branch English carries.
A new language not yet in that table is held to every quantity the base file
has, so add its CLDR categories when Weblate adds the language.
