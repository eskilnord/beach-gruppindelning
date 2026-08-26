# Importera med ett klick

När du laddar upp en anmälningsfil (`.xlsx` eller `.csv`) analyserar appen filen direkt och
försöker välja blad, hitta rubrikraden och mappa kolumner automatiskt.

## Vad som händer

1. **Välj fil** – ladda upp filen som vanligt.
2. Om appen är säker på sina val öppnas **Granska och importera** i stället för den långa
   guiden. Där ser du:
   - vilket blad som valts och varför
   - hur många kolumner som mappats respektive ignoreras
   - hur många spelarader som hittats (och hur många som hoppas över)
   - en tabell med varje kolumn, mål och en kort svensk förklaring
3. Klicka **Importera** – klart.
4. Behöver du ändra något? Klicka **Justera** så öppnas den vanliga steg-för-steg-guiden med
   allt förifyllt.

## Vad appen bestämmer automatiskt

| Beslut | Hur |
|---|---|
| Blad | Matchar bladnamn mot planens kategori eller namn (t.ex. blad `Herr` till plan med kategori Herr). Finns bara ett blad används det. |
| Rubrikrad | Samma heuristik som tidigare (inkl. igenkänning av grupperade exporter). |
| Kolumnmappning | Sparad importmall (samma rubriker som senast) har företräde, annars synonymtabell på svenska/engelska. |
| Ignorerade kolumner | Personnummer (integritet), informationskolumner (t.ex. RankInfo), tidsönskemål (anges i spelarvyn efter import). |
| Gruppblock | I filer där gruppnamn/tid/tränare ligger staplade i kolumn A läses gruppstrukturen automatiskt. |

## Mallar

Om du tidigare sparat en importmall med samma kolumnrubriker visas bannertexten
**Samma filformat som senast — mallen används**. Då återanvänds den sparade mappningen.

## När guiden fortfarande behövs

Appen skickar dig till steg-för-steg-guiden om något är osäkert, till exempel:

- flera blad utan tydlig match mot planen
- kolumner som inte känns igen
- ingen namnkolumn hittades

Du kan alltid välja **Justera** från granskningssidan.
