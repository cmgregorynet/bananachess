package com.charlesbarton.bananachess.engine.online

/**
 * Strings written by the Swift engine itself, for the shapes `golden.json.gz` does not
 * happen to contain: a draw, the two wildcard win reasons, `turnPassed`,
 * `blenderRespawned`, `smoothie`, a `respawnTurn`, and a match id full of characters
 * that need escaping.
 *
 * Produced on 2026-11-23 by a throwaway Swift executable that imported
 * `BananaChessEngine` (rules 18, schema 16), built the payloads that
 * `MatchCodecTest` rebuilds in Kotlin, and printed `MatchCodec.encode` and
 * `MatchLink.url(for:)` for each. They are observations, not expectations: do not edit
 * them to make a test pass.
 */
internal object SwiftSamples {
    /** The match id of [EVERYTHING], which exercises every escape Foundation applies. */
    const val AWKWARD_ID: String =
        "a/b \"q\" \\ \u0001\b\t\n\u000C\r\u001F\u007F \u00E9 \u2192 \u2028\u2029 \uD83C\uDF4C </script>"

    /** [AWKWARD_ID] exactly as Swift wrote it between the quotes. */
    const val AWKWARD_ID_ESCAPED: String =
        "a\\/b \\\"q\\\" \\\\ \\u0001\\b\\t\\n\\f\\r\\u001f\u007F \u00E9 \u2192 \u2028\u2029 \uD83C\uDF4C <\\/script>"

    const val ID_PLACEHOLDER: String = "@ID@"

    /** Every event case and a drawn match. The match id is [ID_PLACEHOLDER]; put [AWKWARD_ID_ESCAPED] there. */
    const val EVERYTHING: String =
        """{"lastMove":{"action":{"restoreBlender":{"at":{"column":4,"row":5},"pieceID":"one.blender.0"}}""" +
            ""","events":[{"moved":{"from":{"column":0,"row":4},"pieceID":"one.fruit.0","to":{"column":0,"row":""" +
            """3}}},{"blenderBattle":{"_0":{"attacker":"one","attackerBonus":1,"attackerFruit":1,"attackerRol""" +
            """l":6,"defenderFruit":0,"defenderRoll":3},"at":{"column":2,"row":2}}},{"blenderDestroyed":{"at":""" +
            """{"column":2,"row":2},"by":"one.blender.0","owner":"two","pieceID":"two.blender.1"}},{"blenderRes""" +
            """tored":{"at":{"column":4,"row":5},"cost":3,"owner":"one","pieceID":"one.blender.0"}},{"blenderRe""" +
            """spawned":{"at":{"column":4,"row":0},"owner":"two","pieceID":"two.blender.1"}},{"bananaReturned":""" +
            """{"pieceID":"one.fruit.0","to":{"column":0,"row":4}}},{"blended":{"at":{"column":0,"row":3},"awar""" +
            """dedTo":"two","fruitOwner":"one","kind":"banana","pieceID":"one.fruit.0","points":-1}},{"delivere""" +
            """d":{"at":{"column":0,"row":0},"kind":"strawberry","owner":"one","pieceID":"one.fruit.0","point""" +
            """s":4}},{"scoreChanged":{"newScore":-7,"player":"two"}},{"turnPassed":{"skipped":"two"}},{"turnCh""" +
            """anged":{"active":"one"}},{"gameEnded":{"outcome":{"draw":{}}}},{"gameEnded":{"outcome":{"win":""" +
            """{"_0":"one","by":"bananaSplit"}}}},{"gameEnded":{"outcome":{"win":{"_0":"two","by":"blenderama"}""" +
            """}}},{"pineappleTurnedGolden":{"owner":"one","pieceID":"one.fruit.0"}},{"smoothiePoured":{"playe""" +
            """r":"two","points":3}},{"smoothie":{"pieceIDs":["two.blender.1","one.blender.0"],"player":"on""" +
            """e","respawnTurn":9}},{"smoothie":{"pieceIDs":[],"player":"two","respawnTurn":0}}],"playedBy":"on""" +
            """e"},"matchID":"@ID@","moveNumber":2,"rulesVersion":18,"schemaVersion":16,"state":{"activePlaye""" +
            """r":"two","blenderBattles":true,"boardHistory":[0,1,9223372036854775807,9223372036854775808,18446""" +
            """744073709551615],"boardSetup":"random","boardWrap":"all","history":[{"restoreBlender":{"at":{"co""" +
            """lumn":4,"row":5},"pieceID":"one.blender.0"}},{"move":{"_0":{"destination":{"column":0,"row":3}""" +
            ""","origin":{"column":0,"row":4},"pieceID":"one.fruit.0"}}}],"pieces":[{"home":{"column":1,"row":5""" +
            """},"id":"one.blender.0","kind":"blender","owner":"one","position":{"column":1,"row":5}},{"id":"on""" +
            """e.fruit.0","kind":"banana","owner":"one","position":{"column":0,"row":4}},{"home":{"column":4,"r""" +
            """ow":0},"id":"two.blender.1","kind":"blender","owner":"two","respawnTurn":9}],"ruleSet":"wildcar""" +
            """d","scores":{"one":12,"two":-7},"status":{"ended":{"_0":{"draw":{}}}},"turnNumber":8,"wildcard""" +
            """s":{"bananasDelivered":{"one":2,"two":0},"blenderamaCountered":{"one":true,"two":false},"ownFrui""" +
            """tBlended":{"one":0,"two":5},"smoothie":{"one":{"banana":true,"pineapple":false,"strawberry":fals""" +
            """e},"two":{"banana":false,"pineapple":true,"strawberry":false}},"smoothieFired":{"one":false,"tw""" +
            """o":true}}}}"""

