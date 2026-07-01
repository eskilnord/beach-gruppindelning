# Installera Gruppindelning på Mac

Den här guiden är för styrelsemedlemmar/kansliet som ska installera appen på en Mac
(macOS 15 eller senare). Du behöver **inte** installera Java, Node eller något annat i
förväg — allt som behövs följer med i appen.

## 1. Ladda ner appen

1. Gå till projektets Releases-sida på GitHub:
   `https://github.com/eskilnord/beach-gruppindelning/releases`
2. Under den senaste versionen, klicka på "Assets" (om listan är hopfälld) och ladda ner
   filen som slutar på **`.dmg`** (t.ex. `Gruppindelning_0.1.0_aarch64.dmg`).
3. Filen hamnar normalt i din mapp **Nedladdningar (Downloads)**.

## 2. Installera

1. Dubbelklicka på den nedladdade `.dmg`-filen. Ett litet fönster öppnas med
   Gruppindelning-ikonen och en genväg till mappen **Program (Applications)**.
2. Dra Gruppindelning-ikonen till mappen **Program**.
3. Stäng fönstret. Du kan mata ut (eject) skivavbilden i Finder om du vill — den
   nedladdade `.dmg`-filen kan sedan raderas, appen ligger nu kvar i **Program**.

## 3. Öppna appen första gången (macOS varnar — det är förväntat)

Appen är i det här tidiga skedet **inte signerad av Apple** (ADR-003 i projektets
tekniska dokumentation), så macOS Gatekeeper blockerar den första körningen. Så här
öppnar du den ändå, en gång:

1. Öppna mappen **Program (Applications)** och dubbelklicka på **Gruppindelning**.
2. Du får ett meddelande i stil med *"Gruppindelning" kan inte öppnas eftersom
   utvecklaren inte kan verifieras* (eller liknande). Klicka **OK**/stäng rutan.
3. Öppna **Systeminställningar** (System Settings) → **Integritet och säkerhet**
   (Privacy & Security).
4. Scrolla ner till avsnittet **Säkerhet** längst ner på sidan. Där ska det stå att
   "Gruppindelning" blockerades, med en knapp **Öppna ändå** (Open Anyway).
5. Klicka **Öppna ändå** och ange ditt Mac-lösenord (eller Touch ID) om du blir ombedd.
6. Ett nytt bekräftelsefönster visas — klicka **Öppna**.

Appen startar nu. Detta steg (steg 3–6) behöver du bara göra **en gång** — efter det
öppnas appen normalt med ett dubbelklick, precis som vilken annan app som helst.

> **Om macOS istället säger att appen "är skadad och kan inte öppnas"** (inte samma sak
> som varningen ovan): försök **inte** att fixa detta själv (t.ex. via Terminal-kommandon).
> Det är ett tecken på ett paketeringsfel och inte det förväntade beteendet. Kontakta den
> som underhåller appen och rapportera exakt vilket meddelande du fick.

## 4. Första gången appen körs

När appen öppnas startar den automatiskt en lokal bakgrundsprocess (den inbyggda
"motorn" som räknar ut grupperna). Det tar oftast någon sekund. Du behöver inte göra
något — appen visar en laddningsindikator tills den är klar.

## Felsökning

**Appen visar en felskärm / "Backend startar inte":**
- Klicka på knappen **"Försök igen"** i felskärmen — det löser oftast problemet.
- Om det inte hjälper: loggfilen finns här (kopiera in raden i Finder via
  **Gå (Go) → Gå till mapp…**, `Cmd+Shift+G`):

  ```
  ~/Library/Application Support/se.klubb.groupplanner/logs/backend.log
  ```

  Skicka den filen till den som underhåller appen tillsammans med en beskrivning av vad
  som hände.

**"Gruppindelning är skadad och kan inte öppnas":** se rutan i steg 3 ovan — rapportera
detta, försök inte kringgå det själv.

**Appen startar inte alls:** kontrollera att du kopierade hela appen till mappen
**Program** (steg 2) och inte bara dubbelklickade på den direkt inifrån skivavbilden —
appen behöver ligga kvar på disken permanent, inte köras direkt från `.dmg`-filen.

**En ny version har släppts:** ladda ner den nya `.dmg`-filen och upprepa steg 1–3 (du
kan behöva godkänna "Öppna ändå" igen för den nya versionen, eftersom det är en ny fil).
