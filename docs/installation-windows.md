# Installera Gruppindelning på Windows

Den här guiden är för styrelsemedlemmar/kansliet som ska installera appen på en
Windows-dator (Windows 10 eller 11, 64-bitars). Du behöver **inte** installera Java,
Node eller något annat i förväg — allt som behövs följer med i appen. Ett undantag är
komponenten "Microsoft Edge WebView2" (används av nästan alla moderna Windows-datorer
redan) — installationsprogrammet laddar ner den automatiskt om den saknas.

## 1. Ladda ner appen

1. Gå till projektets Releases-sida på GitHub:
   `https://github.com/eskilnord/beach-gruppindelning/releases`
2. Under den senaste versionen, klicka på "Assets" (om listan är hopfälld) och ladda ner
   installationsfilen som slutar på **`.exe`** (t.ex.
   `Gruppindelning_0.1.0_x64-setup.exe`).
3. Filen hamnar normalt i din mapp **Hämtade filer (Downloads)**.

## 2. Installera

1. Dubbelklicka på den nedladdade `.exe`-filen.
2. Windows SmartScreen visar troligen en blå ruta: **"Windows skyddade din dator"**.
   Det beror på att appen ännu inte är digitalt signerad (samma som för många mindre,
   nya program) — det är förväntat:
   1. Klicka på länken **"Mer information"** (More info) i rutan.
   2. Klicka på knappen **"Kör ändå"** (Run anyway) som dyker upp längst ner.
3. Installationsguiden startar. Appen installeras bara för din egen användare, så du
   behöver **inte** vara administratör. Klicka dig igenom guiden (**Installera**/
   **Install**).
4. Om Microsoft Edge WebView2 saknas på datorn laddas den ner och installeras
   automatiskt av installationsprogrammet — detta kräver internetuppkoppling.

## 3. Starta appen

- Sök efter **"Gruppindelning"** i Startmenyn och öppna den.
- Om SmartScreen visar samma varning igen vid första körningen: upprepa
  **"Mer information" → "Kör ändå"** som i steg 2.

Efter det första godkännandet startar appen normalt vid varje senare tillfälle.

## Felsökning

**Appen visar en felskärm / "Backend startar inte":**
- Klicka på knappen **"Försök igen"** i felskärmen — det löser oftast problemet.
- Om det inte hjälper: loggfilen finns här (klistra in raden nedan i Utforskarens
  adressfält och tryck Enter — `%APPDATA%` är en dold systemmapp som annars inte syns):

  ```
  %APPDATA%\se.klubb.groupplanner\logs\backend.log
  ```

  Skicka den filen till den som underhåller appen tillsammans med en beskrivning av vad
  som hände.

**SmartScreen blockerar helt och "Mer information" saknas:** vissa arbetsdatorer har en
IT-policy som blockerar okända program helt. Kontakta din IT-avdelning eller den som
underhåller appen.

**Antivirusprogrammet flaggar filen eller tar bort den:** detta kan hända för program
som ännu inte är digitalt signerade. Kontakta den som underhåller appen innan du lägger
till ett undantag i antivirusprogrammet.

**En ny version har släppts:** ladda ner den nya `.exe`-filen och kör den — den
installerar över/uppdaterar den befintliga installationen. Du kan behöva godkänna
SmartScreen-varningen igen för den nya filen.