    /** `MatchLink.url(for:)` of [EVERYTHING]. */
    const val EVERYTHING_LINK: String =
        """bananachess://m/1/rVZNbttGFL6KMOtpQkqUJQtBF4qTtIu2hm20i9AIRuTIGpjksDNDC4JAJKus0k0v0G19jtzEJ8gR8u""" +
            """aH5FCSlRgIDNjSzJvvfe_ve96ijEj1G7-jaLZFJFGMF_qToFJxQecZLVIqzJ3SvxOeVTlYRBgJvkazcY1RyWhCfz1DM8QL-m""" +
            """xhnzwLUA139I4WSqLZ2y3KwUmqMZaC5z5W4LCiPaylqJgCJIwUP_RiVIOPLXIu50SpzMTxLrCMFUluNXsNBiDNwZwXFXAKu5""" +
            """PX2lHv5IJnGZqdYJTSpUF3JkF3Yk1GNd7JzdCxG_bYnUFGBd_YFDz2AKPFZi-PGPF1YcJQa478HMH31i5EvrcLW78DzvzKJV""" +
            """zC3ahzYPN0pKC-g5LAqyMegvppzEkBPxdUVcLBPq0ZIj_dB2h1TQMFWxMBRle8pWbQ_-il4ZYVAON47aWlo1NyZnr8p9AQSG""" +
            """nG7ujB3AdeZhw6NAVZL6gQG3S8DPv-IuNOJlDnlytS3FiPBV1f6iPgMwHbjGzaAhh7nd5zIqW1lresLPXHvoGHpzVBq4MhZQ""" +
            """xuSE5fNTnmlUp4bqYuhUjgb10ftVqzohlRF6dpeZvlyzKDIXsCgi2eRbDtRHLSAJSsoKQsM3plWuoNz1Jqnn5Pnm1yc87Vit""" +
            """FzXrmC9hLa1WLUM_eaV2vfTrfjnbG69spkGQk7XJo2mp0eg77Gu4x6b4O6bizS-aYpIkY5UcnKhEzi54tBjP6O0SCOB3EVBE""" +
            """EYL2IVF_EyFvp7uHw_-Hw_ePj47-Dhw_8PH-4HX_7759PgRfxcJoKV6mdwqrX99ypfaB5azaqMyj-pkGabhFMMXbqiOemOQF""" +
            """ilIqrdOnf0vB9GT9MhUiUqCqcc5vYXprUNonkb4BCfDoej0WQYjE6m42gyGU-DyYGzKQ6nUXQyiaJgMpoEp-NxeBKOrx3iJY""" +
            """hOCa4FKVLYTe70L0H0IQGVx2jVOv1hy9GuxKaVtXBIxQrSrOBDusUFu2EHbx9fnrXtAX1nF_HKzVEDEXp0WbrHtBNCF--eTn""" +
            """HJdkl3mDrOFrUTsV1x_Takp_N4Pwh_6TCnZ72ZezSIA2NzqjOmmxgaAyzWLEsTaAiErdpKoyFAdBZCs-v3oLW17ejKXLYryF""" +
            """XWU0ajr82swGQ04OadTYc881eIcdT40eF1QveSV4Xq2dkxMaZLkklqV7D532XeLUZjGjg7XXZfXcxlQ6VBbKXU4WJ_cbWuDF""" +
            """731Fl6by3Y_lOPwmvmx-MgDLB-rFP4FQ"""

