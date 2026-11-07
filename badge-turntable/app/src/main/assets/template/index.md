---
layout: badge

# Display name of the badge
title: "Badge Name Here"

# Directory slug — must match this directory name AND assets/badges/{con}/{slug}/
slug: badge-slug-here

# Unique 3-digit hex identifier printed on the physical badge (e.g. 001, A3F)
badge_id: ""

# Four-digit year the badge was made / first appeared
year: 2025

# Convention / origin:
#   defcon | saintcon | dc503 | queercon | layerone | toorcon | other
con: defcon

# Human-readable event name (optional)
event: "DEF CON 33"

# Badge form factor:
#   badge      - full conference main badge
#   sao        - Shitty/Sophisticated Add-On (SAO connector)
#   minibadge  - SAINTCON minibadge standard (credit-card sized, edge connector)
#   standalone - independent device / art piece
#   entry      - physical entry badge with no electronics
#   other      - doesn't fit above categories
type: badge

# Production run / distribution variant:
#   regular | limited | press | speaker | staff | volunteer | prototype | artist | other
#   Leave blank if not applicable.
edition: ""

# Creator(s) — list one per line; a single creator can use the inline form
makers:
  - "Creator Name"

# Maker group / organization (e.g. "Dual Gang", "DCzia", "Queercon Badge Team")
group: ""

# Set to false for entry/art badges with no electronics
electronics: true

# Microcontroller / main chip (e.g. ESP32-S3, RP2040, ATtiny85)
mcu: ""

# Display type:
#   none | oled | eink | lcd | led-matrix | 7seg | neopixel-matrix | other
display: none

# Power configuration
power:
  # For SAOs / minibadges — which connectors can supply power:
  #   [battery, usb, sao]  or any subset
  sources: []
  # Battery chemistry: none | rechargeable | non-rechargeable
  battery: none
  # Cell type (if battery != none): CR2032 | CR2016 | 18650 | LiPo | AA | AAA | 9V | other
  battery_cell: ""
  # Input current draw in milliamps (SAO / minibadge — leave null if unknown)
  current_ma: null

# SAO device connector details (type: sao only)
# Omit this block entirely for non-SAO badges.
sao:
  # Connector spec version: SAOv1 | SAOv2 | SAOv3
  version: ""
  # Which pins this SAO device actually uses:
  #   i2c | gpio1 | gpio2 | 3v3
  implements: []

# Minibadge device details (type: minibadge only)
# Omit this block entirely for non-minibadge badges.
minibadge:
  # Which signals from the SAINTCON minibadge spec this device implements:
  #   i2c | clk | gpio | 3v3
  implements: []

# SAO ports this badge provides as a host (any type)
# Omit if the badge has no SAO ports.
# sao_ports:
#   count: 4
#   implements: [i2c, gpio, 3v3]   # signals provided to connected SAOs
#   power: 3v3                     # 3v3 | vbat | 5v

# Minibadge ports this badge provides as a host (any type)
# Omit if the badge has no minibadge ports.
# minibadge_ports:
#   count: 8
#   implements: [i2c, clk, 3v3]    # signals provided to connected minibadges
#   power: 3v3                     # 3v3 | vbat | 5v

# USB connector (if any): none | USB-C | micro-USB | mini-USB
interface: none

# Debug / flash interface: none | JTAG | SWD | UART | USB-DFU | other
programming: none

# Wireless / radio features (list any that apply):
#   wifi | bluetooth | ir | nfc | lora | zigbee | rf | other
connectivity: []

# Notable features (free-form list — add whatever is interesting):
#   neopixels | ws2812 | led-matrix | e-ink | lcd | oled | speaker | buzzer
#   buttons | joystick | accelerometer | gyro | sd-card | qr-code | other
features: []

# How many were made / how easy to find:
#   unknown | limited | small-run | mass-produced
rarity: unknown

# Where / when you acquired this badge
acquisition:
  date: ""       # YYYY-MM-DD
  source: ""     # e.g. "DEF CON badge swap", "Tindie order"

# Documentation URL (Hackaday, wiki, project page — separate from source code)
docs_url: ""

# Source code / schematic repo (GitHub, GitLab, etc.)
source_repo: ""

# Vendor(s) where this badge was or is sold
sold_at: []
# sold_at:
#   - vendor: "Tindie"
#     url: "https://www.tindie.com/products/..."
#   - vendor: "GroupGets"
#     url: "https://groupgets.com/..."

# Primary / current purchase URL (short-form alternative to sold_at)
purchase_url: ""

# Photos — place image files alongside this index.md in _badges/{con}/{slug}/
# First image is used as the card thumbnail.
images: []
# images:
#   - filename: front.jpg
#     caption: "Front of badge"
#   - filename: back.jpg
#     caption: "Back of badge"
#   - filename: detail-screen.jpg
#     caption: "OLED display showing boot screen"

# External links — YouTube entries render as embedded video; type "web" renders as a link
links: []
# links:
#   - label: "Unboxing video"
#     url: "https://www.youtube.com/watch?v=..."
#     type: youtube
#   - label: "Project page"
#     url: "https://example.com"
#     type: web

# stub | wip | complete
status: stub
---

Add notes here. Markdown supported.

What's interesting about this badge? What puzzle or game did it have? Any quirks
during setup or flashing? Interesting history or story behind it?
