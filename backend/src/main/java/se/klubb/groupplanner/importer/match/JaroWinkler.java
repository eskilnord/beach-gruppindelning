package se.klubb.groupplanner.importer.match;

/**
 * Hand-rolled Jaro-Winkler string similarity (spec §8.7 "liknande namn" / docs/design/02-product-data-ui.md
 * §2: "Jaro-Winkler >= 0.92 on 'förnamn efternamn'"). No dependency added for this - only POI and
 * Commons CSV are approved for M3 (see backend/docs/m3-notes.md) - so the well-known algorithm is
 * implemented directly here rather than pulling in Apache Commons Text.
 */
public final class JaroWinkler {

    private static final int MAX_PREFIX_LENGTH = 4;
    private static final double PREFIX_SCALING_FACTOR = 0.1;

    private JaroWinkler() {
    }

    /** Similarity in {@code [0.0, 1.0]}; {@code 1.0} means identical strings. */
    public static double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }

        double jaro = jaroSimilarity(a, b);

        int prefixLength = 0;
        int maxPrefix = Math.min(MAX_PREFIX_LENGTH, Math.min(a.length(), b.length()));
        while (prefixLength < maxPrefix && a.charAt(prefixLength) == b.charAt(prefixLength)) {
            prefixLength++;
        }

        return jaro + prefixLength * PREFIX_SCALING_FACTOR * (1.0 - jaro);
    }

    private static double jaroSimilarity(String a, String b) {
        int aLen = a.length();
        int bLen = b.length();
        int matchDistance = Math.max(aLen, bLen) / 2 - 1;
        if (matchDistance < 0) {
            matchDistance = 0;
        }

        boolean[] aMatches = new boolean[aLen];
        boolean[] bMatches = new boolean[bLen];

        int matches = 0;
        for (int i = 0; i < aLen; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, bLen);
            for (int j = start; j < end; j++) {
                if (bMatches[j] || a.charAt(i) != b.charAt(j)) {
                    continue;
                }
                aMatches[i] = true;
                bMatches[j] = true;
                matches++;
                break;
            }
        }

        if (matches == 0) {
            return 0.0;
        }

        double transpositions = 0;
        int k = 0;
        for (int i = 0; i < aLen; i++) {
            if (!aMatches[i]) {
                continue;
            }
            while (!bMatches[k]) {
                k++;
            }
            if (a.charAt(i) != b.charAt(k)) {
                transpositions++;
            }
            k++;
        }
        transpositions /= 2.0;

        return (matches / (double) aLen + matches / (double) bLen + (matches - transpositions) / matches) / 3.0;
    }
}
