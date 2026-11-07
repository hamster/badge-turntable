# Badge Turntable

An Android app, and the tools around it, for photographing badges for [the hamster badge museum](https://github.com/hamster/badges). A phone on a tripod drives an IR turntable inside a light box, reads a printed encoder ring to know the table's angle, takes the four views and a looping spin video, and produces a badge folder ready for the site.

| Path | What it is |
| --- | --- |
| [Spec.md](Spec.md) | The app spec: the source of truth for behaviour |
| [Remote-API.md](Remote-API.md) | The API between the phone and the site editor's Turntable panel |
| [Overview.md](Overview.md) | Background research the spec grew from |
| [badge-turntable/](badge-turntable/README.md) | The Android app (open this folder in Android Studio), with the [mock phone](badge-turntable/remote-mock/README.md) for testing the editor without one |
| [encoder-ring/](encoder-ring/README.md) | Generator for the printed marker band (paper SVG or ZPL labels) |
| [ir-tools/](ir-tools/README.md) | Capturing and replaying the turntable remote's IR codes from a PC |

## The site repo

This used to be `apps/` in [hamster/badges](https://github.com/hamster/badges), and moved here with its history. Two things still live in the site repo:

- The editor's **Turntable** panel ([edit/turntable.js](https://github.com/hamster/badges/blob/main/edit/turntable.js)), the client side of [Remote-API.md](Remote-API.md). Change both together.
- The badge template ([_template/index.md](https://github.com/hamster/badges/blob/main/_template/index.md)). The app bundles a copy, refreshed at build time when the site is checked out beside this repo.

Keep the two side by side:

```
Badges/
  badges/            hamster/badges (the site and the editor)
  badge-turntable/   this repo
```

With that layout the app build picks up template changes, and `python badge-turntable/remote-mock/mock_phone.py` serves the editor from the site checkout. Elsewhere, use `-PbadgesRepo=<path>` for the build and `--site <path>` for the mock.
