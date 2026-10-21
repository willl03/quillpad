<h1 align="center">Quillpad</h1>

<p align="center">
  <a href="https://github.com/willl03/quillpad/releases"><img src="https://img.shields.io/badge/release-1.5.14%2Bfork.1-6A40F9" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPLv3-5030C0" alt="License"></a>
</p>

<h2 align="center">This Fork</h2>

<p align="center">
  A personal fork of <a href="https://github.com/quillpad/quillpad">quillpad/quillpad</a>
  focused on using Quillpad as a direct Markdown notes app for a folder inside an
  Obsidian vault. Versions follow <code>upstream+fork.N</code>
  (current: <b>1.5.14+fork.1</b>).
</p>

- **The folder is the source of truth** — notes are read from and written directly to
  `.md`/`.txt` files in the folder you choose.
- **External changes are respected** — files moved, renamed or deleted by another app
  are followed or removed on the next sync; notes are never resurrected or duplicated.
- **Safe by default** — file edits win on conflicts, ambiguous files are left alone,
  unsaved local edits are preserved if their file disappears, and an empty/unavailable
  folder never triggers a mass deletion.
- **Recursive and Obsidian-friendly** — nested folders work; hidden folders, `.trash`
  and `sync-conflict` files are ignored.
- **Explicit recovery** — failed writes are retried, and Settings → Syncing →
  File Storage has a confirmed **Rescan folder** action.

<p align="center">
  <a href="https://github.com/willl03/quillpad/releases/latest">
    <img src="https://github.com/machiav3lli/oandbackupx/blob/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png?raw=true" alt="Download from GitHub" height="75">
  </a>
</p>

> **Note:** builds are signed with a personal key — install over any previous build of
> this fork (not over upstream's F-Droid/Play builds).

*Everything below this notice is the upstream readme.*

[![Gitter](https://badges.gitter.im/quillpad/community.svg)](https://gitter.im/quillpad/community?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)
[![Android Build Release](https://github.com/quillpad/quillpad/actions/workflows/android.yml/badge.svg?branch=master)](https://github.com/quillpad/quillpad/actions/workflows/android.yml)
# Quillpad
Quillpad is fully free and open-source. It will never show you ads, ask you for unnecessary permissions or upload your notes anywhere without you knowing.

Take beautiful markdown notes whenever you feel inspired. Place them in notebooks and tag them accordingly. Stay organized by making task lists, set reminders and keep everything in one place by attaching related files.

## Available at

<a href="https://f-droid.org/packages/io.github.quillpad">
<img alt="Get it on F-Droid" src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="80"></a>

<a href='https://play.google.com/store/apps/details?id=io.github.quillpad'>
<img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' height="80"/></a>

## Features
With Quillpad, you can:

- Take notes with Markdown support
- Make task lists
- Pin your favorite notes to the top
- Hide notes you do not want others to see
- Set reminders for events you do not want to miss
- Add voice recordings and other file attachments
- Group related notes in notebooks
- Add tags to notes
- Archive notes you want out of your way
- Search through notes
- Sync notes to the file system (store notes as markdown files in a local or cloud folder)
- Sync with Nextcloud (experimental, requires the Nextcloud Notes app installed on the Nextcloud server used for syncing)
- Backup your notes to a zip file which you can restore at a later time
- Toggle between Light and Dark mode
- Choose between multiple color schemes

### Support

If you like the work and would like to support me,

<a href="https://buymeacoffee.com/quillpad" target="_blank">
<img src="https://cdn.buymeacoffee.com/buttons/default-orange.png" alt="Buy Me A Coffee" height="41" width="174" />
</a>

### Matrix

Join the conversation at [Matrix](https://matrix.to/#/#quillpad_community:gitter.im)
or [Gitter](https://gitter.im/quillpad/community?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)

### Fork Info

Quillpad is a fork of an original app called [Quillnote](https://github.com/msoultanidis/quillnote). The development
stopped on the original app and PR backlogs were not cleared up. The community showed much interest in the app for
continued development and so this fork was
created. https://github.com/msoultanidis/quillnote/issues/177 https://github.com/msoultanidis/quillnote/issues/209

<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="256"/><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="256"/><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="256"/>

## Migration
Backups from [Notally](https://github.com/OmGodse/Notally) can be converted into Quillpad compatible backups using this [python script](https://gist.github.com/nWestie/224d14a6efd00661b5c93040c7511816)

Notes from [Google Keep](https://www.google.com/keep/) can be converted to Quillpad compatible backups using [gk2qp](https://github.com/l0f3n/gk2qp).

## Translations

Please see the [Contributing Guidelines](.github/CONTRIBUTING.md#translations) for information on how to help translate Quillpad!