    /** The state of [EVERYTHING], won by Banana Split, with no last move. */
    const val BANANA_SPLIT: String =
        """{"matchID":"m","moveNumber":2,"rulesVersion":18,"schemaVersion":16,"state":{"activePlayer":"tw""" +
            """o","blenderBattles":true,"boardHistory":[0,1,9223372036854775807,9223372036854775808,18446744073""" +
            """709551615],"boardSetup":"random","boardWrap":"all","history":[{"restoreBlender":{"at":{"column":""" +
            """4,"row":5},"pieceID":"one.blender.0"}},{"move":{"_0":{"destination":{"column":0,"row":3},"origi""" +
            """n":{"column":0,"row":4},"pieceID":"one.fruit.0"}}}],"pieces":[{"home":{"column":1,"row":5},"i""" +
            """d":"one.blender.0","kind":"blender","owner":"one","position":{"column":1,"row":5}},{"id":"one.fr""" +
            """uit.0","kind":"banana","owner":"one","position":{"column":0,"row":4}},{"home":{"column":4,"row":""" +
            """0},"id":"two.blender.1","kind":"blender","owner":"two","respawnTurn":9}],"ruleSet":"wildcard","s""" +
            """cores":{"one":12,"two":-7},"status":{"ended":{"_0":{"win":{"_0":"one","by":"bananaSplit"}}}},"tu""" +
            """rnNumber":8,"wildcards":{"bananasDelivered":{"one":2,"two":0},"blenderamaCountered":{"one":true""" +
            ""","two":false},"ownFruitBlended":{"one":0,"two":5},"smoothie":{"one":{"banana":true,"pineapple":f""" +
            """alse,"strawberry":false},"two":{"banana":false,"pineapple":true,"strawberry":false}},"smoothieFi""" +
            """red":{"one":false,"two":true}}}}"""

    /** The state of [EVERYTHING], won by Blenderama, with no last move. */
    const val BLENDERAMA: String =
        """{"matchID":"m","moveNumber":2,"rulesVersion":18,"schemaVersion":16,"state":{"activePlayer":"tw""" +
            """o","blenderBattles":true,"boardHistory":[0,1,9223372036854775807,9223372036854775808,18446744073""" +
            """709551615],"boardSetup":"random","boardWrap":"all","history":[{"restoreBlender":{"at":{"column":""" +
            """4,"row":5},"pieceID":"one.blender.0"}},{"move":{"_0":{"destination":{"column":0,"row":3},"origi""" +
            """n":{"column":0,"row":4},"pieceID":"one.fruit.0"}}}],"pieces":[{"home":{"column":1,"row":5},"i""" +
            """d":"one.blender.0","kind":"blender","owner":"one","position":{"column":1,"row":5}},{"id":"one.fr""" +
            """uit.0","kind":"banana","owner":"one","position":{"column":0,"row":4}},{"home":{"column":4,"row":""" +
            """0},"id":"two.blender.1","kind":"blender","owner":"two","respawnTurn":9}],"ruleSet":"wildcard","s""" +
            """cores":{"one":12,"two":-7},"status":{"ended":{"_0":{"win":{"_0":"two","by":"blenderama"}}}},"tur""" +
            """nNumber":8,"wildcards":{"bananasDelivered":{"one":2,"two":0},"blenderamaCountered":{"one":true""" +
            ""","two":false},"ownFruitBlended":{"one":0,"two":5},"smoothie":{"one":{"banana":true,"pineapple":f""" +
            """alse,"strawberry":false},"two":{"banana":false,"pineapple":true,"strawberry":false}},"smoothieFi""" +
            """red":{"one":false,"two":true}}}}"""

