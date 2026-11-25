# Harkara emergency handbook

An offline emergency handbook for Harkara, in English and Bangla, for neighbourhoods and campuses in Dhaka. It covers first aid, floods and waterlogging, cyclones, fire, earthquakes, power cuts, staying in touch without a network, crowd safety and a go-bag and family plan. Every page is stored on the phone and works with no signal.

**Not medical advice. In an emergency, call 999 first.** This handbook gives general first-aid and safety guidance. It does not replace a doctor, trained first-aider, the Fire Service or other emergency services, and it cannot cover every situation.

## Layout

- `index.json`: the table of contents. Each section has an `id`, a `group`, an `icon`, a `title`, the two Markdown files, and search `keywords` in both languages. It also lists the `sources` and the `emergencyNumbers` (with the `source` used to check each number).
- `en/<id>.md` and `bn/<id>.md`: one page per section, in plain Markdown: a `#` title, `##` headings, numbered steps, bullet lists and **bold** warnings. No images, HTML or links. Both languages have the same structure and the same facts.

## Writing rules

- Short sentences and numbered steps, in the order to do them.
- The first line says when to call 999.
- "Do not" warnings are in bold.
- Bangla is standard Bangla in Bengali script and uses the polite আপনি form, as public safety guidance in Bangladesh does. Terms match the app's Bangla strings (for example সেভার, পাড়ার নোড, ব্লুটুথ মেশ).
- Neutral and non-political. No brand names for medicines.

## Keeping it current

- Phone numbers were checked in November 2026 against the sources in `index.json`. Numbers that could not be checked were left out. Hospital numbers are deliberately not listed because they change often.
- The legal aid helpline moved from 16430 to 16699 in November 2025. The Shastho Batayon line (16263) stopped for some days in November 2026; check it again before each release.
- When you change a fact, change it in both languages and update `updated` in `index.json`.

## Licence

The handbook text is original writing for Harkara. You may use it under either the **Creative Commons Attribution-ShareAlike 4.0 International licence (CC BY-SA 4.0)** or the **GNU Affero General Public License, version 3 or later (AGPL-3.0-or-later)**, as part of Harkara, at your choice.

The facts are summarised, in our own words, from the public sources listed in `index.json`: the IFRC international first aid guidelines, the World Health Organization, St John Ambulance, the US CDC, the Bangladesh Department of Disaster Management, the Ministry of Disaster Management and Relief, the Bangladesh Meteorological Department, the Flood Forecasting and Warning Centre, the Cyclone Preparedness Programme and Bangladesh Red Crescent Society, the Bangladesh Fire Service and Civil Defence, the Directorate General of Health Services, and the government helplines. No text was copied from these sources. Their names are used only to credit them; this handbook is not endorsed by any of them.