    /** `MatchPayload.newMatch` with default options. */
    const val FRESH: String =
        """{"matchID":"550E8400-E29B-41D4-A716-446655440000","moveNumber":0,"rulesVersion":18,"schemaVersio""" +
            """n":16,"state":{"activePlayer":"one","blenderBattles":false,"boardHistory":[10941334533497594564]""" +
            ""","boardSetup":"standard","boardWrap":"flat","history":[],"pieces":[{"home":{"column":1,"row":5}""" +
            ""","id":"one.blender.0","kind":"blender","owner":"one","position":{"column":1,"row":5}},{"home":""" +
            """{"column":4,"row":5},"id":"one.blender.1","kind":"blender","owner":"one","position":{"column":4""" +
            ""","row":5}},{"home":{"column":0,"row":4},"id":"one.fruit.0","kind":"banana","owner":"one","positi""" +
            """on":{"column":0,"row":4}},{"home":{"column":1,"row":4},"id":"one.fruit.1","kind":"strawberry","o""" +
            """wner":"one","position":{"column":1,"row":4}},{"home":{"column":2,"row":4},"id":"one.fruit.2","ki""" +
            """nd":"banana","owner":"one","position":{"column":2,"row":4}},{"home":{"column":3,"row":4},"id":"o""" +
            """ne.fruit.3","kind":"pineapple","owner":"one","position":{"column":3,"row":4}},{"home":{"column":""" +
            """4,"row":4},"id":"one.fruit.4","kind":"strawberry","owner":"one","position":{"column":4,"row":4}}""" +
            """,{"home":{"column":5,"row":4},"id":"one.fruit.5","kind":"pineapple","owner":"one","position":{"c""" +
            """olumn":5,"row":4}},{"home":{"column":1,"row":0},"id":"two.blender.0","kind":"blender","owner":"t""" +
            """wo","position":{"column":1,"row":0}},{"home":{"column":4,"row":0},"id":"two.blender.1","kind":"b""" +
            """lender","owner":"two","position":{"column":4,"row":0}},{"home":{"column":5,"row":1},"id":"two.fr""" +
            """uit.0","kind":"banana","owner":"two","position":{"column":5,"row":1}},{"home":{"column":4,"row":""" +
            """1},"id":"two.fruit.1","kind":"strawberry","owner":"two","position":{"column":4,"row":1}},{"hom""" +
            """e":{"column":3,"row":1},"id":"two.fruit.2","kind":"banana","owner":"two","position":{"column":3""" +
            ""","row":1}},{"home":{"column":2,"row":1},"id":"two.fruit.3","kind":"pineapple","owner":"two","pos""" +
            """ition":{"column":2,"row":1}},{"home":{"column":1,"row":1},"id":"two.fruit.4","kind":"strawberr""" +
            """y","owner":"two","position":{"column":1,"row":1}},{"home":{"column":0,"row":1},"id":"two.fruit.""" +
            """5","kind":"pineapple","owner":"two","position":{"column":0,"row":1}}],"ruleSet":"standard","scor""" +
            """es":{"one":0,"two":0},"status":{"inProgress":{}},"turnNumber":1,"wildcards":{"bananasDelivered":""" +
            """{"one":0,"two":0},"blenderamaCountered":{"one":false,"two":false},"ownFruitBlended":{"one":0,"tw""" +
            """o":0},"smoothie":{"one":{"banana":false,"pineapple":false,"strawberry":false},"two":{"banana":fa""" +
            """lse,"pineapple":false,"strawberry":false}},"smoothieFired":{"one":false,"two":false}}}}"""

    /** `MatchLink.url(for:host:)` of [FRESH] with host `bananachess.example`. */
    const val FRESH_HTTPS_LINK: String =
        """https://bananachess.example/m/1/nZZRb5swEMe_i59JZcBOG96WpdX2MlWatD1Ue3DBWayBjYwpqqJ8951dCETFJkqi""" +
            """SOF8_v_uzj4dR1Qxkx--71CGKMWPDwTj1WOy2a5IvCOrL_fxekXIek0pgRWMUYQq9cZ_tNUr1yjDEdJtyZtfXDdCSZTFDxFq""" +
            """8gOv2Ghag8kww1F2RCw34o0_l-zdbkdKclB8LbksuN4yY0ALZXtWNhzMiunim2iM0u8oe4nxhsRpSij8Nvd0Q-ia_Om9fnLT""" +
            """1qAHHFnAM-rtvzWz5n3JDJgOZy3YVwueW9jLER1U5YLLVdlWNmLISnUoo6cIieIjzLs-yDtbgn9CWnNvAoPq5CSfWjXCuNzn""" +
            """NE_RZyIJEePbiCRIxP0qmRL3uhXmIkMm4XsVbhScw8V-3CS9xmjWwcWCM7oGGQeRiR-Z3JRhEsSlflw64mohOavrkl9FTINE""" +
            """4ieSm2tKgkjqR9Jbk6RB4nDGeCCaTl3Xi-C4cG9wsKyzxGAv-okkSBwqEE-Jy73ox42CoQRncEu9uJzhPDL1I0O96MelQVzi""" +
            """xy30op-YBImxn7jUi8v3dB6J_ciFXvQTR80TjEg72mGyoou52uRK27l5dC1td1g51y12yrduSchnrf6Cn32C6JFptRxeGiCt""" +
            """TpRFDnrO-ePYmx0v4dVA82JWu-82VrGvqpXmwq9_X3C-7v_J5fpka7F1--Y1m0opcxD8vDjEcpYcqzdYJkd4ZjnBm_ZOgngS""" +
            """4ZTg8x8"""
}
